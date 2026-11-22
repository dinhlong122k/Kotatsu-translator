package org.koitharu.kotatsu.translator.ocr

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Detection result of the bubble detector. */
data class Det(val box: Box, val score: Float, val label: Int)

/** Line boxes found inside one detection tile (page coordinates). */
class TileLines(val tile: Box, val boxes: List<Box>)

/**
 * Pure post-processing of the detectors. Port of `src/ocr/post.ts` (tileRects, iou, mergeDetections,
 * extractBoxes, detTiles, mergeTileLines, separateLines). This is not the full DB algorithm: boxes are
 * axis-aligned bounding boxes of 4-connected components of `prob > 0.3`.
 */
object DetPostProcessor {

	private const val BIN_THRESHOLD = 0.3
	private const val MIN_PIXELS = 30
	private const val MIN_SIDE = 6
	private const val UNCLIP_RATIO = 1.6

	private const val DET_TILE_SIDE = 960
	private const val DET_TILE_OVERLAP = 128
	private const val EDGE_TOL = 2

	/** JS Math.round: ties go towards +infinity. */
	private fun jsRound(v: Double): Double = floor(v + 0.5)

	/** Tiles for the bubble detector (640x640 input after resize). */
	fun tileRects(width: Int, height: Int): List<Box> {
		if (height <= width) return listOf(Box(0, 0, width, height))
		val step = jsRound(width * 0.85).toInt()
		val out = ArrayList<Box>()
		var y = 0
		while (y + width < height) {
			out.add(Box(0, y, width, width))
			y += step
		}
		out.add(Box(0, height - width, width, width))
		return out
	}

	fun iou(a: Box, b: Box): Double {
		val iw = min(a.right, b.right) - max(a.x, b.x)
		val ih = min(a.bottom, b.bottom) - max(a.y, b.y)
		if (iw <= 0 || ih <= 0) return 0.0
		val inter = iw * ih
		return inter / (a.w * a.h + b.w * b.h - inter)
	}

	fun union(a: Box, b: Box): Box {
		val x = min(a.x, b.x)
		val y = min(a.y, b.y)
		return Box(x, y, max(a.right, b.right) - x, max(a.bottom, b.bottom) - y)
	}

	/** Repeatedly unions any two detections with IoU above [thr]; the union keeps score and label of the better one. */
	fun mergeDetections(dets: List<Det>, thr: Double = 0.5): List<Det> {
		val out = ArrayList(dets)
		var changed = true
		while (changed) {
			changed = false
			outer@ for (i in out.indices) {
				for (j in i + 1 until out.size) {
					if (iou(out[i].box, out[j].box) > thr) {
						val hi = if (out[j].score > out[i].score) out[j] else out[i]
						out[i] = Det(union(out[i].box, out[j].box), hi.score, hi.label)
						out.removeAt(j)
						changed = true
						break@outer
					}
				}
			}
		}
		return out
	}

	/**
	 * Text boxes from a probability map [prob] of size [w]x[h] (row-major). Box coordinates are multiplied by
	 * [scaleX]/[scaleY]. Components with fewer than 30 pixels or a side below 6 are dropped; boxes are grown by
	 * `area * 1.6 / perimeter`. Result is sorted by y then x.
	 */
	fun extractBoxes(prob: FloatArray, w: Int, h: Int, scaleX: Double, scaleY: Double): List<Box> {
		val total = w * h
		val bin = BooleanArray(total) { prob[it].toDouble() > BIN_THRESHOLD }
		val seen = BooleanArray(total)
		val stack = IntArray(total)
		val boxes = ArrayList<Box>()
		for (s in 0 until total) {
			if (!bin[s] || seen[s]) continue
			var minX = w
			var minY = h
			var maxX = 0
			var maxY = 0
			var n = 0
			var sp = 0
			stack[sp++] = s
			seen[s] = true
			while (sp > 0) {
				val p = stack[--sp]
				val x = p % w
				val y = p / w
				n++
				if (x < minX) minX = x
				if (x > maxX) maxX = x
				if (y < minY) minY = y
				if (y > maxY) maxY = y
				if (x > 0) {
					val q = p - 1
					if (!seen[q] && bin[q]) {
						seen[q] = true
						stack[sp++] = q
					}
				}
				if (x < w - 1) {
					val q = p + 1
					if (!seen[q] && bin[q]) {
						seen[q] = true
						stack[sp++] = q
					}
				}
				if (p - w >= 0) {
					val q = p - w
					if (!seen[q] && bin[q]) {
						seen[q] = true
						stack[sp++] = q
					}
				}
				if (p + w < total) {
					val q = p + w
					if (!seen[q] && bin[q]) {
						seen[q] = true
						stack[sp++] = q
					}
				}
			}
			val bw = maxX - minX + 1
			val bh = maxY - minY + 1
			if (n < MIN_PIXELS || bw < MIN_SIDE || bh < MIN_SIDE) continue
			val d = (bw.toDouble() * bh * UNCLIP_RATIO) / (2.0 * (bw + bh))
			val x0 = max(0.0, (minX - d) * scaleX)
			val y0 = max(0.0, (minY - d) * scaleY)
			val x1 = min(w * scaleX, (maxX + d) * scaleX)
			val y1 = min(h * scaleY, (maxY + d) * scaleY)
			boxes.add(Box(jsRound(x0), jsRound(y0), jsRound(x1 - x0), jsRound(y1 - y0)))
		}
		return boxes.sortedWith(compareBy<Box>({ it.y }, { it.x }))
	}

