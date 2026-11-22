package org.koitharu.kotatsu.translator.domain

import org.koitharu.kotatsu.translator.domain.model.AiException

/**
 * One-off events of the translator that the reader shows as a message with an action.
 */
sealed interface TranslatorEvent {

	/** The API key is missing or was rejected; sent once until the settings change */
	data object AuthRequired : TranslatorEvent

	/** The request limit of the provider is exceeded */
	data object QuotaExceeded : TranslatorEvent

	/** The OCR data of the pack [pack] is not installed; sent once per pack */
	data class OcrPackMissing(val pack: String) : TranslatorEvent
}

private val QUOTA_REGEX = Regex("quota|RESOURCE_EXHAUSTED|rate.?limit|too many requests", RegexOption.IGNORE_CASE)

/** Tells a rate limit or an exhausted quota (429) from other temporary errors */
fun AiException.isQuotaExceeded(): Boolean = kind == AiException.Kind.RETRYABLE &&
	(httpCode == 429 || message?.let { QUOTA_REGEX.containsMatchIn(it) } == true)

/** True if the key is absent (no request was sent), as opposed to a key rejected by the provider */
fun AiException.isMissingKey(): Boolean = kind == AiException.Kind.AUTH && message?.startsWith("Missing API key") == true
