package org.koitharu.kotatsu.translator.ocr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class ImageTensorsTest {

	private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

	/** v / 10 + c */
	private val testNorm = TensorNorm(DoubleArray(3) { 0.1 }, DoubleArray(3) { it.toDouble() })

	@Test
	fun resizeTwoByTwoToFourByFourKeepsChwOrderAndAppliesNorm() {
		// R(x, y) = 80x + 160y ; G = 50 ; B = 7
		val pixels = IntArray(4)
		for (y in 0 until 2) for (x in 0 until 2) pixels[y * 2 + x] = argb(80 * x + 160 * y, 50, 7)
		val img = PixelImage(pixels, 2, 2)
		val out = ImageTensors.toTensor(img, Box(0, 0, 2, 2), 4, 4, testNorm)
		assertEquals(3 * 16, out.size)
		// sample weights at o = 0..3 are 0, .25, .75, 1 (centre-aligned, edge-clamped)
		val fx = doubleArrayOf(0.0, 20.0, 60.0, 80.0)
		val fy = doubleArrayOf(0.0, 40.0, 120.0, 160.0)
		for (oy in 0 until 4) for (ox in 0 until 4) {
			assertEquals((fx[ox] + fy[oy]) / 10, out[oy * 4 + ox].toDouble(), 1e-4)
			assertEquals(6.0, out[16 + oy * 4 + ox].toDouble(), 1e-4)
			assertEquals(2.7, out[32 + oy * 4 + ox].toDouble(), 1e-4)
		}
		assertEquals(14.0, out[2 * 4 + 1].toDouble(), 1e-4)
		assertEquals(24.0, out[15].toDouble(), 1e-4)
	}

	@Test
	fun samplesOnlyInsideTheRect() {
		val pixels = intArrayOf(10, 20, 30, 40).map { argb(it, it, it) }.toIntArray()
		val out = ImageTensors.toTensor(
			PixelImage(pixels, 4, 1), Box(2, 0, 1, 1), 2, 2, TensorNorm(DoubleArray(3) { 1.0 }, DoubleArray(3)),
		)
		assertArrayEquals(floatArrayOf(30f, 30f, 30f, 30f), out.copyOfRange(0, 4), 0f)
	}

	@Test
	fun normalizationConstants() {
		val img = PixelImage(intArrayOf(argb(255, 0, 128)), 1, 1)
		val rec = ImageTensors.toTensor(img, Box(0, 0, 1, 1), 1, 1, TensorNorm.REC)
		assertEquals(1.0, rec[0].toDouble(), 1e-6)
		assertEquals(-1.0, rec[1].toDouble(), 1e-6)
		assertEquals((128 / 255.0 - 0.5) / 0.5, rec[2].toDouble(), 1e-6)
		val det = ImageTensors.toTensor(img, Box(0, 0, 1, 1), 1, 1, TensorNorm.DET)
		assertEquals((1.0 - 0.485) / 0.229, det[0].toDouble(), 1e-5)
		assertEquals((0.0 - 0.456) / 0.224, det[1].toDouble(), 1e-5)
		assertEquals((128 / 255.0 - 0.406) / 0.225, det[2].toDouble(), 1e-5)
		val bubble = ImageTensors.toTensor(img, Box(0, 0, 1, 1), 1, 1, TensorNorm.BUBBLE)
		assertEquals(1.0, bubble[0].toDouble(), 1e-6)
		assertEquals(128 / 255.0, bubble[2].toDouble(), 1e-6)
	}

	/** Straightforward reference implementation (one pixel at a time). */
	private fun reference(img: PixelImage, r: Box, outW: Int, outH: Int, norm: TensorNorm): FloatArray {
		val out = FloatArray(3 * outW * outH)
		val plane = outW * outH
		val x0 = max(0.0, floor(r.x))
		val y0 = max(0.0, floor(r.y))
		val x1 = min(img.width.toDouble(), ceil(r.x + r.w)) - 1
		val y1 = min(img.height.toDouble(), ceil(r.y + r.h)) - 1
		fun clamp(v: Double, lo: Double, hi: Double) = min(hi, max(lo, v))
		for (oy in 0 until outH) {
			val sy = clamp(r.y + ((oy + 0.5) * r.h) / outH - 0.5, y0, y1)
			val ya = floor(sy).toInt()
			val yb = min(y1.toInt(), ya + 1)
			val fy = sy - ya
			for (ox in 0 until outW) {
				val sx = clamp(r.x + ((ox + 0.5) * r.w) / outW - 0.5, x0, x1)
				val xa = floor(sx).toInt()
				val xb = min(x1.toInt(), xa + 1)
				val fx = sx - xa
				for (c in 0 until 3) {
					fun p(yy: Int, xx: Int) = ((img.pixels[yy * img.width + xx] shr (16 - 8 * c)) and 0xFF).toDouble()
					val top = p(ya, xa) * (1 - fx) + p(ya, xb) * fx
					val bot = p(yb, xa) * (1 - fx) + p(yb, xb) * fx
					out[c * plane + oy * outW + ox] = ((top * (1 - fy) + bot * fy) * norm.scale[c] + norm.bias[c]).toFloat()
				}
			}
		}
		return out
	}

	@Test
	fun matchesReferenceOnRandomImagesRectsAndSizes() {
		var seed = 12345L
		fun rnd(): Double {
			seed = (seed * 1664525L + 1013904223L) and 0xFFFFFFFFL
			return seed.toDouble() / 4294967296.0
		}
		val norm = TensorNorm(DoubleArray(3) { 1.0 / (255 * 0.229) }, DoubleArray(3) { -0.45 * it / 0.229 })
		repeat(40) {
			val width = 1 + floor(rnd() * 40).toInt()
			val height = 1 + floor(rnd() * 40).toInt()
			val pixels = IntArray(width * height) { floor(rnd() * 16777216).toInt() or (0xFF shl 24) }
			val img = PixelImage(pixels, width, height)
			// rects include fractional origins and ones that spill outside the image
			val r = Box(
				rnd() * width * 1.2 - width * 0.1,
				rnd() * height * 1.2 - height * 0.1,
				// wide enough to always reach into the image (callers clamp their boxes to the page)
				0.1 * width + 1 + rnd() * width,
				0.1 * height + 1 + rnd() * height,
			)
			val outW = 1 + floor(rnd() * 50).toInt()
			val outH = 1 + floor(rnd() * 50).toInt()
			assertArrayEquals(reference(img, r, outW, outH, norm), ImageTensors.toTensor(img, r, outW, outH, norm), 0f)
		}
	}

	// ---- rotation ----

	private fun noise(w: Int, h: Int): PixelImage {
		var x = 12345
		return PixelImage(
			IntArray(w * h) {
				x = (x * 1103515245 + 12345) and 0x7fffffff
				(x and 0xFFFFFF) or (0xFF shl 24)
			},
			w, h,
		)
	}

	/** Crop, rotate 90 degrees counter-clockwise pixel by pixel (np.rot90), then the ordinary resize. */
	private fun rotateCrop(img: PixelImage, r: Box): PixelImage {
		val rx = r.x.toInt()
		val ry = r.y.toInt()
		val rw = r.w.toInt()
		val rh = r.h.toInt()
		val out = IntArray(rw * rh)
		// rotated is rh wide and rw tall: rotated[y'][x'] = src[y = x'][x = W-1-y']
		for (yp in 0 until rw) for (xp in 0 until rh) {
			out[yp * rh + xp] = img.pixels[(ry + xp) * img.width + (rx + rw - 1 - yp)]
		}
		return PixelImage(out, rh, rw)
	}

	@Test
	fun rot90EqualsRotateThenResize() {
		val img = noise(60, 90)
		val norm = TensorNorm.BUBBLE
		for (r in listOf(Box(10, 5, 12, 70), Box(0, 0, 20, 33), Box(30, 20, 9, 60))) {
			val rot = rotateCrop(img, r)
			val w = ceil((r.h / r.w) * 48).toInt()
			val want = ImageTensors.toTensor(rot, Box(0, 0, rot.width, rot.height), w, 48, norm)
			val got = ImageTensors.toTensorRot90(img, r, w, 48, norm)
			assertEquals(want.size, got.size)
			var maxDiff = 0f
			for (i in got.indices) maxDiff = max(maxDiff, abs(got[i] - want[i]))
			assertTrue("max diff $maxDiff for $r", maxDiff < 1e-5f)
		}
	}

	@Test
	fun columnReadingTopToBottomBecomesRowReadingLeftToRight() {
		val w = 4
		val h = 12
		val pixels = IntArray(w * h) { if (it / w < 3) argb(255, 255, 255) else argb(0, 0, 0) }
		val t = ImageTensors.toTensorRot90(PixelImage(pixels, w, h), Box(0, 0, w, h), 12, 4, TensorNorm.BUBBLE)
		assertTrue(t[0] > 0.9f) // left
		assertTrue(t[11] < 0.1f) // right
	}

	@Test
	fun isVerticalBoxThreshold() {
		assertTrue(Box(0, 0, 10, 15).isVertical)
		assertFalse(Box(0, 0, 10, 14).isVertical)
		assertFalse(Box(0, 0, 100, 20).isVertical)
	}
}
