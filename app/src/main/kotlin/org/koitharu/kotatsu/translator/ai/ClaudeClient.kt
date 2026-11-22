package org.koitharu.kotatsu.translator.ai

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.translator.domain.model.AiException
import org.koitharu.kotatsu.translator.domain.model.TranslateRequest
import org.koitharu.kotatsu.translator.domain.model.TranslateResult

/**
 * Anthropic Messages API. [baseUrl] has no version segment ("https://api.anthropic.com").
 */
internal class ClaudeClient(
	private val transport: AiTransport,
	private val baseUrl: () -> String,
	private val apiKey: () -> String?,
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
			url = root() + "/messages",
			headers = headers,
			body = buildBody(model, request),
			onEvent = { handleEvent(it, feed) },
		)
		return TranslateResultParser.parse(feed.text, request.blockIds)
	}

	override suspend fun listModels(): List<AiModelInfo> {
		val headers = headers()
		val out = ArrayList<AiModelInfo>()
		var afterId: String? = null
		var pages = 0
		do {
			val url = root() + "/models?limit=1000" + (afterId?.let { "&after_id=" + encodePathSegment(it) } ?: "")
			val json = transport.getJson(url, headers)
			json.optJSONArray("data")?.objects()?.forEach { m ->
				val id = m.strOrNull("id") ?: return@forEach
				out.add(AiModelInfo(id, m.strOrNull("display_name")))
			}
			afterId = if (json.optBoolean("has_more", false)) json.strOrNull("last_id")?.takeIf { it.isNotEmpty() } else null
			pages++
		} while (afterId != null && pages < MAX_PAGES)
		return out.normalized()
	}

	override suspend fun testModel(model: String) {
		transport.get(root() + "/models/" + encodePathSegment(model.trim()), headers(), TEST_TIMEOUT_MS, readBody = false)
	}

	override suspend fun warmUp(model: String) {
		runCatchingCancellable {
			val headers = headers()
			if (warmThrottle.tryAcquire("claude|$model")) {
				transport.get(root() + "/models?limit=1", headers, WarmThrottle.WARM_TIMEOUT_MS, readBody = false)
			}
		}
	}

	private fun root() = baseUrl().trimEnd('/') + "/v1"

	private fun headers(): Map<String, String> {
		val key = apiKey()?.trim().orEmpty()
		if (key.isEmpty()) throw missingKey("Claude")
		return mapOf(
			"x-api-key" to key,
			"anthropic-version" to API_VERSION,
		)
	}

	companion object {

		const val API_VERSION = "2023-06-01"
		const val MAX_TOKENS = 8192
		private const val MAX_PAGES = 5
		private val RETRYABLE_TYPE = Regex("overloaded|rate_limit|api_error")
		private val AUTH_TYPE = Regex("authentication|permission")

		fun buildBody(model: String, request: TranslateRequest): JSONObject = JSONObject()
			.put("model", model)
			.put("max_tokens", MAX_TOKENS)
			.put("stream", true)
			.put(
				"system",
				JSONArray().put(
					JSONObject()
						.put("type", "text")
						.put("text", request.stablePrompt)
						.put("cache_control", JSONObject().put("type", "ephemeral")),
				),
			)
			.put(
				"messages",
				JSONArray().put(JSONObject().put("role", "user").put("content", request.dynamicPrompt)),
			)

		/**
		 * Appends text deltas to [feed]; throws [AiException] for an error event.
		 */
		fun handleEvent(data: String, feed: PartialFeed) {
			val ev = try {
				JSONObject(data)
			} catch (e: JSONException) {
				return
			}
			when (ev.opt("type")) {
				"content_block_delta" -> {
					val delta = ev.optJSONObject("delta") ?: return
					val text = delta.opt("text")
					if (delta.opt("type") == "text_delta" && text is String) feed.push(text)
				}

				"error" -> {
					val err = ev.optJSONObject("error")
					val type = err?.strOrNull("type").orEmpty()
					val msg = eventMessage(err?.opt("message"), "Claude stream error")
					val kind = when {
						RETRYABLE_TYPE.containsMatchIn(type) -> AiException.Kind.RETRYABLE
						AUTH_TYPE.containsMatchIn(type) -> AiException.Kind.AUTH
						else -> AiException.Kind.REJECTED
					}
					throw AiException(kind, msg)
				}
			}
		}
	}
}
