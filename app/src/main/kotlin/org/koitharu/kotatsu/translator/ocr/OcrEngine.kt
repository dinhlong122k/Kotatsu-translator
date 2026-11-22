package org.koitharu.kotatsu.translator.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.translator.domain.model.OcrMode
import org.koitharu.kotatsu.translator.domain.model.OcrNotReadyException
import org.koitharu.kotatsu.translator.domain.model.TextBlock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-device OCR: ONNX Runtime + PP-OCRv5 (detector shared, recognizer per language pack).
 * Port of `src/ocr/engine.ts`. Pages are processed one at a time; the image is never fully decoded:
 * detection reads downscaled tiles, recognition reads line regions at the original resolution.
 *
 * Models are never downloaded here: when something is missing [OcrNotReadyException] is thrown,
 * use [OcrModelStore.download].
 */
@Singleton
class OcrEngine @Inject constructor(
	@ApplicationContext private val context: Context,
	private val store: OcrModelStore,
) {

	private val mutex = Mutex()

	@Volatile
	private var releaseRequested = false

	private var detSession: OrtSession? = null
	private var bubbleSession: OrtSession? = null
	private var rec: LoadedRec? = null

	private class LoadedRec(val packId: String, val session: OrtSession, val dict: List<String>)

	private class FloatOutput(val data: FloatArray, val shape: LongArray)

	/**
	 * Recognizes the text of the page image at [uri] (`file://`, `file+zip://path#entry`).
	 * Boxes are in pixels of the original image, ids are `b0, b1, ...` in reading order.
	 * @param rtl right-to-left reading order of blocks inside a row (Japanese manga, not webtoon)
	 * @throws OcrNotReadyException the model pack of [lang] (or the shared detector) is not installed
	 */
	suspend fun recognize(uri: Uri, lang: String, mode: OcrMode, rtl: Boolean): List<TextBlock> {
		val pack = resolvePack(lang, mode)
		return mutex.withLock {
			try {
				withContext(Dispatchers.Default) {
					PageImageSource.open(context, uri).use { source ->
						when (mode) {
							OcrMode.FAST -> runFast(source, pack, rtl)
							OcrMode.ACCURATE -> runAccurate(source, pack, rtl)
						}
					}
				}
			} finally {
				closeIfRequested()
			}
		}
	}

	/**
	 * Creates the sessions a run would need and runs one tiny dummy inference on each, so the first page does not
	 * pay for it. Every failure is swallowed (also a missing pack).
	 */
	suspend fun warmUp(lang: String, mode: OcrMode) {
		runCatchingCancellable {
			val pack = resolvePack(lang, mode)
			mutex.withLock {
				try {
					withContext(Dispatchers.Default) {
						val det = getDet()
						val recognizer = getRec(pack)
						val bubble = if (mode == OcrMode.ACCURATE) getBubble() else null
						dummy { runFloat(det, FloatArray(3 * 32 * 32), longArrayOf(1, 3, 32, 32)) }
						dummy {
							runFloat(
								recognizer.session,
								FloatArray(3 * REC_H * 64),
								longArrayOf(1, 3, REC_H.toLong(), 64),
							)
						}
						if (bubble != null) {
							dummy { runBubble(bubble, FloatArray(3 * BUBBLE_SIZE * BUBBLE_SIZE), BUBBLE_SIZE, BUBBLE_SIZE) }
						}
					}
				} finally {
					closeIfRequested()
				}
			}
		}.onFailure {
			it.printStackTraceDebug()
		}
	}

	/** Closes all sessions (also when a run is in progress: then they are closed as soon as it ends). */
	fun release() {
		releaseRequested = true
		if (mutex.tryLock()) {
			try {
				closeIfRequested()
			} finally {
				mutex.unlock()
			}
		}
	}

	// ---- pipeline ----

	private suspend fun runFast(source: PageImageSource, pack: OcrPack, rtl: Boolean): List<TextBlock> {
		val recognizer = getRec(pack)
		val perTile = ArrayList<TileLines>()
		for (tile in DetPostProcessor.detTiles(source.width, source.height)) {
			perTile.add(TileLines(tile, detectIn(source, tile)))
		}
		val boxes = DetPostProcessor.mergeTileLines(perTile, source.height)
			.sortedWith(compareBy<Box>({ it.y }, { it.x }))
		val trimmed = DetPostProcessor.separateLines(boxes)
		val lines = ArrayList<RecognizedLine>()
		for (i in boxes.indices) {
			val r = recognizeLine(source, boxes[i], trimmed[i], pack, recognizer)
			val text = r.text.trim()
			if (text.isNotEmpty() && r.conf >= REC_MIN_CONF) {
				lines.add(RecognizedLine(boxes[i], text, r.conf))
			}
		}
		val groups = LineGrouper.groupLines(lines, source.width, source.height)
		return toBlocks(LineGrouper.readingOrder(groups, rtl) { it.box })
	}

	private suspend fun runAccurate(source: PageImageSource, pack: OcrPack, rtl: Boolean): List<TextBlock> {
		val recognizer = getRec(pack)
		// Many pages have no text at all: one cheap line-detector pass settles that before the heavy models run.
		if (max(source.width, source.height) <= QUICK_CHECK_MAX_SIDE) {
			if (detectIn(source, Box(0, 0, source.width, source.height)).isEmpty()) {
				return emptyList()
			}
		}
		val found = ArrayList<TextGroup>()
		for (region in detectRegions(source)) {
			// Top to bottom, or right to left when the region is mostly vertical columns.
			val lines = LineGrouper.sortLines(detectLines(source, region.box)) { it }
			val trimmed = DetPostProcessor.separateLines(lines)
			val keptBoxes = ArrayList<Box>()
			val keptTexts = ArrayList<String>()
			var confSum = 0.0
			for (i in lines.indices) {
				val r = recognizeLine(source, lines[i], trimmed[i], pack, recognizer)
				val text = r.text.trim()
				if (text.isNotEmpty() && r.conf >= REC_MIN_CONF) {
					keptBoxes.add(lines[i])
					keptTexts.add(text)
					confSum += r.conf
				}
			}
			if (keptTexts.isEmpty()) continue
			found.add(
				TextGroup(
					box = region.box,
					text = keptTexts.joinToString(" "),
					conf = confSum / keptTexts.size,
					isVertical = LineGrouper.isMostlyVertical(keptBoxes),
				),
			)
		}
		return toBlocks(LineGrouper.readingOrder(found, rtl) { it.box })
	}

	private fun toBlocks(groups: List<TextGroup>): List<TextBlock> = groups.mapIndexed { i, g ->
		TextBlock(
			id = "b$i",
			box = g.box.toRect(),
			text = g.text,
			confidence = g.conf.toFloat(),
			isVertical = g.isVertical,
		)
	}

	private fun Box.toRect() = Rect(x.roundToInt(), y.roundToInt(), right.roundToInt(), bottom.roundToInt())

	private suspend fun detectRegions(source: PageImageSource): List<Det> {
		val session = getBubble()
		val dets = ArrayList<Det>()
		for (tile in DetPostProcessor.tileRects(source.width, source.height)) {
			currentCoroutineContext().ensureActive()
			val tw = tile.w.toInt()
			val th = tile.h.toInt()
			val ss = sampleSizeFor(tw, th, BUBBLE_SIZE, BUBBLE_SIZE)
			val img = source.readPixels(tile.x.toInt(), tile.y.toInt(), tile.right.toInt(), tile.bottom.toInt(), ss)
			if (img.width == 0) continue
			val data = ImageTensors.toTensor(
				img, Box(0, 0, img.width, img.height), BUBBLE_SIZE, BUBBLE_SIZE, TensorNorm.BUBBLE,
			)
			for (det in runBubble(session, data, tw, th)) {
				if (det.label !in BUBBLE_LABELS || det.score < BUBBLE_MIN_SCORE) continue
				val box = Box(tile.x + det.box.x, tile.y + det.box.y, det.box.w, det.box.h)
					.clampTo(source.width, source.height)
				if (box.w > 0 && box.h > 0) {
					dets.add(Det(box, det.score, det.label))
				}
			}
		}
		return DetPostProcessor.mergeDetections(dets)
	}

	/** Text lines inside a bubble region (grown by 10% on each side). */
	private suspend fun detectLines(source: PageImageSource, region: Box): List<Box> {
		val r = Box(region.x - region.w * 0.1, region.y - region.h * 0.1, region.w * 1.2, region.h * 1.2)
			.clampTo(source.width, source.height)
		if (r.w < 1 || r.h < 1) return emptyList()
		return detectIn(source, r)
	}

	/** Text-line detection inside an already clamped crop [r]; boxes are returned in page coordinates. */
	private suspend fun detectIn(source: PageImageSource, r: Box): List<Box> {
		currentCoroutineContext().ensureActive()
		val session = getDet()
		val s = min(1.0, DET_MAX_SIDE / max(r.w, r.h))
		val w = max(32, ceil(r.w * s / 32).toInt() * 32)
		val h = max(32, ceil(r.h * s / 32).toInt() * 32)
		val ss = sampleSizeFor(r.w.toInt(), r.h.toInt(), w, h)
		val img = source.readPixels(r.x.toInt(), r.y.toInt(), r.right.toInt(), r.bottom.toInt(), ss)
		if (img.width == 0) return emptyList()
		val data = ImageTensors.toTensor(img, Box(0, 0, img.width, img.height), w, h, TensorNorm.DET)
		val out = runFloat(session, data, longArrayOf(1, 3, h.toLong(), w.toLong()))
		check(out.data.size >= w * h) { "Unexpected detector output size ${out.data.size}" }
		return DetPostProcessor.extractBoxes(out.data, w, h, r.w / w, r.h / h)
			.map { Box(it.x + r.x, it.y + r.y, it.w, it.h).clampTo(source.width, source.height) }
			.filter { it.w > 0 && it.h > 0 }
	}

	/**
	 * Recognizes a line from its detected box. In dense paragraphs that crop also catches slivers of the lines
	 * above and below; when the result is not confident, the box trimmed clear of its neighbours is tried too.
	 */
	private suspend fun recognizeLine(
		source: PageImageSource,
		box: Box,
		trimmed: Box,
		pack: OcrPack,
		recognizer: LoadedRec,
	): CtcResult {
		val r = recognizeBox(source, box, pack, recognizer)
		if (r.conf >= REC_MIN_CONF || trimmed == box) return r
		val t = recognizeBox(source, trimmed, pack, recognizer)
		return if (t.conf > r.conf) t else r
	}

	private suspend fun recognizeBox(
		source: PageImageSource,
		line: Box,
		pack: OcrPack,
		recognizer: LoadedRec,
	): CtcResult {
		currentCoroutineContext().ensureActive()
		if (line.w < 1 || line.h < 1) return CtcResult("", 0.0)
		// Vertical text (Japanese / Chinese columns): rotate the crop 90 degrees counter-clockwise, as PaddleOCR does.
		val rotate = pack.vertical && line.isVertical
		val ratio = if (rotate) line.h / line.w else line.w / line.h
		val w = min(REC_MAX_W, max(16, ceil(ratio * REC_H).toInt()))
		val img = source.readPixels(line.x.toInt(), line.y.toInt(), line.right.toInt(), line.bottom.toInt(), 1)
		if (img.width == 0) return CtcResult("", 0.0)
		val crop = Box(0, 0, img.width, img.height)
		val data = if (rotate) {
			ImageTensors.toTensorRot90(img, crop, w, REC_H, TensorNorm.REC)
		} else {
			ImageTensors.toTensor(img, crop, w, REC_H, TensorNorm.REC)
		}
		val out = runFloat(recognizer.session, data, longArrayOf(1, 3, REC_H.toLong(), w.toLong()))
		check(out.shape.size >= 3) { "Unexpected recognizer output shape" }
		val steps = out.shape[1].toInt()
		val classes = out.shape[2].toInt()
		return CtcDecoder.decode(out.data, steps, classes, recognizer.dict)
	}

	/** Largest power-of-two sample size that keeps the decoded image at least [targetW] x [targetH]. */
	private fun sampleSizeFor(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
		var ss = 1
		while (srcW / (ss * 2) >= targetW && srcH / (ss * 2) >= targetH) {
			ss *= 2
		}
		return ss
	}

	// ---- sessions ----

	private fun resolvePack(lang: String, mode: OcrMode): OcrPack {
		val candidates = store.candidatesFor(lang)
		if (candidates.isEmpty()) {
			throw OcrNotReadyException(lang)
		}
		return candidates.firstOrNull { store.isInstalled(it, mode) }
			?: throw OcrNotReadyException(candidates.first().id)
	}

	private val ortEnv: OrtEnvironment
		get() = OrtEnvironment.getEnvironment()

	private fun createSession(modelKey: String): OrtSession {
		val file = store.openVerified(modelKey)
		val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
		return OrtSession.SessionOptions().use { options ->
			options.setIntraOpNumThreads(threads)
			ortEnv.createSession(file.absolutePath, options)
		}
	}

	private fun getDet(): OrtSession = detSession ?: createSession(OcrModels.KEY_DET).also { detSession = it }

	private fun getBubble(): OrtSession =
		bubbleSession ?: createSession(OcrModels.KEY_BUBBLE).also { bubbleSession = it }

	/** Only the recognizer of the current language stays in memory; another language closes the previous one. */
	private fun getRec(pack: OcrPack): LoadedRec {
		rec?.let { current ->
			if (current.packId == pack.id) return current
			rec = null
			current.session.close()
		}
		val dictSpec = OcrModels.spec(pack.dictKey)
		val dict = OcrDict.parse(store.openVerified(pack.dictKey).readBytes(), dictSpec.kind)
		val loaded = LoadedRec(pack.id, createSession(pack.recKey), dict)
		rec = loaded
		return loaded
	}

	private fun closeIfRequested() {
		if (!releaseRequested) return
		releaseRequested = false
		closeQuietly(detSession)
		closeQuietly(bubbleSession)
		closeQuietly(rec?.session)
		detSession = null
		bubbleSession = null
		rec = null
	}

	private fun closeQuietly(session: OrtSession?) {
		try {
			session?.close()
		} catch (e: Exception) {
			e.printStackTraceDebug()
		}
	}

	// ---- ONNX Runtime calls ----

	private fun FloatArray.toDirectBuffer(): FloatBuffer {
		val buffer = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
		buffer.put(this)
		buffer.rewind()
		return buffer
	}

	private fun FloatBuffer.toFloatArray(): FloatArray {
		val array = FloatArray(remaining())
		get(array)
		return array
	}

	private fun LongBuffer.toLongArray(): LongArray {
		val array = LongArray(remaining())
		get(array)
		return array
	}

	/** Runs a single-input single-output float model (detector, recognizer) and copies the first output. */
	private fun runFloat(session: OrtSession, input: FloatArray, shape: LongArray): FloatOutput {
		val inputName = session.inputNames.first()
		return OnnxTensor.createTensor(ortEnv, input.toDirectBuffer(), shape).use { tensor ->
			session.run(mapOf(inputName to tensor)).use { result ->
				val output = result.get(0) as OnnxTensor
				FloatOutput(output.floatBuffer.toFloatArray(), output.info.shape)
			}
		}
	}

	/**
	 * Runs the bubble detector (RT-DETR) on a 640x640 tile of source size [tileW] x [tileH].
	 * Returns detections with boxes in tile pixels: x, y, w, h.
	 */
	private fun runBubble(session: OrtSession, input: FloatArray, tileW: Int, tileH: Int): List<Det> {
		val sizes = ByteBuffer.allocateDirect(2 * 8).order(ByteOrder.nativeOrder()).asLongBuffer()
		sizes.put(tileW.toLong())
		sizes.put(tileH.toLong())
		sizes.rewind()
		val imageShape = longArrayOf(1, 3, BUBBLE_SIZE.toLong(), BUBBLE_SIZE.toLong())
		return OnnxTensor.createTensor(ortEnv, input.toDirectBuffer(), imageShape).use { images ->
			OnnxTensor.createTensor(ortEnv, sizes, longArrayOf(1, 2)).use { targetSizes ->
				val inputs = mapOf("images" to images, "orig_target_sizes" to targetSizes)
				session.run(inputs).use { result ->
					var labels: LongArray? = null
					var boxes: FloatArray? = null
					var scores: FloatArray? = null
					for (entry in result) {
						val tensor = entry.value as? OnnxTensor ?: continue
						when (entry.key) {
							"labels" -> labels = tensor.longBuffer.toLongArray()
							"boxes" -> boxes = tensor.floatBuffer.toFloatArray()
							"scores" -> scores = tensor.floatBuffer.toFloatArray()
						}
					}
					check(labels != null && boxes != null && scores != null) { "Unexpected bubble detector outputs" }
					val count = min(scores.size, min(labels.size, boxes.size / 4))
					List(count) { i ->
						val x1 = boxes[i * 4]
						val y1 = boxes[i * 4 + 1]
						val x2 = boxes[i * 4 + 2]
						val y2 = boxes[i * 4 + 3]
						Det(
							box = Box(x1.toDouble(), y1.toDouble(), (x2 - x1).toDouble(), (y2 - y1).toDouble()),
							score = scores[i],
							label = labels[i].toInt(),
						)
					}
				}
			}
		}
	}

	private inline fun dummy(block: () -> Unit) {
		try {
			block()
		} catch (e: Exception) {
			e.printStackTraceDebug()
		}
	}

	private companion object {

		const val BUBBLE_SIZE = 640
		val BUBBLE_LABELS = setOf(1, 2) // 1 text_bubble, 2 text_free
		const val BUBBLE_MIN_SCORE = 0.3f
		const val DET_MAX_SIDE = 960.0

		/** Whole-page text check only while the downscale to DET_MAX_SIDE keeps at least half the resolution. */
		const val QUICK_CHECK_MAX_SIDE = 1920
		const val REC_H = 48

		/** Long note lines are ~40-60x wider than tall; squeezing them to a narrower crop garbles the recognizer. */
		const val REC_MAX_W = 2880
		const val REC_MIN_CONF = 0.8
	}
}
