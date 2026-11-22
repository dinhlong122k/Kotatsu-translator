package org.koitharu.kotatsu.translator.domain.model

import java.io.IOException

class AiException(
	val kind: Kind,
	message: String,
	val retryAfterMs: Long? = null,
	val partial: TranslateResult? = null,
	/** HTTP status of the response that caused the error, null if it did not come from a response */
	val httpCode: Int? = null,
) : IOException(message) {

	enum class Kind { AUTH, RETRYABLE, REJECTED, BAD_OUTPUT, ABORTED }
}

class OcrNotReadyException(val missingPack: String) : IllegalStateException()

class ModelIntegrityException(val modelKey: String) : IOException()
