package org.koitharu.kotatsu.translator.ai

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.koitharu.kotatsu.translator.domain.model.AiException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Pure helpers: classification of HTTP errors and redaction of secrets. Error messages reach the UI and the log,
 * so they stay short and never carry credentials.
 */
internal object AiHttp {

	const val MAX_MESSAGE = 200
	const val REDACTED = "[redacted]"

	private val SECRET_HEADER = Regex("authorization|api-?key|token|secret", RegexOption.IGNORE_CASE)
	private val WHITESPACE = Regex("\\s+")
	private val DURATION = Regex("^\\s*(\\d+(?:\\.\\d+)?)\\s*(ms|s)?\\s*$")
	private val PLAIN_NUMBER = Regex("^\\d+(?:\\.\\d+)?$")

	private val BEARER = Regex("\\bBearer\\s+\\S+", RegexOption.IGNORE_CASE)
	private val SK_KEY = Regex("\\bsk-[A-Za-z0-9_*.-]{4,}")
	private val GOOGLE_KEY = Regex("\\bAIza[A-Za-z0-9_-]{10,}")
	private val KEY_PARAM = Regex("([?&]key=)[^&\\s\"']+", RegexOption.IGNORE_CASE)

	/**
	 * The values of credential headers, plus the bare token of "Bearer <token>". Longest first so a full value
	 * is replaced before any of its parts.
	 */
	fun secretsIn(headers: Map<String, String>): List<String> {
		val out = HashSet<String>()
		for ((name, value) in headers) {
			if (value.isEmpty() || !SECRET_HEADER.containsMatchIn(name)) continue
			out.add(value)
			val token = value.trim().split(WHITESPACE).lastOrNull()
			if (!token.isNullOrEmpty()) out.add(token)
		}
		return out.sortedByDescending { it.length }
	}

	/**
	 * Redacts secrets, collapses whitespace and cuts to at most [max] characters (ellipsis included).
	 */
	fun clean(text: String, secrets: List<String>, max: Int): String {
		var s = text
		for (secret in secrets) {
			if (secret.isNotEmpty()) s = s.replace(secret, REDACTED)
		}
		s = BEARER.replace(s, REDACTED)
		s = SK_KEY.replace(s, REDACTED)
		s = GOOGLE_KEY.replace(s, REDACTED)
		s = KEY_PARAM.replace(s) { it.groupValues[1] + REDACTED }
		s = WHITESPACE.replace(s, " ").trim()
		if (s.length <= max) return s
		var cut = s.substring(0, (max - 1).coerceAtLeast(0))
		if (cut.isNotEmpty() && Character.isHighSurrogate(cut.last())) {
			cut = cut.dropLast(1) // do not split a surrogate pair
		}
		return cut + "…"
	}

	/**
	 * [prefix] + cleaned detail, at most [MAX_MESSAGE] characters in total.
	 */
	fun message(prefix: String, detail: String, secrets: List<String>): String {
		val body = clean(detail, secrets, MAX_MESSAGE - prefix.length)
		return if (body.isNotEmpty()) prefix + body else prefix.trimEnd().removeSuffix(":")
	}

	/**
	 * Copy of [e] with the message redacted (kind, retry hint and partial result are kept).
	 */
	fun cleaned(e: AiException, secrets: List<String>): AiException {
		val msg = clean(e.message.orEmpty(), secrets, MAX_MESSAGE)
		return if (msg == e.message) e else AiException(e.kind, msg, e.retryAfterMs, e.partial, e.httpCode)
	}

	/**
	 * "37s", "1.5s", "500ms" -> milliseconds
	 */
	fun parseDuration(v: Any?): Long? {
		if (v !is String) return null
		val m = DURATION.matchEntire(v) ?: return null
		val n = m.groupValues[1].toDoubleOrNull() ?: return null
		val factor = if (m.groupValues[2] == "ms") 1.0 else 1000.0
		return Math.round(n * factor)
	}

	/**
	 * Retry-After header (delta-seconds or HTTP date) or Gemini's error.details[].retryDelay; null if absent.
	 */
	fun retryHint(retryAfterHeader: String?, json: JSONObject?, now: Long = System.currentTimeMillis()): Long? {
		val h = retryAfterHeader?.trim()
		if (!h.isNullOrEmpty()) {
			if (PLAIN_NUMBER.matches(h)) {
				return h.toDoubleOrNull()?.let { Math.round(it * 1000.0) }
			}
			val t = parseHttpDate(h)
			if (t != null) return (t - now).coerceAtLeast(0L)
		}
		val details = json?.optJSONObject("error")?.optJSONArray("details")
		if (details != null) {
			for (i in 0 until details.length()) {
				val ms = parseDuration(details.optJSONObject(i)?.opt("retryDelay"))
				if (ms != null) return ms
			}
		}
		return null
	}

	private fun parseHttpDate(value: String): Long? = try {
		ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
	} catch (e: Exception) {
		null
	}

	/**
	 * Maps a non-2xx response to [AiException]:
	 * 429 and 5xx are retryable (429/503 carry the server hint), 401/403 and 400 with API_KEY_INVALID are auth
	 * problems, everything else is rejected.
	 */
	fun httpError(
		status: Int,
		rawBody: String,
		retryAfterHeader: String?,
		secrets: List<String>,
		now: Long = System.currentTimeMillis(),
	): AiException {
		var detail = rawBody
		var parsed: JSONObject? = null
		try {
			val j = JSONObject(rawBody)
			parsed = j
			val err = j.opt("error")
			val m = (err as? JSONObject)?.opt("message") ?: j.opt("message")
			if (m is String) detail = m
		} catch (e: JSONException) {
			// not JSON: the raw text is the detail
		}
		val msg = message("HTTP $status: ", detail, secrets)
		if (status == 429 || status >= 500) {
			val hint = if (status == 429 || status == 503) retryHint(retryAfterHeader, parsed, now) else null
			return AiException(AiException.Kind.RETRYABLE, msg, hint, httpCode = status)
		}
		// Only credential problems are AUTH; Gemini reports a bad key as 400 with reason API_KEY_INVALID.
		if (status == 401 || status == 403 || (status == 400 && rawBody.contains("API_KEY_INVALID"))) {
			return AiException(AiException.Kind.AUTH, msg, httpCode = status)
		}
		return AiException(AiException.Kind.REJECTED, msg, httpCode = status)
	}

	/**
	 * Quotes [s] as a JSON string. Does not escape non-ASCII characters (unlike org.json on the JVM).
	 */
	fun jsonQuote(s: String, out: StringBuilder = StringBuilder()): StringBuilder {
		out.append('"')
		for (c in s) {
			when (c) {
				'"' -> out.append("\\\"")
				'\\' -> out.append("\\\\")
				'\n' -> out.append("\\n")
				'\r' -> out.append("\\r")
				'\t' -> out.append("\\t")
				'\b' -> out.append("\\b")
				'\u000C' -> out.append("\\f")
				else -> if (c < ' ') {
					out.append("\\u").append(String.format("%04x", c.code))
				} else {
					out.append(c)
				}
			}
		}
		return out.append('"')
	}
}

/** Strict string accessor: null for missing, null and non-string values (org.json would coerce numbers). */
internal fun JSONObject.strOrNull(name: String): String? = opt(name) as? String

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
