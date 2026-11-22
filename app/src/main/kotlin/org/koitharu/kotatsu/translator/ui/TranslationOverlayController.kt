package org.koitharu.kotatsu.translator.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Rect
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.koitharu.kotatsu.translator.domain.model.PageTranslation

/**
 * Implemented by the page image views that draw the translation in `onDraw`.
 */
interface TranslationOverlayHost {

	val overlayController: TranslationOverlayController
}

/**
 * Connects a page view with its [TranslationOverlay]. The overlay (and its text layouts) is created only when a
 * translation with text is shown for the first time, so a view of a page that is never translated costs one null
 * check per draw. The parameters set before that are only remembered.
 */
class TranslationOverlayController(private val view: SubsamplingScaleImageView) {

	private var overlay: TranslationOverlay? = null
	private var isHidden = false
	private var isImageFinal = false
	private var crop: Rect? = null
	private var colorFilter: ColorFilter? = null
	private var fontScale = 1f

	/** Call from `onDraw` after the image is drawn */
	fun draw(canvas: Canvas) {
		overlay?.draw(canvas, view)
	}

	/** Blocks of [PageTranslation.Ready] are drawn, any other state clears the overlay */
	fun setTranslation(state: PageTranslation) {
		val blocks = state.blocksOrEmpty()
		var current = overlay
		if (current == null) {
			if (blocks.isEmpty()) return
			current = TranslationOverlay(view.context)
			current.setHidden(isHidden)
			current.setImageFinal(isImageFinal)
			current.setCrop(crop)
			current.setColorFilter(colorFilter)
			current.setFontScale(fontScale)
			overlay = current
		}
		if (current.setBlocks(blocks)) view.invalidate()
	}

	/** The original text is shown instead of the translation */
	fun setHidden(value: Boolean) {
		isHidden = value
		if (overlay?.setHidden(value) == true) view.invalidate()
	}

	fun setColorFilter(value: ColorFilter?) {
		colorFilter = value
		if (overlay?.setColorFilter(value) == true) view.invalidate()
	}

	fun setFontScale(value: Float) {
		fontScale = value
		if (overlay?.setFontScale(value) == true) view.invalidate()
	}

	/**
	 * Block boxes are pixels of the whole image; when the view shows a region of it (cropped margins) this is the
	 * region, null otherwise.
	 */
	fun setCrop(bounds: Rect?) {
		crop = bounds
		if (overlay?.setCrop(bounds) == true) view.invalidate()
	}

	/** The final (not a preview) image is shown, only then the boxes match the view */
	fun setImageFinal(value: Boolean) {
		isImageFinal = value
		if (overlay?.setImageFinal(value) == true) view.invalidate()
	}
}
