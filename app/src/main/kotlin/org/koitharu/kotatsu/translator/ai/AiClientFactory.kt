package org.koitharu.kotatsu.translator.ai

import dagger.Lazy
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.translator.TranslatorHttpClient
import org.koitharu.kotatsu.translator.data.TranslatorSecrets
import org.koitharu.kotatsu.translator.data.TranslatorSettings
import org.koitharu.kotatsu.translator.domain.model.AiProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates [AiClient]s. Clients are cheap and read the key and the base URL from the settings on every call, so a
 * created client can be kept while the user changes the settings. The HTTP client is injected lazily: the base
 * client of the app must not be created on the main thread.
 */
@Singleton
class AiClientFactory @Inject constructor(
	@TranslatorHttpClient private val okHttpClient: Lazy<OkHttpClient>,
	private val settings: TranslatorSettings,
	private val secrets: TranslatorSecrets,
) {

	private val transport by lazy(LazyThreadSafetyMode.PUBLICATION) { AiTransport(okHttpClient.get()) }

	/**
	 * Without an API key the calls of the returned client throw `AiException(AUTH)` without sending a request.
	 * The exception is the OpenAI-compatible provider with a custom base URL: the key is optional there.
	 */
	fun create(provider: AiProvider): AiClient = when (provider) {
		AiProvider.OPENAI -> OpenAiClient(
			transport = transport,
			baseUrl = { settings.baseUrlFor(AiProvider.OPENAI) },
			apiKey = { secrets.get(AiProvider.OPENAI) },
			allowEmptyKey = { isCustomOpenAiEndpoint() },
		)

		AiProvider.GEMINI -> GeminiClient(
			transport = transport,
			baseUrl = { settings.baseUrlFor(AiProvider.GEMINI) },
			apiKey = { secrets.get(AiProvider.GEMINI) },
		)

		AiProvider.CLAUDE -> ClaudeClient(
			transport = transport,
			baseUrl = { settings.baseUrlFor(AiProvider.CLAUDE) },
			apiKey = { secrets.get(AiProvider.CLAUDE) },
		)
	}

	private fun isCustomOpenAiEndpoint(): Boolean {
		val custom = settings.openAiBaseUrl?.trim()?.trimEnd('/')
		return !custom.isNullOrEmpty() && custom != TranslatorSettings.BASE_URL_OPENAI
	}
}