	/**
	 * Tiles (source pixels) for running the text-line detector over a whole page. Wide pages are scaled so
	 * width = 960; tiles span the full width and ~960 output px of height, overlapping by at least 128 output px.
	 * The last tile is bottom-aligned. Never upscales.
	 */
	fun detTiles(width: Int, height: Int): List<Box> {
		val scale = if (width > DET_TILE_SIDE) DET_TILE_SIDE.toDouble() / width else 1.0
		val tileH = ceil(DET_TILE_SIDE / scale).toInt()
		if (height <= tileH) return listOf(Box(0, 0, width, height))
		val step = floor((DET_TILE_SIDE - DET_TILE_OVERLAP) / scale).toInt()
		val out = ArrayList<Box>()
		var y = 0
		while (y + tileH < height) {
			out.add(Box(0, y, width, tileH))
			y += step
		}
		out.add(Box(0, height - tileH, width, tileH))
		return out
	}

	private fun isSame(a: Box, b: Box): Boolean {
		if (iou(a, b) > 0.5) return true
		val iw = min(a.right, b.right) - max(a.x, b.x)
		val ih = min(a.bottom, b.bottom) - max(a.y, b.y)
		if (iw <= 0 || ih <= 0) return false
		return (iw * ih) / min(a.w * a.h, b.w * b.h) >= 0.8
	}

	/**
	 * Flattens per-tile line boxes (page coordinates). A box touching an interior tile cut is dropped (the
	 * neighbouring tile sees the whole line); remaining duplicates (IoU > 0.5 or at least 80% contained) are unioned.
	 */
	fun mergeTileLines(tiles: List<TileLines>, pageHeight: Int): List<Box> {
		val kept = ArrayList<Box>()
		for (t in tiles) {
			val tile = t.tile
			val interiorTop = tile.y > 0
			val interiorBottom = tile.bottom < pageHeight
			for (b in t.boxes) {
				if (interiorTop && b.y <= tile.y + EDGE_TOL) continue
				if (interiorBottom && b.bottom >= tile.bottom - EDGE_TOL) continue
				kept.add(b)
			}
		}
		var changed = true
		while (changed) {
			changed = false
			outer@ for (i in kept.indices) {
				for (j in i + 1 until kept.size) {
					if (isSame(kept[i], kept[j])) {
						kept[i] = union(kept[i], kept[j])
						kept.removeAt(j)
						changed = true
						break@outer
					}
				}
			}
		}
		return kept
	}

	/**
	 * Dense paragraphs (translator notes): the detector's expanded line boxes overlap the lines above and below.
	 * Splits each such overlap at its midpoint. Only horizontal lines that share columns and overlap by less
	 * than half the shorter height are trimmed; other boxes are returned unchanged (same instance).
	 */
	fun separateLines(boxes: List<Box>): List<Box> {
		val top = DoubleArray(boxes.size) { boxes[it].y }
		val bottom = DoubleArray(boxes.size) { boxes[it].bottom }
		for (i in boxes.indices) {
			for (j in boxes.indices) {
				val a = boxes[i]
				val b = boxes[j]
				if (i == j || a.isVertical || b.isVertical || a.y >= b.y) continue
				val ov = a.bottom - b.y
				if (ov <= 0 || ov >= 0.5 * min(a.h, b.h)) continue
				val ox = min(a.right, b.right) - max(a.x, b.x)
				if (ox <= 0) continue
				val cut = jsRound((a.bottom + b.y) / 2)
				bottom[i] = min(bottom[i], cut)
				top[j] = max(top[j], cut)
			}
		}
		return boxes.mapIndexed { i, b ->
			if (top[i] == b.y && bottom[i] == b.bottom) b else Box(b.x, top[i], b.w, bottom[i] - top[i])
		}
	}
}
