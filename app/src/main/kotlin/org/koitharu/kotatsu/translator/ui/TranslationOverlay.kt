package org.koitharu.kotatsu.translator.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Rect
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.koitharu.kotatsu.translator.domain.model.PageTranslation
import org.koitharu.kotatsu.translator.domain.model.TranslatedBlock

/**
 * State of the overlay of one view and the transform from the coordinates of the image to the ones of the view,
 * built from `scale` and `getCenter()` of the image view only: view = (source - center) * scale + size / 2.
 */
class TranslationOverlay(context: Context) {

	private val drawer = TranslationOverlayDrawer(context)
	private var blocks: List<TranslatedBlock> = emptyList()
	private var cropLeft = 0
	private var cropTop = 0
	private var colorFilter: ColorFilter? = null
	private var fontScale = 1f
	private var isHidden = false
	private var isImageFinal = false

	/** @return true if the view has to be redrawn */
	fun setBlocks(newBlocks: List<TranslatedBlock>): Boolean {
		if (blocks === newBlocks || (blocks.isEmpty() && newBlocks.isEmpty())) return false
		blocks = newBlocks
		return true
	}

	fun setHidden(value: Boolean): Boolean = update(isHidden != value) { isHidden = value }

	fun setColorFilter(value: ColorFilter?): Boolean = update(colorFilter !== value) { colorFilter = value }

	fun setFontScale(value: Float): Boolean = update(fontScale != value) { fontScale = value }

	fun setImageFinal(value: Boolean): Boolean = update(isImageFinal != value) { isImageFinal = value }

	fun setCrop(bounds: Rect?): Boolean {
		val l = bounds?.left ?: 0
		val t = bounds?.top ?: 0
		return update(l != cropLeft || t != cropTop) {
			cropLeft = l
			cropTop = t
		}
	}

	fun draw(canvas: Canvas, view: SubsamplingScaleImageView) {
		if (blocks.isEmpty() || isHidden || !isImageFinal || !view.isReady) return
		val center = view.getCenter() ?: return
		val scale = view.scale
		drawer.draw(
			canvas = canvas,
			blocks = blocks,
			scale = scale,
			offsetX = view.width / 2f - (center.x + cropLeft) * scale,
			offsetY = view.height / 2f - (center.y + cropTop) * scale,
			colorFilter = colorFilter,
			fontScale = fontScale,
		)
	}

	private inline fun update(isChanged: Boolean, block: () -> Unit): Boolean {
		if (isChanged) block()
		return isChanged
	}
}

fun PageTranslation.blocksOrEmpty(): List<TranslatedBlock> = (this as? PageTranslation.Ready)?.blocks.orEmpty()
