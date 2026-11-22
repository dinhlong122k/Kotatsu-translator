package org.koitharu.kotatsu.translator.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.core.net.toFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.core.util.ext.ensureRamAtLeast
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.isZipUri
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipFile

/**
 * A page image opened for OCR. Never keeps the whole page as a bitmap when it can be avoided: the image is
 * read through [BitmapRegionDecoder] strip by strip ([readPixels]). Supports `file://` and
 * `file+zip://path#entry` (and legacy `cbz:`/`zip:`) uris, other schemes are read through the content resolver.
 *
 * Formats the region decoder cannot open (e.g. AVIF on old devices) fall back to decoding the whole image with
 * [BitmapDecoderCompat], guarded by a free RAM check like `PageLoader.convertBimap`.
 *
 * Not thread-safe by contract: used by one OCR run at a time.
 */
class PageImageSource private constructor(
	val width: Int,
	val height: Int,
	private val regionDecoder: BitmapRegionDecoder?,
	private var fullBitmap: Bitmap?,
	@Suppress("unused") private val keepAlive: Any?,
) : Closeable {

	/**
	 * Reads the region [left, right) x [top, bottom) of the image (original pixel coordinates, clamped to the
	 * image) downsampled by [sampleSize] (power of two). The returned image covers exactly that region.
	 */
	@Blocking
	fun readPixels(left: Int, top: Int, right: Int, bottom: Int, sampleSize: Int = 1): PixelImage {
		val l = left.coerceIn(0, width)
		val t = top.coerceIn(0, height)
		val r = right.coerceIn(l, width)
		val b = bottom.coerceIn(t, height)
		if (r - l < 1 || b - t < 1) {
			return PixelImage(IntArray(0), 0, 0)
		}
		val ss = sampleSize.coerceAtLeast(1)
		val full = fullBitmap
		return if (regionDecoder != null) {
			val options = BitmapFactory.Options().apply {
				inSampleSize = ss
				inPreferredConfig = Bitmap.Config.ARGB_8888
			}
			val bitmap = regionDecoder.decodeRegion(Rect(l, t, r, b), options)
				?: throw IOException("Cannot decode image region")
			try {
				bitmap.toPixels()
			} finally {
				bitmap.recycle()
			}
		} else if (full != null) {
			if (ss == 1) {
				val pixels = IntArray((r - l) * (b - t))
				full.getPixels(pixels, 0, r - l, l, t, r - l, b - t)
				PixelImage(pixels, r - l, b - t)
			} else {
				val crop = Bitmap.createBitmap(full, l, t, r - l, b - t)
				val scaled = Bitmap.createScaledBitmap(
					crop,
					((r - l) / ss).coerceAtLeast(1),
					((b - t) / ss).coerceAtLeast(1),
					true,
				)
				try {
					scaled.toPixels()
				} finally {
					if (scaled !== crop) {
						scaled.recycle()
					}
					if (crop !== full) {
						crop.recycle()
					}
				}
			}
		} else {
			throw IllegalStateException("PageImageSource is closed")
		}
	}

	override fun close() {
		regionDecoder?.recycle()
		fullBitmap?.recycle()
		fullBitmap = null
	}

	private fun Bitmap.toPixels(): PixelImage {
		val pixels = IntArray(this.width * this.height)
		getPixels(pixels, 0, this.width, 0, 0, this.width, this.height)
		return PixelImage(pixels, this.width, this.height)
	}

	companion object {

		suspend fun open(context: Context, uri: Uri): PageImageSource = runInterruptible(Dispatchers.IO) {
			openBlocking(context, uri)
		}

		@Blocking
		private fun openBlocking(context: Context, uri: Uri): PageImageSource {
			if (uri.isZipUri()) {
				ZipFile(uri.schemeSpecificPart).use { zip ->
					val entry = zip.getEntry(uri.fragment) ?: throw FileNotFoundException(uri.toString())
					context.ensureRamAtLeast(entry.size * 2)
					val bytes = zip.getInputStream(entry).use { it.readBytes() }
					return fromBytes(context, bytes, MimeTypes.getMimeTypeFromExtension(entry.name))
				}
			}
			if (uri.isFileUri()) {
				val file = uri.toFile()
				val decoder = openFileRegionDecoder(file)
				if (decoder != null) {
					return PageImageSource(decoder.width, decoder.height, decoder, null, null)
				}
				context.ensureRamAtLeast(file.length() * 2)
				val bitmap = BitmapDecoderCompat.decode(file).toSoftware()
				return PageImageSource(bitmap.width, bitmap.height, null, bitmap, null)
			}
			val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
				?: throw FileNotFoundException(uri.toString())
			return fromBytes(context, bytes, null)
		}

		private fun fromBytes(context: Context, bytes: ByteArray, mimeType: MimeType?): PageImageSource {
			val decoder = try {
				BitmapDecoderCompat.createRegionDecoder(ByteArrayInputStream(bytes))
			} catch (e: Exception) {
				e.printStackTraceDebug()
				null
			}
			if (decoder != null && decoder.width > 0 && decoder.height > 0) {
				// the bytes are referenced to be sure the data stays alive as long as the decoder does
				return PageImageSource(decoder.width, decoder.height, decoder, null, bytes)
			}
			decoder?.recycle()
			context.ensureRamAtLeast(bytes.size * 2L)
			val bitmap = BitmapDecoderCompat.decode(ByteArrayInputStream(bytes), mimeType).toSoftware()
			return PageImageSource(bitmap.width, bitmap.height, null, bitmap, null)
		}

		private fun openFileRegionDecoder(file: File): BitmapRegionDecoder? = try {
			val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				BitmapRegionDecoder.newInstance(file.absolutePath)
			} else {
				@Suppress("DEPRECATION")
				BitmapRegionDecoder.newInstance(file.absolutePath, false)
			}
			if (decoder != null && (decoder.width <= 0 || decoder.height <= 0)) {
				decoder.recycle()
				null
			} else {
				decoder
			}
		} catch (e: IOException) {
			e.printStackTraceDebug()
			null
		} catch (e: IllegalArgumentException) {
			e.printStackTraceDebug()
			null
		}

		/** `ImageDecoder` may return a HARDWARE bitmap whose pixels cannot be read. */
		private fun Bitmap.toSoftware(): Bitmap {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && config == Bitmap.Config.HARDWARE) {
				val copy = copy(Bitmap.Config.ARGB_8888, false) ?: throw IOException("Cannot read image pixels")
				recycle()
				return copy
			}
			return this
		}
	}
}
