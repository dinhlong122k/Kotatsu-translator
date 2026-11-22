package org.koitharu.kotatsu.translator.ocr

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Per-channel affine normalization of a raw 0..255 value: `v * scale[c] + bias[c]` (channel 0=R, 1=G, 2=B). */
class TensorNorm(val scale: DoubleArray, val bias: DoubleArray) {

	companion object {

		private val MEAN = doubleArrayOf(0.485, 0.456, 0.406)
		private val STD = doubleArrayOf(0.229, 0.224, 0.225)

		/** (v/255 - mean) / std */
		val DET = TensorNorm(
			DoubleArray(3) { 1.0 / (255.0 * STD[it]) },
			DoubleArray(3) { -MEAN[it] / STD[it] },
		)

		/** (v/255 - 0.5) / 0.5 */
		val REC = TensorNorm(DoubleArray(3) { 2.0 / 255.0 }, DoubleArray(3) { -1.0 })

		/** v/255 */
		val BUBBLE = TensorNorm(DoubleArray(3) { 1.0 / 255.0 }, DoubleArray(3) { 0.0 })
	}
}

/**
 * Crop + bilinear resize into CHW float tensors. Port of `src/ocr/image.ts`.
 * Sampling is centre-aligned and clamped to the crop edges; alpha is ignored.
 */
object ImageTensors {

	private fun clamp(v: Double, lo: Double, hi: Double) = min(hi, max(lo, v))

	private fun channel(pixel: Int, c: Int): Int = (pixel shr (16 - 8 * c)) and 0xFF

	/** Crops [r] (image pixel coordinates) out of [img], bilinear-resizes it to outW x outH and returns CHW floats. */
	fun toTensor(img: PixelImage, r: Box, outW: Int, outH: Int, norm: TensorNorm): FloatArray {
		val out = FloatArray(3 * outW * outH)
		if (img.width <= 0 || img.height <= 0) return out
		val plane = outW * outH
		val x0 = max(0.0, floor(r.x))
		val y0 = max(0.0, floor(r.y))
		val x1 = (min(img.width.toDouble(), ceil(r.x + r.w)) - 1)
		val y1 = (min(img.height.toDouble(), ceil(r.y + r.h)) - 1)
		val xMax = x1.toInt()

		val xa = IntArray(outW)
		val xb = IntArray(outW)
		val fx = DoubleArray(outW)
		for (ox in 0 until outW) {
			val sx = clamp(r.x + ((ox + 0.5) * r.w) / outW - 0.5, x0, x1)
			val a = floor(sx).toInt()
			xa[ox] = a
			xb[ox] = min(xMax, a + 1)
			fx[ox] = sx - a
		}
		val yMax = y1.toInt()
		val pixels = img.pixels
		val stride = img.width
		for (oy in 0 until outH) {
			val sy = clamp(r.y + ((oy + 0.5) * r.h) / outH - 0.5, y0, y1)
			val ya = floor(sy).toInt()
			val rowA = ya * stride
			val rowB = min(yMax, ya + 1) * stride
			val fy = sy - ya
			val outRow = oy * outW
			for (c in 0 until 3) {
				val base = c * plane + outRow
				val scale = norm.scale[c]
				val bias = norm.bias[c]
				for (ox in 0 until outW) {
					val f = fx[ox]
					val pa = xa[ox]
					val pb = xb[ox]
					val top = channel(pixels[rowA + pa], c) * (1 - f) + channel(pixels[rowA + pb], c) * f
					val bot = channel(pixels[rowB + pa], c) * (1 - f) + channel(pixels[rowB + pb], c) * f
					out[base + ox] = ((top * (1 - fy) + bot * fy) * scale + bias).toFloat()
				}
			}
		}
		return out
	}

	/**
	 * Same as [toTensor], but the crop is first rotated 90 degrees counter-clockwise (like `np.rot90`), so a column
	 * of text reading top-to-bottom becomes a row reading left-to-right. The rotated crop is r.h wide and r.w tall.
	 */
	fun toTensorRot90(img: PixelImage, r: Box, outW: Int, outH: Int, norm: TensorNorm): FloatArray {
		val out = FloatArray(3 * outW * outH)
		if (img.width <= 0 || img.height <= 0) return out
		val plane = outW * outH
		val x0 = max(0.0, floor(r.x))
		val y0 = max(0.0, floor(r.y))
		val x1 = (min(img.width.toDouble(), ceil(r.x + r.w)) - 1)
		val y1 = (min(img.height.toDouble(), ceil(r.y + r.h)) - 1)
		val xMax = x1.toInt()
		val yMax = y1.toInt()
		// Rotated column ox maps to a source row (top to bottom); rotated row oy maps to a source column (right to left).
		val ya = IntArray(outW)
		val yb = IntArray(outW)
		val fy = DoubleArray(outW)
		for (ox in 0 until outW) {
			val sy = clamp(r.y + ((ox + 0.5) * r.h) / outW - 0.5, y0, y1)
			val a = floor(sy).toInt()
			ya[ox] = a
			yb[ox] = min(yMax, a + 1)
			fy[ox] = sy - a
		}
		val pixels = img.pixels
		val stride = img.width
		for (oy in 0 until outH) {
			val sx = clamp(r.x + r.w - ((oy + 0.5) * r.w) / outH - 0.5, x0, x1)
			val xa = floor(sx).toInt()
			val xb = min(xMax, xa + 1)
			val fx = sx - xa
			for (c in 0 until 3) {
				val base = c * plane + oy * outW
				val scale = norm.scale[c]
				val bias = norm.bias[c]
				for (ox in 0 until outW) {
					val ra = ya[ox] * stride
					val rb = yb[ox] * stride
					val top = channel(pixels[ra + xa], c) * (1 - fx) + channel(pixels[ra + xb], c) * fx
					val bot = channel(pixels[rb + xa], c) * (1 - fx) + channel(pixels[rb + xb], c) * fx
					out[base + ox] = ((top * (1 - fy[ox]) + bot * fy[ox]) * scale + bias).toFloat()
				}
			}
		}
		return out
	}
}
