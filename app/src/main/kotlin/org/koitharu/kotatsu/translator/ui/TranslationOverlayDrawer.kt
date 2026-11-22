package org.koitharu.kotatsu.translator.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.koitharu.kotatsu.translator.domain.model.TranslatedBlock
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws translated text over the original text: a white rounded rectangle at the position of the block and black text
 * that is centered and shrunk to fit (no inpainting). Used from `onDraw` of the page views, so it does not allocate
 * while nothing changes: the layouts are cached per block and per width.
 *
 * Not thread safe, use from the main thread.
 */
class TranslationOverlayDrawer(context: Context) {

	private val density = context.resources.displayMetrics.density
	private val minTextSize = MIN_TEXT_DP * density
	private val maxTextSize = MAX_TEXT_DP * density
	private val cornerRadius = CORNER_DP * density
	private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BACKGROUND_COLOR }
	private val rect = RectF()
	private val cache = HashMap<String, CachedLayout>()
	private var cachedBlocks: List<TranslatedBlock>? = null
	private var cachedFontScale = 1f

	private class CachedLayout(
		val width: Int,
		val maxHeight: Int,
		val text: String,
		val layout: StaticLayout,
		val paint: TextPaint,
	)

	/**
	 * @param scale view pixels per pixel of the original image
	 * @param offsetX view X of the left edge of the original image (not cropped) at this scale
	 * @param offsetY view Y of the top edge of the original image
	 * @param colorFilter the filter of the reader, so that the overlay follows night mode and so on
	 * @param fontScale 0.8..1.5, multiplies the maximum text size
	 */
	fun draw(
		canvas: Canvas,
		blocks: List<TranslatedBlock>,
		scale: Float,
		offsetX: Float,
		offsetY: Float,
		colorFilter: ColorFilter?,
		fontScale: Float,
	) {
		if (blocks.isEmpty() || scale <= 0f) return
		if (cachedBlocks !== blocks || cachedFontScale != fontScale) {
			cache.clear()
			cachedBlocks = blocks
			cachedFontScale = fontScale
		}
		backgroundPaint.colorFilter = colorFilter
		val viewWidth = canvas.width.toFloat()
		val viewHeight = canvas.height.toFloat()
		for (block in blocks) {
			val box = block.box
			val left = box.left * scale + offsetX
			val top = box.top * scale + offsetY
			val right = box.right * scale + offsetX
			val bottom = box.bottom * scale + offsetY
			if (right < 0f || bottom < 0f || left > viewWidth || top > viewHeight) continue // not visible
			val width = (right - left).roundToInt()
			val height = (bottom - top).roundToInt()
			if (width < 1 || height < 1) continue
			rect.set(left, top, right, bottom)
			canvas.drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint)
			val item = layoutFor(block, width, height, fontScale)
			item.paint.colorFilter = colorFilter
			val save = canvas.save()
			canvas.clipRect(rect)
			canvas.translate(left, top + (height - item.layout.height) / 2f)
			item.layout.draw(canvas)
			canvas.restoreToCount(save)
		}
	}

	private fun layoutFor(block: TranslatedBlock, width: Int, height: Int, fontScale: Float): CachedLayout {
		val cached = cache[block.id]
		if (cached != null && cached.width == width && cached.maxHeight == height && cached.text == block.text) {
			return cached
		}
		val paint = cached?.paint ?: TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR }
		val maxSize = max(minTextSize, maxTextSize * fontScale)
		val size = TextFit.largestFitting(minTextSize.roundToInt(), maxSize.roundToInt()) { px ->
			paint.textSize = px.toFloat()
			buildLayout(block.text, paint, width).height <= height
		}
		paint.textSize = size.toFloat()
		val item = CachedLayout(width, height, block.text, buildLayout(block.text, paint, width), paint)
		cache[block.id] = item
		return item
	}

	private fun buildLayout(text: String, paint: TextPaint, width: Int): StaticLayout =
		StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
			.setAlignment(Layout.Alignment.ALIGN_CENTER)
			.setLineSpacing(0f, LINE_SPACING)
			.setIncludePad(false)
			.build()

	companion object {

		private const val MIN_TEXT_DP = 6f
		private const val MAX_TEXT_DP = 28f
		private const val CORNER_DP = 3f
		private const val LINE_SPACING = 1.2f
		private const val BACKGROUND_COLOR = 0xFFFFFFFF.toInt()
		private const val TEXT_COLOR = 0xFF000000.toInt()
	}
}
