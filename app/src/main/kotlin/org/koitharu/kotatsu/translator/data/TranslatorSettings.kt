package org.koitharu.kotatsu.translator.data

import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.translator.domain.model.AiProvider
import org.koitharu.kotatsu.translator.domain.model.OcrMode
import javax.inject.Inject

/**
 * Typed access to the AI Translator preferences. API keys are NOT here: see [TranslatorSecrets].
 */
@Reusable
class TranslatorSettings @Inject constructor(
	private val settings: AppSettings,
) {

	var isEnabled: Boolean
		get() = settings.isTranslatorEnabled
		set(value) {
			settings.isTranslatorEnabled = value
		}

	/**
	 * Translate automatically when a chapter is opened
	 */
	var isAutoOnOpen: Boolean
		get() = settings.isTranslatorAutoOnOpen
		set(value) {
			settings.isTranslatorAutoOnOpen = value
		}

	/**
	 * Names of manga sources that are always translated
	 */
	val autoSources: Set<String>
		get() = settings.translatorAutoSources

	fun isAutoSource(sourceName: String): Boolean = sourceName in settings.translatorAutoSources

	fun setAutoSource(sourceName: String, isAuto: Boolean) {
		val current = settings.translatorAutoSources
		if ((sourceName in current) == isAuto) {
			return
		}
		settings.translatorAutoSources = if (isAuto) current + sourceName else current - sourceName
	}

	/**
	 * Target language code, "vi" by default
	 */
	var targetLanguage: String
		get() = settings.translatorTargetLanguage
		set(value) {
			settings.translatorTargetLanguage = value
		}

	/**
	 * Source language chosen by the user (like "ko", "ja") or null if it should be detected from the manga source
	 */
	var sourceLanguageOverride: String?
		get() = settings.translatorSourceLanguage.takeUnless { it == AppSettings.TRANSLATOR_SOURCE_LANG_AUTO }
		set(value) {
			settings.translatorSourceLanguage = value ?: AppSettings.TRANSLATOR_SOURCE_LANG_AUTO
		}

	var provider: AiProvider
		get() = settings.translatorProvider
		set(value) {
			settings.translatorProvider = value
		}

	/**
	 * Model chosen by the user for the [provider] or its default one (see [defaultModelFor])
	 */
	fun modelFor(provider: AiProvider): String = settings.getTranslatorModel(provider) ?: defaultModelFor(provider)

	/**
	 * Model chosen by the user for the [provider] or null if nothing selected
	 */
	fun modelOrNull(provider: AiProvider): String? = settings.getTranslatorModel(provider)

	fun setModel(provider: AiProvider, model: String?) = settings.setTranslatorModel(provider, model)

	/**
	 * Base URL without trailing slash. Only OpenAI-compatible provider can be customized,
	 * for others the fixed vendor endpoint is returned.
	 */
	fun baseUrlFor(provider: AiProvider): String = when (provider) {
		AiProvider.GEMINI -> BASE_URL_GEMINI
		AiProvider.CLAUDE -> BASE_URL_CLAUDE
		AiProvider.OPENAI -> settings.translatorOpenAiBaseUrl?.trimEnd('/') ?: BASE_URL_OPENAI
	}

	/**
	 * Custom base URL of OpenAI-compatible endpoint or null if the default one is used
	 */
	var openAiBaseUrl: String?
		get() = settings.translatorOpenAiBaseUrl
		set(value) {
			settings.translatorOpenAiBaseUrl = value
		}

	var ocrMode: OcrMode
		get() = settings.translatorOcrMode
		set(value) {
			settings.translatorOcrMode = value
		}

	/**
	 * 0 means no limit
	 */
	var requestsPerMinute: Int
		get() = settings.translatorRequestsPerMinute.coerceIn(0, MAX_REQUESTS_PER_MINUTE)
		set(value) {
			settings.translatorRequestsPerMinute = value.coerceIn(0, MAX_REQUESTS_PER_MINUTE)
		}

	var isPrefetchNextChapter: Boolean
		get() = settings.isTranslatorPrefetchNextChapter
		set(value) {
			settings.isTranslatorPrefetchNextChapter = value
		}

	var isFallbackEnabled: Boolean
		get() = settings.isTranslatorFallbackEnabled
		set(value) {
			settings.isTranslatorFallbackEnabled = value
		}

	var isHedgingEnabled: Boolean
		get() = settings.isTranslatorHedgingEnabled
		set(value) {
			settings.isTranslatorHedgingEnabled = value
		}

	/**
	 * Translated text size, 80..150 (percent), 100 by default
	 */
	var fontScalePercent: Int
		get() = settings.translatorFontScalePercent.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
		set(value) {
			settings.translatorFontScalePercent = value.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
		}

	val fontScale: Float
		get() = fontScalePercent / 100f

	/**
	 * How long to keep series context, in days, 1..30, 7 by default
	 */
	var contextRetentionDays: Int
		get() = settings.translatorContextRetentionDays.coerceIn(MIN_CONTEXT_DAYS, MAX_CONTEXT_DAYS)
		set(value) {
			settings.translatorContextRetentionDays = value.coerceIn(MIN_CONTEXT_DAYS, MAX_CONTEXT_DAYS)
		}

	fun observeEnabled(): Flow<Boolean> = settings.observeAsFlow(AppSettings.KEY_TRANSLATOR_ENABLED) { isTranslatorEnabled }

	fun observeFontScale(): Flow<Float> = settings.observeAsFlow(AppSettings.KEY_TRANSLATOR_FONT_SCALE) {
		translatorFontScalePercent.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE) / 100f
	}

	fun observeAutoSources(): Flow<Set<String>> = settings.observeAsFlow(AppSettings.KEY_TRANSLATOR_AUTO_SOURCES) {
		translatorAutoSources
	}

	fun observeTargetLanguage(): Flow<String> = settings.observeAsFlow(AppSettings.KEY_TRANSLATOR_TARGET_LANG) {
		translatorTargetLanguage
	}

	fun observeSourceLanguageOverride(): Flow<String?> = settings.observeAsFlow(AppSettings.KEY_TRANSLATOR_SOURCE_LANG) {
		translatorSourceLanguage.takeUnless { it == AppSettings.TRANSLATOR_SOURCE_LANG_AUTO }
	}

	fun observeProvider(): Flow<AiProvider> = settings.observeAsFlow(AppSettings.KEY_TRANSLATOR_PROVIDER) {
		translatorProvider
	}

	fun observeOcrMode(): Flow<OcrMode> = settings.observeAsFlow(AppSettings.KEY_TRANSLATOR_OCR_MODE) {
		translatorOcrMode
	}

	/**
	 * Emits (with no payload) once immediately and after every change of any AI Translator preference
	 */
	fun observeAny(): Flow<Unit> = settings.observeChanges()
		.filter { key -> key == null || key.startsWith(KEY_PREFIX) }
		.onStart { emit(null) }
		.map { }

	companion object {

		const val BASE_URL_GEMINI = "https://generativelanguage.googleapis.com"
		const val BASE_URL_CLAUDE = "https://api.anthropic.com"
		const val BASE_URL_OPENAI = "https://api.openai.com/v1"

		const val MAX_REQUESTS_PER_MINUTE = 600
		const val MIN_FONT_SCALE = 80
		const val MAX_FONT_SCALE = 150
		const val MIN_CONTEXT_DAYS = 1
		const val MAX_CONTEXT_DAYS = 30

		private const val KEY_PREFIX = "translator_"

		/**
		 * Fallback model ids used when the user has not chosen one. Presets for the UI list live in the `ai` package.
		 */
		fun defaultModelFor(provider: AiProvider): String = when (provider) {
			AiProvider.GEMINI -> "gemini-3.6-flash"
			AiProvider.OPENAI -> "gpt-4o-mini"
			AiProvider.CLAUDE -> "claude-haiku-5-5"
		}
	}
}
