package org.koitharu.kotatsu.translator.ocr

/**
 * Axis-aligned box used by the pure OCR algorithms (they must not depend on android.graphics.Rect, which is
 * a stub in JVM unit tests). Converted to android.graphics.Rect only at the engine boundary.
 */
data class Box(val x: Double, val y: Double, val w: Double, val h: Double) {

	constructor(x: Int, y: Int, w: Int, h: Int) : this(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble())

	val right: Double
		get() = x + w

	val bottom: Double
		get() = y + h

	/** A line crop is "vertical" (a column of text) when it is clearly taller than wide, as in PaddleOCR. */
	val isVertical: Boolean
		get() = h >= 1.5 * w
}

/** Pixels in android.graphics.Color ARGB order, row-major. */
class PixelImage(
	@JvmField val pixels: IntArray,
	@JvmField val width: Int,
	@JvmField val height: Int,
)

/** Clamps the box to the image [0, imgW] x [0, imgH] and snaps it outwards to whole pixels (port of `clampRect`). */
fun Box.clampTo(imgW: Int, imgH: Int): Box {
	val x0 = kotlin.math.max(0.0, kotlin.math.floor(x))
	val y0 = kotlin.math.max(0.0, kotlin.math.floor(y))
	val x1 = kotlin.math.min(imgW.toDouble(), kotlin.math.ceil(right))
	val y1 = kotlin.math.min(imgH.toDouble(), kotlin.math.ceil(bottom))
	return Box(x0, y0, kotlin.math.max(0.0, x1 - x0), kotlin.math.max(0.0, y1 - y0))
}
