package org.koitharu.kotatsu.settings.translator

import android.content.res.Resources
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.EditTextValidator
import org.koitharu.kotatsu.translator.ai.AiHttp
import org.koitharu.kotatsu.translator.domain.model.AiException
import org.koitharu.kotatsu.translator.domain.model.ModelIntegrityException

/**
 * Accepts only http and https addresses (an empty value means the default address)
 */
class HttpUrlValidator : EditTextValidator() {

	override fun validate(text: String): ValidationResult {
		val trimmed = text.trim()
		if (trimmed.isEmpty() || isValid(trimmed)) {
			return ValidationResult.Success
		}
		return ValidationResult.Failed(context.getString(R.string.translator_settings_base_url_invalid))
	}

	companion object {

		fun isValid(value: String): Boolean = value.toHttpUrlOrNull() != null
	}
}

/**
 * Message of a failed AI request for the settings screen. The text of [AiException] is already redacted by the AI
 * client, it is only used as a detail.
 */
internal fun Throwable.toTranslatorMessage(resources: Resources, model: String?, hasKey: Boolean): String {
	val detail = AiHttp.clean(message.orEmpty(), emptyList(), MAX_DETAIL)
	if (this is ModelIntegrityException) {
		return resources.getString(R.string.translator_error_model_corrupted)
	}
	if (this !is AiException) {
		return resources.getString(R.string.translator_settings_error_network, detail).trim()
	}
	return when (kind) {
		AiException.Kind.AUTH -> resources.getString(
			if (hasKey) R.string.translator_error_api_key_invalid else R.string.translator_error_api_key_missing,
		)

		AiException.Kind.REJECTED -> if (model != null) {
			resources.getString(R.string.translator_settings_error_model_not_found, model, detail)
		} else {
			resources.getString(R.string.translator_settings_error_network, detail).trim()
		}

		AiException.Kind.RETRYABLE -> if (httpCode == 429) {
			resources.getString(R.string.translator_error_quota)
		} else {
			resources.getString(R.string.translator_settings_error_network, detail).trim()
		}

		AiException.Kind.BAD_OUTPUT,
		AiException.Kind.ABORTED,
			-> resources.getString(R.string.translator_settings_error_network, detail).trim()
	}
}

private const val MAX_DETAIL = 200
