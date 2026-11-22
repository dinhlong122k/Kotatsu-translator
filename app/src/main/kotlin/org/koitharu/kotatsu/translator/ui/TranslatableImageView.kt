package org.koitharu.kotatsu.translator.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

/**
 * Page image view that draws the translation over the image. When no translation is set `onDraw` does nothing
 * extra. Page views of the webtoon mode do the same in `WebtoonImageView`.
 */
class TranslatableImageView @JvmOverloads constructor(
	context: Context,
	attr: AttributeSet? = null,
) : SubsamplingScaleImageView(context, attr), TranslationOverlayHost {

	override val overlayController = TranslationOverlayController(this)

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		overlayController.draw(canvas)
	}
}
