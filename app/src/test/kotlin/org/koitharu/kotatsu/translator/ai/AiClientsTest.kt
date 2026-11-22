package org.koitharu.kotatsu.translator.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.koitharu.kotatsu.translator.domain.model.AiException
import org.koitharu.kotatsu.translator.domain.model.AiException.Kind
import org.koitharu.kotatsu.translator.domain.model.LanguagePair
import org.koitharu.kotatsu.translator.domain.model.TranslateRequest
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AiClientsTest {

	private val tricky = "He said \"hi\" {x} [y] \\ done"
	private val full = "{\"translations\":[{\"id\":\"0:b1\",\"text\":" + AiHttp.jsonQuote(tricky) +
		"},{\"id\":\"0:b2\",\"text\":\"Hai\"},{\"id\":\"9:zz\",\"text\":\"lạ\"}]," +
		"\"glossary\":[{\"src\":\"a\",\"dst\":\"b\",\"kind\":\"name\"}],\"addressing\":[],\"summary\":\"s\"}"

	private val request = TranslateRequest(
		pair = LanguagePair("ko", "vi"),
		stablePrompt = "STABLE",
		dynamicPrompt = "DYNAMIC",
		blockIds = listOf("0:b1", "0:b2"),
	)

	private class Recorded(val url: String, val headers: Map<String, String>, val body: String)

	private fun transport(handler: (Request) -> Response, log: MutableList<Recorded> = ArrayList()): AiTransport {
		val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
			val req = chain.request()
			val buf = Buffer()
			req.body?.writeTo(buf)
			log.add(Recorded(req.url.toString(), req.headers.toMultimap().mapValues { it.value.first() }, buf.readUtf8()))
			handler(req)
		}).build()
		return AiTransport(http)
	}

	private fun response(req: Request, code: Int, body: String, headers: Map<String, String> = emptyMap()): Response =
		Response.Builder()
			.request(req)
			.protocol(Protocol.HTTP_1_1)
			.code(code)
			.message("msg")
			.apply { headers.forEach { (k, v) -> header(k, v) } }
			.body(body.toResponseBody("application/json".toMediaType()))
			.build()

	private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" }

	private fun <T> failure(block: suspend () -> T): AiException {
		try {
			runBlocking { block() }
		} catch (e: AiException) {
			return e
		}
		fail("did not throw")
		throw IllegalStateException()
	}

	private fun openAi(t: AiTransport, key: String? = "k", allowEmpty: Boolean = false) = OpenAiClient(
		t, { "https://o.test/v1" }, { key }, { allowEmpty }, WarmThrottle(),
	)

	private fun gemini(t: AiTransport, key: String? = "k") = GeminiClient(t, { "https://g.test" }, { key }, WarmThrottle())

	private fun claude(t: AiTransport, key: String? = "k") = ClaudeClient(t, { "https://c.test" }, { key }, WarmThrottle())

	// ---- translate

	@Test
	fun openAiStreamsBlocksAndReturnsResult() {
		val log = ArrayList<Recorded>()
		val half = full.length / 2
		val wire = sse(
			"""{"choices":[{"delta":{"role":"assistant"}}]}""",
			"""{"choices":[{"delta":{"content":${AiHttp.jsonQuote(full.substring(0, half))}}}]}""",
			"""{"choices":[{"delta":{"content":${AiHttp.jsonQuote(full.substring(half))}}}]}""",
			"[DONE]",
		)
		val client = openAi(transport({ response(it, 200, wire) }, log))
		val seen = ArrayList<Pair<String, String>>()
		val result = runBlocking { client.translate("gpt-x", request) { id, text -> seen.add(id to text) } }
		assertEquals(listOf("0:b1" to tricky, "0:b2" to "Hai"), seen)
		assertEquals(mapOf("0:b1" to tricky, "0:b2" to "Hai"), result.translations)
		assertEquals("s", result.summary)
		assertEquals("a", result.glossary.single().src)

		val r = log.single()
		assertEquals("https://o.test/v1/chat/completions", r.url)
		assertEquals("Bearer k", r.headers["authorization"])
		val body = JSONObject(r.body)
		assertEquals("gpt-x", body.getString("model"))
		assertTrue(body.getBoolean("stream"))
		assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
		val messages = body.getJSONArray("messages")
		assertEquals("STABLE", messages.getJSONObject(0).getString("content"))
		assertEquals("system", messages.getJSONObject(0).getString("role"))
		assertEquals("DYNAMIC", messages.getJSONObject(1).getString("content"))
		assertFalse(body.has("temperature"))
	}

	@Test
	fun openAiWithoutKeyThrowsAuthWithoutSendingARequest() {
		val log = ArrayList<Recorded>()
		val client = openAi(transport({ response(it, 200, "") }, log), key = null)
		assertEquals(Kind.AUTH, failure { client.translate("m", request) { _, _ -> } }.kind)
		assertEquals(Kind.AUTH, failure { client.listModels() }.kind)
		assertEquals(Kind.AUTH, failure { client.testModel("m") }.kind)
		runBlocking { client.warmUp("m") }
		assertTrue(log.isEmpty())
	}

	@Test
	fun openAiCustomEndpointAllowsEmptyKeyAndSendsNoAuthorization() {
		val log = ArrayList<Recorded>()
		val wire = sse("""{"choices":[{"delta":{"content":${AiHttp.jsonQuote(full)}}}]}""")
		val client = openAi(transport({ response(it, 200, wire) }, log), key = "", allowEmpty = true)
		runBlocking { client.translate("m", request) { _, _ -> } }
		assertFalse(log.single().headers.containsKey("authorization"))
	}

	@Test
	fun geminiSkipsThoughtPartsAndUsesKeyHeader() {
		val log = ArrayList<Recorded>()
		fun ev(text: String, thought: Boolean = false) =
			"""{"candidates":[{"content":{"parts":[{"text":"thinking...","thought":true},{"text":${AiHttp.jsonQuote(text)}${if (thought) ",\"thought\":true" else ""}}]}}]}"""
		val wire = sse(ev(full.substring(0, 50)), ev(full.substring(50)), ev("ignored", thought = true))
		val client = gemini(transport({ response(it, 200, wire) }, log))
		val result = runBlocking { client.translate("gemini-2.0-x", request) { _, _ -> } }
		assertEquals(2, result.translations.size)
		val r = log.single()
		assertEquals("https://g.test/v1beta/models/gemini-2.0-x:streamGenerateContent?alt=sse", r.url)
		assertEquals("k", r.headers["x-goog-api-key"])
		assertFalse(r.url.contains("key="))
		val body = JSONObject(r.body)
		assertEquals("STABLE", body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
		val content = body.getJSONArray("contents").getJSONObject(0)
		assertEquals("user", content.getString("role"))
		assertEquals("DYNAMIC", content.getJSONArray("parts").getJSONObject(0).getString("text"))
		val config = body.getJSONObject("generationConfig")
		assertEquals("application/json", config.getString("responseMimeType"))
		assertFalse(config.has("thinkingConfig"))
	}

	@Test
	fun geminiThinkingLadderFallsThroughThenIsMemoized() {
		val log = ArrayList<Recorded>()
		val ok = sse("""{"candidates":[{"content":{"parts":[{"text":${AiHttp.jsonQuote(full)}}]}}]}""")
		val reject = """{"error":{"message":"Thinking level is not supported for this model"}}"""
		var n = 0
		val t = transport({ if (n++ < 2) response(it, 400, reject) else response(it, 200, ok) }, log)
		runBlocking { gemini(t, "KEY-0123456789").translate("gemini-3-flash-memo", request) { _, _ -> } }
		val configs = log.map { JSONObject(it.body).getJSONObject("generationConfig").optJSONObject("thinkingConfig")?.optString("thinkingLevel") }
		assertEquals(listOf("minimal", "low", null), configs)

		log.clear()
		runBlocking { gemini(t, "KEY-0123456789").translate("gemini-3-flash-memo", request) { _, _ -> } }
		assertEquals(1, log.size)
		assertFalse(JSONObject(log[0].body).getJSONObject("generationConfig").has("thinkingConfig"))
	}

	@Test
	fun geminiRejectionNotAboutThinkingIsNotRetried() {
		val log = ArrayList<Recorded>()
		val t = transport({ response(it, 400, """{"error":{"message":"model not found"}}""") }, log)
		assertEquals(Kind.REJECTED, failure { gemini(t).translate("gemini-3-pro-x", request) { _, _ -> } }.kind)
		assertEquals(1, log.size)
	}

	@Test
	fun claudeHeadersAndBody() {
		val log = ArrayList<Recorded>()
		val wire = sse(
			"""{"type":"message_start"}""",
			"""{"type":"content_block_delta","delta":{"type":"text_delta","text":${AiHttp.jsonQuote(full)}}}""",
			"""{"type":"content_block_delta","delta":{"type":"input_json_delta","partial_json":"{}"}}""",
			"""{"type":"message_stop"}""",
		)
		val client = claude(transport({ response(it, 200, wire) }, log))
		val result = runBlocking { client.translate("claude-x", request) { _, _ -> } }
		assertEquals(2, result.translations.size)
		val r = log.single()
		assertEquals("https://c.test/v1/messages", r.url)
		assertEquals("k", r.headers["x-api-key"])
		assertEquals("2023-06-01", r.headers["anthropic-version"])
		assertFalse(r.headers.containsKey("anthropic-dangerous-direct-browser-access"))
		val body = JSONObject(r.body)
		assertEquals(8192, body.getInt("max_tokens"))
		assertTrue(body.getBoolean("stream"))
		val system = body.getJSONArray("system").getJSONObject(0)
		assertEquals("STABLE", system.getString("text"))
		assertEquals("ephemeral", system.getJSONObject("cache_control").getString("type"))
		assertEquals("DYNAMIC", body.getJSONArray("messages").getJSONObject(0).getString("content"))
	}

	@Test
	fun claudeErrorEventsAreClassified() {
		fun kindOf(type: String): Kind {
			val wire = sse("""{"type":"error","error":{"type":"$type","message":"msg"}}""")
			return failure { claude(transport({ response(it, 200, wire) })).translate("m", request) { _, _ -> } }.kind
		}
		assertEquals(Kind.RETRYABLE, kindOf("overloaded_error"))
		assertEquals(Kind.RETRYABLE, kindOf("rate_limit_error"))
		assertEquals(Kind.AUTH, kindOf("authentication_error"))
		assertEquals(Kind.REJECTED, kindOf("invalid_request_error"))
	}

	@Test
	fun streamErrorEventsOfOtherProviders() {
		val g429 = sse("""{"error":{"code":429,"message":"slow down"}}""")
		assertEquals(Kind.RETRYABLE, failure { gemini(transport({ response(it, 200, g429) })).translate("gemini-2.0-x", request) { _, _ -> } }.kind)
		val g400 = sse("""{"error":{"code":400,"message":"bad"}}""")
		assertEquals(Kind.REJECTED, failure { gemini(transport({ response(it, 200, g400) })).translate("gemini-2.0-y", request) { _, _ -> } }.kind)
		val o = sse("""{"error":{"message":"nope"}}""")
		assertEquals(Kind.REJECTED, failure { openAi(transport({ response(it, 200, o) })).translate("m", request) { _, _ -> } }.kind)
	}

	@Test
	fun emptyStreamIsBadOutput() {
		val empty = sse("[DONE]")
		assertEquals(Kind.BAD_OUTPUT, failure { openAi(transport({ response(it, 200, empty) })).translate("m", request) { _, _ -> } }.kind)
		assertEquals(Kind.BAD_OUTPUT, failure { gemini(transport({ response(it, 200, empty) })).translate("gemini-2.0-z", request) { _, _ -> } }.kind)
		assertEquals(Kind.BAD_OUTPUT, failure { claude(transport({ response(it, 200, empty) })).translate("m", request) { _, _ -> } }.kind)
	}

	@Test
	fun missingBlockIsBadOutputWithPartial() {
		val partial = """{"translations":[{"id":"0:b1","text":"a"}],"glossary":[],"addressing":[],"summary":""}"""
		val wire = sse("""{"choices":[{"delta":{"content":${AiHttp.jsonQuote(partial)}}}]}""")
		val e = failure { openAi(transport({ response(it, 200, wire) })).translate("m", request) { _, _ -> } }
		assertEquals(Kind.BAD_OUTPUT, e.kind)
		assertEquals(mapOf("0:b1" to "a"), e.partial?.translations)
	}

	// ---- errors

	@Test
	fun httpErrorsAreClassifiedAndTheKeyNeverLeaks() {
		val key = "sk-proj-SECRETKEY0123456789abcdefXYZ"
		fun errorFor(code: Int, headers: Map<String, String> = emptyMap(), body: String = """{"error":{"message":"echo $key"}}"""): AiException =
			failure { openAi(transport({ response(it, code, body, headers) }), key = key).translate("m", request) { _, _ -> } }
		assertEquals(Kind.AUTH, errorFor(401).kind)
		assertEquals(Kind.AUTH, errorFor(403).kind)
		assertEquals(Kind.REJECTED, errorFor(404).kind)
		assertEquals(Kind.RETRYABLE, errorFor(500).kind)
		val limited = errorFor(429, mapOf("Retry-After" to "7"))
		assertEquals(Kind.RETRYABLE, limited.kind)
		assertEquals(7000L, limited.retryAfterMs)
		for (code in listOf(401, 404, 429, 500)) {
			assertFalse(errorFor(code).message!!.contains("SECRETKEY"))
		}
	}

	@Test
	fun networkFailureIsRetryable() {
		val t = transport({ throw IOException("connection reset") })
		val e = failure { openAi(t).translate("m", request) { _, _ -> } }
		assertEquals(Kind.RETRYABLE, e.kind)
	}

	@Test
	fun cancellingTheCoroutineCancelsTheCallAndThrowsCancellation() {
		val started = CountDownLatch(1)
		val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
			started.countDown()
			val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
			while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(10)
			if (chain.call().isCanceled()) throw IOException("Canceled")
			throw IOException("was not cancelled")
		}).build()
		val client = openAi(AiTransport(http))
		val begin = System.nanoTime()
		runBlocking {
			val job = async(Dispatchers.Default) { client.translate("m", request) { _, _ -> } }
			assertTrue(started.await(5, TimeUnit.SECONDS))
			job.cancel()
			try {
				job.await()
				fail("expected cancellation")
			} catch (e: CancellationException) {
				// expected: not wrapped into AiException
			}
		}
		assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 3000)
	}

	// ---- models

	@Test
	fun openAiListModelsSortedById() {
		val log = ArrayList<Recorded>()
		val body = """{"data":[{"id":"b-model"},{"id":"a-model"},{"id":"b-model"},{"object":"x"}]}"""
		val models = runBlocking { openAi(transport({ response(it, 200, body) }, log)).listModels() }
		assertEquals(listOf("a-model", "b-model"), models.map { it.id })
		assertNull(models[0].displayName)
		assertEquals("https://o.test/v1/models", log.single().url)
	}

	@Test
	fun geminiListModelsFiltersByGenerateContentPaginatesAndStripsPrefix() {
		val log = ArrayList<Recorded>()
		val page1 = """{"models":[
			{"name":"models/gemini-b","displayName":"Gemini B","supportedGenerationMethods":["generateContent","countTokens"]},
			{"name":"models/embedding-1","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"tok 2"}"""
		val page2 = """{"models":[
			{"name":"models/gemini-a","displayName":"Gemini A","supportedGenerationMethods":["generateContent"]},
			{"name":"models/no-methods"}]}"""
		val t = transport({ if (it.url.queryParameter("pageToken") == null) response(it, 200, page1) else response(it, 200, page2) }, log)
		val models = runBlocking { gemini(t).listModels() }
		assertEquals(listOf("gemini-a", "gemini-b"), models.map { it.id })
		assertEquals(listOf("Gemini A", "Gemini B"), models.map { it.displayName })
		assertEquals(2, log.size)
		assertEquals("https://g.test/v1beta/models?pageSize=1000", log[0].url)
		assertTrue(log[1].url.contains("pageToken=tok%202"))
	}

	@Test
	fun claudeListModels() {
		val log = ArrayList<Recorded>()
		val body = """{"data":[{"id":"claude-b","display_name":"Claude B"},{"id":"claude-a","display_name":"Claude A"}],"has_more":false}"""
		val models = runBlocking { claude(transport({ response(it, 200, body) }, log)).listModels() }
		assertEquals(listOf("claude-a", "claude-b"), models.map { it.id })
		assertEquals("Claude A", models[0].displayName)
		assertEquals("https://c.test/v1/models?limit=1000", log.single().url)
		assertEquals("2023-06-01", log.single().headers["anthropic-version"])
	}

	@Test
	fun testModelUsesGetOfTheModelResource() {
		val log = ArrayList<Recorded>()
		runBlocking {
			gemini(transport({ response(it, 200, "{}") }, log)).testModel("models/gemini-x")
			claude(transport({ response(it, 200, "{}") }, log)).testModel("claude-x")
			openAi(transport({ response(it, 200, "{}") }, log)).testModel("gpt-x")
		}
		assertEquals(
			listOf("https://g.test/v1beta/models/gemini-x", "https://c.test/v1/models/claude-x", "https://o.test/v1/models/gpt-x"),
			log.map { it.url },
		)
		assertTrue(log.all { it.body.isEmpty() })
	}

	@Test
	fun testModelWrongKeyAndUnknownModel() {
		val t401 = transport({ response(it, 401, """{"error":{"message":"bad key"}}""") })
		assertEquals(Kind.AUTH, failure { claude(t401).testModel("m") }.kind)
		val t404 = transport({ response(it, 404, """{"error":{"message":"not found"}}""") })
		assertEquals(Kind.REJECTED, failure { claude(t404).testModel("m") }.kind)
	}

	@Test
	fun openAiTestModelFallsBackToTheModelListOn404() {
		val handler = { req: Request ->
			if (req.url.encodedPath.endsWith("/models")) response(req, 200, """{"data":[{"id":"openai/gpt-x"}]}""")
			else response(req, 404, "{}")
		}
		runBlocking { openAi(transport(handler)).testModel("openai/gpt-x") }
		assertEquals(Kind.REJECTED, failure { openAi(transport(handler)).testModel("missing") }.kind)
	}

	@Test
	fun warmUpSwallowsErrorsAndIsThrottled() {
		val log = ArrayList<Recorded>()
		val throttle = WarmThrottle()
		val client = OpenAiClient(transport({ response(it, 500, "{}") }, log), { "https://o.test/v1" }, { "k" }, { false }, throttle)
		runBlocking {
			client.warmUp("m")
			client.warmUp("m")
		}
		assertEquals(1, log.size)
		assertEquals("https://o.test/v1/models", log[0].url)
		val noNetwork = OpenAiClient(transport({ throw IOException("down") }), { "https://o.test/v1" }, { "k" }, { false }, WarmThrottle())
		runBlocking { noNetwork.warmUp("m") }
	}

	@Test
	fun warmThrottleWindow() {
		val t = WarmThrottle(60_000)
		assertTrue(t.tryAcquire("a", 0))
		assertFalse(t.tryAcquire("a", 59_999))
		assertTrue(t.tryAcquire("b", 1))
		assertTrue(t.tryAcquire("a", 60_000))
	}

	// ---- pure helpers

	@Test
	fun geminiThinkingCandidates() {
		val c = GeminiClient.Companion::thinkingCandidates
		val minimalLow = listOf(mapOf("thinkingLevel" to "minimal"), mapOf("thinkingLevel" to "low"), null)
		assertEquals(minimalLow, c("gemini-3-flash-preview"))
		assertEquals(minimalLow, c("gemini-3.1-flash-lite"))
		assertEquals(listOf(mapOf("thinkingLevel" to "low"), null), c("gemini-3-pro-preview"))
		assertEquals(listOf(mapOf("thinkingBudget" to 0), null), c("gemini-2.5-flash"))
		assertEquals(listOf(mapOf("thinkingBudget" to 0), null), c("gemini-2.5-flash-lite"))
		assertEquals(listOf(mapOf("thinkingBudget" to 128), null), c("gemini-2.5-pro"))
		assertEquals(listOf(null), c("gemini-2.0-flash"))
		assertEquals(listOf(null), c("gpt-5"))
	}

	@Test
	fun modelPresetsFallbacksNeverContainThePrimary() {
		for (p in org.koitharu.kotatsu.translator.domain.model.AiProvider.entries) {
			for (primary in ModelPresets.presets(p).map { it.id }) {
				assertFalse(ModelPresets.fallbackModels(p, primary).contains(primary))
			}
		}
		assertTrue(ModelPresets.fallbackModels(org.koitharu.kotatsu.translator.domain.model.AiProvider.OPENAI, "x").isEmpty())
		assertEquals(
			listOf("claude-sonnet-5-5"),
			ModelPresets.fallbackModels(org.koitharu.kotatsu.translator.domain.model.AiProvider.CLAUDE, "claude-haiku-5-5"),
		)
		assertTrue(ModelPresets.presets(org.koitharu.kotatsu.translator.domain.model.AiProvider.OPENAI).isEmpty())
	}
}
