package org.koitharu.kotatsu.translator.domain.model

sealed interface PageTranslation {

	/** Not requested yet or the feature is disabled */
	data object Idle : PageTranslation

	/** OCR or translation is in progress */
	data object Pending : PageTranslation

	/** The page has no text */
	data object Empty : PageTranslation

	data class Ready(val blocks: List<TranslatedBlock>, val isPartial: Boolean) : PageTranslation

	data class Failed(val error: Throwable) : PageTranslation
}
