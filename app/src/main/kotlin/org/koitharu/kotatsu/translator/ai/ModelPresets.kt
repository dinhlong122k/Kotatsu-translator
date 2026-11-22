package org.koitharu.kotatsu.translator.ai

import org.koitharu.kotatsu.translator.domain.model.AiProvider

data class ModelPreset(
	val id: String,
	val label: String,
)

/**
 * Static model lists, used only when the list cannot be loaded from the provider ([AiClient.listModels]).
 * Ported from the browser extension, where the ids were checked on 2026-11-05: they may be outdated.
 */
object ModelPresets {

	private val GEMINI = listOf(
		ModelPreset("gemini-3.8-flash", "Gemini 3.8 Flash (mới nhất)"),
		ModelPreset("gemini-3.6-flash", "Gemini 3.6 Flash (stable)"),
		ModelPreset("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite (stable, rẻ)"),
		ModelPreset("gemini-3.1-flash-lite", "Gemini 3.1 Flash-Lite (stable)"),
		ModelPreset("gemini-3.1-pro-preview", "Gemini 3.1 Pro (preview)"),
		ModelPreset("gemini-3-flash-preview", "Gemini 3 Flash (preview)"),
	)

	private val CLAUDE = listOf(
		ModelPreset("claude-fable-5-1", "Claude Fable 5.1"),
		ModelPreset("claude-opus-5-5", "Claude Opus 5.5"),
		ModelPreset("claude-sonnet-5-5", "Claude Sonnet 5.5"),
		ModelPreset("claude-haiku-5-5", "Claude Haiku 5.5"),
	)

	// Never Pro models: too slow for reading pace.
	private val GEMINI_FALLBACKS = listOf(
		"gemini-3.1-flash-lite",
		"gemini-3.5-flash-lite",
		"gemini-3.6-flash",
		"gemini-3-flash-preview",
		"gemini-3.8-flash",
	)

	private val CLAUDE_FALLBACKS = listOf("claude-haiku-5-5", "claude-sonnet-5-5")

	/**
	 * OpenAI-compatible servers have no preset: the user types the model name.
	 */
	fun presets(provider: AiProvider): List<ModelPreset> = when (provider) {
		AiProvider.GEMINI -> GEMINI
		AiProvider.CLAUDE -> CLAUDE
		AiProvider.OPENAI -> emptyList()
	}

	/**
	 * Models to try, in order, when [primary] is overloaded. Never contains [primary].
	 */
	fun fallbackModels(provider: AiProvider, primary: String): List<String> {
		val list = when (provider) {
			AiProvider.GEMINI -> GEMINI_FALLBACKS
			AiProvider.CLAUDE -> CLAUDE_FALLBACKS
			AiProvider.OPENAI -> emptyList()
		}
		return list.filter { it != primary }
	}
}
