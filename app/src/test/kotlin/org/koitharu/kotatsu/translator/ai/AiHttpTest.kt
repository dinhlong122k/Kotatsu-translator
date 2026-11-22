package org.koitharu.kotatsu.translator.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.translator.domain.model.AiException
import org.koitharu.kotatsu.translator.domain.model.AiException.Kind
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class AiHttpTest {

	private val noSecrets = emptyList<String>()

	private fun err(status: Int, body: String = "{}", retryAfter: String? = null, secrets: List<String> = noSecrets, now: Long = 0L): AiException =
		AiHttp.httpError(status, body, retryAfter, secrets, now)

	@Test
	fun statusClassification() {
		assertEquals(Kind.AUTH, err(401).kind)
		assertEquals(Kind.AUTH, err(403).kind)
		assertEquals(Kind.REJECTED, err(400).kind)
		assertEquals(Kind.REJECTED, err(404).kind)
		assertEquals(Kind.RETRYABLE, err(429).kind)
		assertEquals(Kind.RETRYABLE, err(500).kind)
		assertEquals(Kind.RETRYABLE, err(503).kind)
	}

	@Test
	fun geminiInvalidKeyIs400ButAuth() {
		val body = """{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}"""
		assertEquals(Kind.AUTH, err(400, body).kind)
		assertEquals(Kind.REJECTED, err(400, """{"error":{"message":"model not found"}}""").kind)
	}

	@Test
	fun messageUsesErrorMessageFieldAndStatusPrefix() {
		val e = err(404, """{"error":{"message":"The model does not exist"}}""")
		assertEquals("HTTP 404: The model does not exist", e.message)
		assertEquals("HTTP 400: top level", err(400, """{"message":"top level"}""").message)
	}

	@Test
	fun nonJsonBodyIsCutTo200Characters() {
		val html = "<html>" + "x".repeat(1000) + "</html>"
		val e = err(502, html)
		assertTrue(e.message!!.length <= 200)
		assertTrue(e.message!!.endsWith("…"))
		assertEquals("HTTP 500", err(500, "").message)
	}

	@Test
	fun keyIsNeverInTheMessage() {
		val secret = "sk-proj-SECRETKEY0123456789abcdefXYZ"
		val e = err(404, """{"error":{"message":"bad $secret ${"x".repeat(500)}"}}""", secrets = AiHttp.secretsIn(mapOf("Authorization" to "Bearer $secret")))
		assertFalse(e.message!!.contains(secret))
		assertFalse(e.message!!.contains("SECRETKEY"))
		assertTrue(e.message!!.length <= 200)
	}

	@Test
	fun echoedPartialKeyAndPatternsAreRedacted() {
		val e = err(401, """{"error":{"message":"Incorrect API key provided: sk-proj-****abcd. Bearer abc.def-ghi; AIzaSyLEAKEDLEAKED12345; url?key=SECRET123&x=1"}}""")
		val m = e.message!!
		assertFalse(m.contains("abcd"))
		assertFalse(m.contains("abc.def"))
		assertFalse(m.contains("LEAKED"))
		assertFalse(m.contains("SECRET123"))
		assertTrue(m.contains("?key=" + AiHttp.REDACTED))
		assertTrue(m.contains("&x=1"))
	}

	@Test
	fun shortUnusualKeyIsRedactedByHeaderValue() {
		val secrets = AiHttp.secretsIn(mapOf("x-api-key" to "abc123", "content-type" to "application/json"))
		assertEquals(listOf("abc123"), secrets)
		assertEquals("oops [redacted] here", AiHttp.clean("oops abc123 here", secrets, 200))
		val bearer = AiHttp.secretsIn(mapOf("Authorization" to "Bearer abc123"))
		assertEquals(listOf("Bearer abc123", "abc123"), bearer)
		assertEquals("[redacted]", AiHttp.clean("Bearer abc123", bearer, 200))
	}

	@Test
	fun ordinaryTextIsNotRedactedAndAstralCharactersAreNotSplit() {
		assertEquals("Hello world, the sky is blue", AiHttp.clean("Hello   world, the sky\nis blue", noSecrets, 200))
		val text = "x".repeat(198) + "😀" + "yyy"
		val cut = AiHttp.clean(text, noSecrets, 200)
		assertTrue(cut.length <= 200)
		for (i in cut.indices) {
			if (Character.isHighSurrogate(cut[i])) assertTrue(i + 1 < cut.length && Character.isLowSurrogate(cut[i + 1]))
			if (Character.isLowSurrogate(cut[i])) assertTrue(i > 0 && Character.isHighSurrogate(cut[i - 1]))
		}
	}

	@Test
	fun cleanedKeepsKindAndRetryHint() {
		val e = AiException(Kind.RETRYABLE, "bad key AIzaSyLEAKEDLEAKED12345", retryAfterMs = 5)
		val c = AiHttp.cleaned(e, noSecrets)
		assertEquals(Kind.RETRYABLE, c.kind)
		assertEquals(5L, c.retryAfterMs)
		assertFalse(c.message!!.contains("LEAKED"))
	}

	@Test
	fun retryAfterSecondsAndGeminiRetryDelay() {
		assertEquals(7000L, err(429, "x", "7").retryAfterMs)
		assertEquals(1500L, err(429, "x", "1.5").retryAfterMs)
		val body37 = """{"error":{"message":"quota","details":[{"@type":"x"},{"retryDelay":"37s"}]}}"""
		assertEquals(37000L, err(429, body37).retryAfterMs)
		assertEquals(1500L, err(429, """{"error":{"details":[{"retryDelay":"1.5s"}]}}""").retryAfterMs)
		assertEquals(500L, err(503, """{"error":{"details":[{"retryDelay":"500ms"}]}}""").retryAfterMs)
	}

	@Test
	fun retryAfterHttpDate() {
		val date = "Wed, 21 Oct 2015 07:28:00 GMT"
		val t = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
		assertEquals(5000L, err(503, "x", date, now = t - 5000).retryAfterMs)
		assertEquals(0L, err(503, "x", date, now = t + 5000).retryAfterMs)
	}

	@Test
	fun noHintWhenAbsentOrNotApplicable() {
		assertNull(err(429).retryAfterMs)
		assertNull(err(429, "x", "garbage").retryAfterMs)
		assertNull(err(500, "x", "7").retryAfterMs)
		assertNull(err(401, "x", "7").retryAfterMs)
	}

	@Test
	fun parseDuration() {
		assertEquals(37000L, AiHttp.parseDuration("37s"))
		assertEquals(1500L, AiHttp.parseDuration("1.5s"))
		assertEquals(500L, AiHttp.parseDuration("500ms"))
		assertEquals(2000L, AiHttp.parseDuration("2"))
		assertNull(AiHttp.parseDuration("soon"))
		assertNull(AiHttp.parseDuration(5))
		assertNull(AiHttp.parseDuration(null))
	}

	@Test
	fun jsonQuoteEscapes() {
		assertEquals("\"a\\\"b\\\\c\\n\\t\\u0001é안\"", AiHttp.jsonQuote("a\"b\\c\n\t\u0001é안").toString())
	}
}
