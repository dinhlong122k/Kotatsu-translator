package org.koitharu.kotatsu.translator.ai

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.translator.domain.model.AiException
import org.koitharu.kotatsu.translator.domain.model.TranslateRequest
import org.koitharu.kotatsu.translator.domain.model.TranslateResult

/**
 * OpenAI and OpenAI-compatible servers (OpenRouter, DeepSeek, Ollama...). [baseUrl] already includes the version
 * segment ("https://api.openai.com/v1"). [allowEmptyKey] is true for custom servers that need no key.
 */
internal class OpenAiClient(
	private val transport: AiTransport,
	private val baseUrl: () -> String,
	private val apiKey: () -> String?,
	private val allowEmptyKey: () -> Boolean,
	private val warmThrottle: WarmThrottle = WarmThrottle.SHARED,
) : AiClient {

	override suspend fun translate(
		model: String,
		request: TranslateRequest,
		onBlock: (id: String, text: String) -> Unit,
	): TranslateResult {
		val headers = headers()
		val feed = PartialFeed(request.blockIds, onBlock)
		transport.postStream(
			url = url("/chat/completions"),
			headers = headers,
			body = buildBody(model, request),
			onEvent = { handleEvent(it, feed) },
		)
		val text = feed.text
		if (text.isEmpty()) throw badOutput("Missing message content")
		return TranslateResultParser.parse(text, request.blockIds)
	}

	override suspend fun listModels(): List<AiModelInfo> {
		val json = transport.getJson(url("/models"), headers())
		val data = json.optJSONArray("data") ?: return emptyList()
		return data.objects().mapNotNull { m -> m.strOrNull("id")?.let { AiModelInfo(it, null) } }.normalized()
	}

	override suspend fun testModel(model: String) {
		val headers = headers()
		try {
			transport.get(url("/models/" + encodePathSegment(model)), headers, TEST_TIMEOUT_MS, readBody = false)
		} catch (e: AiException) {
			// Many compatible servers have no /models/{id} (or ids with a slash): look the id up in the list instead.
			if (e.kind != AiException.Kind.REJECTED || e.message?.startsWith("HTTP 404") != true) throw e
			val list = try {
				listModels()
			} catch (e2: AiException) {
				throw e
			}
			if (list.none { it.id == model }) throw e
		}
	}

	override suspend fun warmUp(model: String) {
		runCatchingCancellable {
			val headers = headers()
			if (warmThrottle.tryAcquire("openai|" + baseUrl() + "|" + model)) {
				transport.get(url("/models"), headers, WarmThrottle.WARM_TIMEOUT_MS, readBody = false)
			}
		}
	}

	private fun url(path: String) = baseUrl().trimEnd('/') + path

	private fun headers(): Map<String, String> {
		val key = apiKey()?.trim().orEmpty()
		if (key.isEmpty()) {
			if (allowEmptyKey()) return emptyMap()
			throw missingKey("OpenAI")
		}
		return mapOf("Authorization" to "Bearer $key")
	}

	companion object {

		fun buildBody(model: String, request: TranslateRequest): JSONObject = JSONObject()
			.put("model", model)
			.put(
				"messages",
				JSONArray()
					.put(JSONObject().put("role", "system").put("content", request.stablePrompt))
					.put(JSONObject().put("role", "user").put("content", request.dynamicPrompt)),
			)
			.put("response_format", JSONObject().put("type", "json_object"))
			.put("stream", true)

		/**
		 * Appends `choices[0].delta.content` to [feed]; throws [AiException] for an error event.
		 */
		fun handleEvent(data: String, feed: PartialFeed) {
			val ev = try {
				JSONObject(data)
			} catch (e: JSONException) {
				return
			}
			val err = ev.opt("error")
			if (err != null && err != JSONObject.NULL) {
				val msg = eventMessage((err as? JSONObject)?.opt("message"), "OpenAI stream error")
				throw AiException(AiException.Kind.REJECTED, msg)
			}
			val content = ev.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.opt("content")
			if (content is String) feed.push(content)
		}
	}
}
