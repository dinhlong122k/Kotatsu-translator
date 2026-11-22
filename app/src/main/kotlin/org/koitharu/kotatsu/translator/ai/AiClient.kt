package org.koitharu.kotatsu.translator.ai

import org.koitharu.kotatsu.translator.domain.model.TranslateRequest
import org.koitharu.kotatsu.translator.domain.model.TranslateResult

/**
 * Model entry returned by [AiClient.listModels]. [id] is what must be passed to the other methods.
 */
data class AiModelInfo(
	val id: String,
	val displayName: String?,
)

/**
 * Adapter for one AI provider. All methods throw [org.koitharu.kotatsu.translator.domain.model.AiException]
 * for provider and network failures and [kotlinx.coroutines.CancellationException] when the coroutine is cancelled
 * (the running HTTP call is cancelled too).
 */
interface AiClient {

	/**
	 * Translates [request] with streaming. [onBlock] is called (on a background thread) as soon as the translation
	 * of a requested block id is fully received, before the whole response ends.
	 */
	suspend fun translate(
		model: String,
		request: TranslateRequest,
		onBlock: (id: String, text: String) -> Unit,
	): TranslateResult

	/**
	 * Models available for the configured key, sorted by id.
	 */
	suspend fun listModels(): List<AiModelInfo>

	/**
	 * Cheap authenticated GET of the model resource (no tokens spent). Throws if the key or the model is wrong.
	 */
	suspend fun testModel(model: String)

	/**
	 * Opens the connection to the provider in advance. Never throws (except for cancellation).
	 */
	suspend fun warmUp(model: String)
}
