package org.koitharu.kotatsu.translator.ocr

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.translator.TranslatorHttpClient
import org.koitharu.kotatsu.translator.domain.model.ModelIntegrityException
import org.koitharu.kotatsu.translator.domain.model.OcrMode
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Progress of [OcrModelStore.download]. [loadedBytes] and [totalBytes] cover all files of the request,
 * [modelKey] is the file being downloaded right now.
 */
data class DownloadProgress(
	val modelKey: String,
	val loadedBytes: Long,
	val totalBytes: Long,
) {

	val fraction: Float
		get() = if (totalBytes > 0L) (loadedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f
}

/**
 * Model files in `filesDir/ocr_models`. Port of `modelStore.ts`: files are downloaded to a temporary file,
 * the SHA-256 is checked, then the file is renamed. A corrupted file is deleted.
 */
@Singleton
class OcrModelStore @Inject constructor(
	@ApplicationContext private val context: Context,
	@TranslatorHttpClient private val baseOkHttp: OkHttpClient,
) {

	val packs: List<OcrPack>
		get() = OcrModels.packs

	/** Download client: the shared translator client has no read timeout (streaming), here one is needed. */
	private val okHttp: OkHttpClient by lazy {
		baseOkHttp.newBuilder()
			.connectTimeout(30, TimeUnit.SECONDS)
			.readTimeout(30, TimeUnit.SECONDS)
			.build()
	}

	/** key -> (length, lastModified) of files whose SHA-256 was already checked in this process */
	private val verified = ConcurrentHashMap<String, Pair<Long, Long>>()

	private val dir: File
		get() = File(context.filesDir, DIR_NAME)

	fun packFor(lang: String): OcrPack? = OcrModels.packFor(lang)

	/** Packs able to read [lang], best first. */
	fun candidatesFor(lang: String): List<OcrPack> = OcrModels.packCandidates(lang)

	fun fileOf(modelKey: String): File {
		val spec = OcrModels.spec(modelKey)
		return File(dir, "${spec.key}.${spec.kind.extension}")
	}

	/**
	 * Cheap check (existence and size, no hashing) that everything [mode] needs for [pack] is on disk:
	 * the shared detector, the bubble detector for [OcrMode.ACCURATE], the recognizer and the dictionary.
	 */
	fun isInstalled(pack: OcrPack, mode: OcrMode): Boolean = requiredKeys(pack, mode).all { hasFile(it) }

	/** Rec and dictionary of [pack] only. */
	fun isPackInstalled(pack: OcrPack): Boolean = hasFile(pack.recKey) && hasFile(pack.dictKey)

	fun isBubbleInstalled(): Boolean = hasFile(OcrModels.KEY_BUBBLE)

	/**
	 * Downloads what is missing for [pack] in [mode]: detector (shared), bubble detector (ACCURATE only),
	 * recognizer, dictionary. Files that are already valid are skipped. Cancel the collector to cancel the download.
	 * Throws [ModelIntegrityException] on a SHA-256 mismatch, [IOException] on network errors.
	 */
	fun download(pack: OcrPack, mode: OcrMode): Flow<DownloadProgress> = channelFlow {
		val keys = requiredKeys(pack, mode)
		val total = keys.sumOf { OcrModels.spec(it).bytes }
		var done = 0L
		for (key in keys) {
			val spec = OcrModels.spec(key)
			val target = fileOf(key)
			if (withContext(Dispatchers.IO) { hasFile(key) && verifyHash(key) }) {
				done += spec.bytes
				send(DownloadProgress(key, done, total))
				continue
			}
			val base = done
			send(DownloadProgress(key, base, total))
			downloadFile(spec, target) { loaded ->
				trySend(DownloadProgress(key, base + loaded, total))
			}
			done += spec.bytes
			send(DownloadProgress(key, done, total))
		}
	}.conflate()

	/**
	 * Deletes the recognizer and dictionary of [pack]. When no pack is left, the shared detectors are deleted too.
	 * Call [OcrEngine.release] afterwards if the pack could be loaded.
	 */
	fun delete(pack: OcrPack) {
		deleteKey(pack.recKey)
		deleteKey(pack.dictKey)
		if (OcrModels.packs.none { isPackInstalled(it) }) {
			deleteKey(OcrModels.KEY_DET)
			deleteKey(OcrModels.KEY_BUBBLE)
		}
		dir.listFiles()?.forEach { if (it.name.endsWith(TEMP_SUFFIX)) it.delete() }
	}

	/** Size on disk of the recognizer and dictionary of [pack] (shared detectors are not counted). */
	fun sizeOnDisk(pack: OcrPack): Long = listOf(pack.recKey, pack.dictKey).sumOf { key ->
		fileOf(key).let { if (it.isFile) it.length() else 0L }
	}

	/** Size of the shared detectors (text-line and bubble) on disk. */
	fun sharedSizeOnDisk(): Long = listOf(OcrModels.KEY_DET, OcrModels.KEY_BUBBLE).sumOf { key ->
		fileOf(key).let { if (it.isFile) it.length() else 0L }
	}

	/**
	 * The file of a model that is going to be opened by the engine. The SHA-256 is verified once per process;
	 * a corrupted file is deleted and [ModelIntegrityException] is thrown.
	 */
	@androidx.annotation.WorkerThread
	internal fun openVerified(modelKey: String): File {
		if (!hasFile(modelKey)) {
			throw ModelIntegrityException(modelKey)
		}
		if (!verifyHash(modelKey)) {
			throw ModelIntegrityException(modelKey)
		}
		return fileOf(modelKey)
	}

	private fun requiredKeys(pack: OcrPack, mode: OcrMode): List<String> = buildList {
		add(OcrModels.KEY_DET)
		if (mode == OcrMode.ACCURATE) {
			add(OcrModels.KEY_BUBBLE)
		}
		add(pack.recKey)
		add(pack.dictKey)
	}

	private fun hasFile(key: String): Boolean {
		val file = fileOf(key)
		return file.isFile && file.length() == OcrModels.spec(key).bytes
	}

	/** Computes the SHA-256 (cached per file state); deletes the file if it does not match. */
	private fun verifyHash(key: String): Boolean {
		val file = fileOf(key)
		val state = file.length() to file.lastModified()
		if (verified[key] == state) {
			return true
		}
		val actual = try {
			sha256(file)
		} catch (e: IOException) {
			e.printStackTraceDebug()
			null
		}
		return if (actual == OcrModels.spec(key).sha256) {
			verified[key] = state
			true
		} else {
			deleteKey(key)
			false
		}
	}

	private fun deleteKey(key: String) {
		verified.remove(key)
		fileOf(key).delete()
	}

	private suspend fun downloadFile(spec: OcrModelSpec, target: File, onProgress: (Long) -> Unit) {
		withContext(Dispatchers.IO) {
			val dir = target.parentFile ?: throw IOException("No parent directory")
			if (!dir.isDirectory && !dir.mkdirs()) {
				throw IOException("Cannot create $dir")
			}
			val temp = File(dir, target.name + TEMP_SUFFIX)
			val call = okHttp.newCall(Request.Builder().url(spec.url).build())
			try {
				coroutineScope {
					// A blocking read cannot be interrupted by cancellation, so the call is cancelled explicitly
					val watcher = launch(Dispatchers.IO) {
						try {
							awaitCancellation()
						} finally {
							call.cancel()
						}
					}
					try {
						val digest = MessageDigest.getInstance("SHA-256")
						var loaded = 0L
						call.execute().use { response ->
							if (!response.isSuccessful) {
								throw IOException("model ${spec.key}: HTTP ${response.code}")
							}
							val body = response.body ?: throw IOException("model ${spec.key}: empty response")
							body.byteStream().use { input ->
								temp.outputStream().use { output ->
									val buffer = ByteArray(BUFFER_SIZE)
									var lastReport = 0L
									while (true) {
										val read = input.read(buffer)
										if (read < 0) break
										output.write(buffer, 0, read)
										digest.update(buffer, 0, read)
										loaded += read
										currentCoroutineContext().ensureActive()
										val now = System.nanoTime()
										if (now - lastReport > REPORT_INTERVAL_NS) {
											lastReport = now
											onProgress(loaded)
										}
									}
								}
							}
						}
						val actual = digest.digest().toHex()
						if (loaded != spec.bytes || actual != spec.sha256) {
							throw ModelIntegrityException(spec.key)
						}
						if (target.exists() && !target.delete()) {
							throw IOException("Cannot replace ${target.name}")
						}
						if (!temp.renameTo(target)) {
							throw IOException("Cannot rename ${temp.name}")
						}
						verified[spec.key] = target.length() to target.lastModified()
					} finally {
						watcher.cancel()
					}
				}
			} finally {
				if (temp.exists()) {
					temp.delete()
				}
			}
		}
	}

	private fun sha256(file: File): String {
		val digest = MessageDigest.getInstance("SHA-256")
		file.inputStream().use { input ->
			val buffer = ByteArray(BUFFER_SIZE)
			while (true) {
				val read = input.read(buffer)
				if (read < 0) break
				digest.update(buffer, 0, read)
			}
		}
		return digest.digest().toHex()
	}

	private fun ByteArray.toHex(): String {
		val chars = CharArray(size * 2)
		for (i in indices) {
			val v = this[i].toInt() and 0xFF
			chars[i * 2] = HEX[v ushr 4]
			chars[i * 2 + 1] = HEX[v and 0x0F]
		}
		return String(chars)
	}

	private companion object {

		const val DIR_NAME = "ocr_models"
		const val TEMP_SUFFIX = ".part"
		const val BUFFER_SIZE = 64 * 1024
		const val REPORT_INTERVAL_NS = 100_000_000L
		val HEX = "0123456789abcdef".toCharArray()
	}
}
