package org.koitharu.kotatsu.translator.ai

import org.koitharu.kotatsu.translator.domain.model.AiException
import java.net.URLEncoder

/**
 * At most one warm-up GET per provider and model in the interval (a warm-up only opens the TLS connection).
 */
internal class WarmThrottle(private val intervalMs: Long = WARM_INTERVAL_MS) {

	private val last = HashMap<String, Long>()

	@Synchronized
	fun tryAcquire(id: String, now: Long = System.currentTimeMillis()): Boolean {
		val prev = last[id]
		if (prev != null && now - prev < intervalMs) return false
		last[id] = now
		return true
	}

	companion object {

		const val WARM_INTERVAL_MS = 60_000L
		const val WARM_TIMEOUT_MS = 8_000L

		val SHARED = WarmThrottle()
	}
}

internal const val TEST_TIMEOUT_MS = 15_000L

/** Cut of the message of a provider error event, same as the 200 characters limit of HTTP errors. */
internal fun eventMessage(raw: Any?, fallback: String): String =
	(raw as? String ?: fallback).take(AiHttp.MAX_MESSAGE)

internal fun encodePathSegment(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

internal fun missingKey(provider: String) = AiException(AiException.Kind.AUTH, "Missing API key for $provider")

internal fun badOutput(message: String) =
	AiException(AiException.Kind.BAD_OUTPUT, message, partial = TranslateResultParser.emptyResult())

internal fun List<AiModelInfo>.normalized(): List<AiModelInfo> =
	filter { it.id.isNotEmpty() }.distinctBy { it.id }.sortedBy { it.id }
