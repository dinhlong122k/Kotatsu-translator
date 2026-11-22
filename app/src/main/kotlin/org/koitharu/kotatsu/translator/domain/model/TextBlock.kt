package org.koitharu.kotatsu.translator.domain.model

import android.graphics.Rect

/**
 * Text region recognized by OCR. [box] is in pixel coordinates of the original image (not cropped, not scaled).
 */
data class TextBlock(
	val id: String,
	val box: Rect,
	val text: String,
	val confidence: Float,
	val isVertical: Boolean,
)

data class TranslatedBlock(
	val id: String,
	val box: Rect,
	val source: String,
	val text: String,
)
