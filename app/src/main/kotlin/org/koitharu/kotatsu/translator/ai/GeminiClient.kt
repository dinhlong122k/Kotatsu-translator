package org.koitharu.kotatsu.translator.ai

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.translator.domain.model.AiException
import org.koitharu.kotatsu.translator.domain.model.TranslateRequest
import org.koitharu.kotatsu.translator.domain.model.TranslateResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Google Gemini API. [baseUrl] has no version segment ("https://generativelanguage.googleapis.com").
 */
internal class GeminiClient(
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
		val name = normalizeModel(model)
		val url = root() + "/models/" + encodePathSegment(name) + ":streamGenerateContent?alt=sse"
		val candidates = thinkingCandidates(name)

		var i = minOf(workingIndex[name] ?: 0, candidates.lastIndex)
		var text = ""
		while (true) {
			try {
				val feed = PartialFeed(request.blockIds, onBlock)
				transport.postStream(
					url = url,
					headers = headers,
					body = buildBody(request, candidates[i]),
					onEvent = { handleEvent(it, feed) },
				)
				text = feed.text
				workingIndex[name] = i
				break
			} catch (e: AiException) {
				// The model may not support the requested thinking setting: try the next, weaker one.
				if (e.kind == AiException.Kind.REJECTED && THINKING.containsMatchIn(e.message.orEmpty()) && i < candidates.lastIndex) {
					i++
					continue
				}
				throw e
			}
		}
		if (text.isEmpty()) throw badOutput("Missing candidate text")
		return TranslateResultParser.parse(text, request.blockIds)
	}

	override suspend fun listModels(): List<AiModelInfo> {
		val headers = headers()
		val out = ArrayList<AiModelInfo>()
		var pageToken: String? = null
		var pages = 0
		do {
			val url = root() + "/models?pageSize=1000" + (pageToken?.let { "&pageToken=" + encodePathSegment(it) } ?: "")
			val json = transport.getJson(url, headers)
			json.optJSONArray("models")?.objects()?.forEach { m ->
				val name = m.strOrNull("name") ?: return@forEach
				if (!supportsGenerate(m.optJSONArray("supportedGenerationMethods"))) return@forEach
				out.add(AiModelInfo(name.removePrefix("models/"), m.strOrNull("displayName")))
			}
			pageToken = json.strOrNull("nextPageToken")?.takeIf { it.isNotEmpty() }
			pages++
		} while (pageToken != null && pages < MAX_PAGES)
		return out.normalized()
	}

	override suspend fun testModel(model: String) {
		transport.get(root() + "/models/" + encodePathSegment(normalizeModel(model)), headers(), TEST_TIMEOUT_MS, readBody = false)
	}

	override suspend fun warmUp(model: String) {
		runCatchingCancellable {
			val headers = headers()
			val name = normalizeModel(model)
			if (warmThrottle.tryAcquire("gemini|$name")) {
				transport.get(root() + "/models/" + encodePathSegment(name), headers, WarmThrottle.WARM_TIMEOUT_MS, readBody = false)
			}
		}
	}

	private fun root() = baseUrl().trimEnd('/') + "/v1beta"

	private fun headers(): Map<String, String> {
		val key = apiKey()?.trim().orEmpty()
		if (key.isEmpty()) throw missingKey("Gemini")
		return mapOf("x-goog-api-key" to key)
	}

	companion object {

		private const val MAX_PAGES = 10
		private val THINKING = Regex("thinking", RegexOption.IGNORE_CASE)
		private val VERSION = Regex("^gemini-(\\d+)(?:\\.(\\d+))?")

		/** Index of the thinking candidate that worked last, per model. */
		private val workingIndex = ConcurrentHashMap<String, Int>()

		fun normalizeModel(model: String) = model.trim().removePrefix("models/")

		/**
		 * thinkingConfig values to try, most aggressive first (translation does not need deep reasoning).
		 * Older and unknown models get no thinkingConfig (a single null candidate).
		 */
		fun thinkingCandidates(model: String): List<Map<String, Any>?> {
			val name = model.lowercase()
			val m = VERSION.find(name) ?: return listOf(null)
			val major = m.groupValues[1].toInt()
			val minor = m.groupValues[2].toIntOrNull() ?: 0
			if (major >= 3) {
				return if (name.contains("flash")) {
					listOf(mapOf("thinkingLevel" to "minimal"), mapOf("thinkingLevel" to "low"), null)
				} else {
					listOf(mapOf("thinkingLevel" to "low"), null)
				}
			}
			if (major == 2 && minor == 5) {
				if (name.contains("flash")) return listOf(mapOf("thinkingBudget" to 0), null)
				if (name.contains("pro")) return listOf(mapOf("thinkingBudget" to 128), null)
			}
			return listOf(null)
		}

		fun buildBody(request: TranslateRequest, thinking: Map<String, Any>?): JSONObject {
			val config = JSONObject().put("responseMimeType", "application/json")
			if (thinking != null) {
				val t = JSONObject()
				for ((k, v) in thinking) t.put(k, v)
				config.put("thinkingConfig", t)
			}
			return JSONObject()
				.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", request.stablePrompt))))
				.put(
					"contents",
					JSONArray().put(
						JSONObject().put("role", "user")
							.put("parts", JSONArray().put(JSONObject().put("text", request.dynamicPrompt))),
					),
				)
				.put("generationConfig", config)
		}

		/**
		 * Appends the text parts of `candidates[0]` to [feed], skipping thought parts; throws [AiException]
		 * for an error event (429 and 5xx are retryable).
		 */
		fun handleEvent(data: String, feed: PartialFeed) {
			val ev = try {
				JSONObject(data)
			} catch (e: JSONException) {
				return
			}
			val err = ev.optJSONObject("error")
			if (err != null) {
				val code = (err.opt("code") as? Number)?.toInt() ?: 0
				val kind = if (code == 429 || code >= 500) AiException.Kind.RETRYABLE else AiException.Kind.REJECTED
				throw AiException(kind, eventMessage(err.opt("message"), "Gemini stream error"))
			}
			val parts = ev.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return
			for (part in parts.objects()) {
				if (part.opt("thought") == true) continue
				val text = part.opt("text") as? String ?: continue
				feed.push(text)
			}
		}

		private fun supportsGenerate(methods: JSONArray?): Boolean {
			if (methods == null) return false
			for (i in 0 until methods.length()) {
				if (methods.opt(i) == "generateContent") return true
			}
			return false
		}
	}
}
