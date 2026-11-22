package org.koitharu.kotatsu.translator.domain

import org.koitharu.kotatsu.translator.domain.model.AiProvider
import kotlin.math.min

/**
 * Per-model circuit breaker: a failed model is skipped until its cooldown ends. Port of `modelHealth.ts`.
 * Cooldown after the n-th consecutive failure is `30 s * 2^(n-1)`, at most 300 s, unless the server gave a hint.
 * Thread safe.
 *
 * @param clock monotonic time in milliseconds
 */
class ModelHealth(
	private val clock: () -> Long = RateLimiter.DEFAULT_CLOCK,
) {

	private class State(val until: Long, val failures: Int)

	private val lock = Any()
	private val state = HashMap<String, State>()

	/** Marks the model overloaded; the cooldown is [retryAfterMs] when given, else grows with consecutive failures. */
	fun markFailed(provider: AiProvider, model: String, retryAfterMs: Long? = null) {
		val key = key(provider, model)
		synchronized(lock) {
			val failures = (state[key]?.failures ?: 0) + 1
			val cooldown = if (retryAfterMs != null && retryAfterMs >= 0) {
				retryAfterMs
			} else {
				min(COOLDOWN_MAX_MS, COOLDOWN_BASE_MS shl (min(failures, 30) - 1))
			}
			state[key] = State(clock() + cooldown, failures)
		}
	}

	fun markOk(provider: AiProvider, model: String) {
		synchronized(lock) { state.remove(key(provider, model)) }
	}

	fun isHealthy(provider: AiProvider, model: String): Boolean = synchronized(lock) {
		val s = state[key(provider, model)]
		s == null || clock() >= s.until
	}

	/** The first healthy model of [chain] (`[primary, ...fallbacks]`), or the first one if none is healthy. */
	fun pick(provider: AiProvider, chain: List<String>): String =
		chain.firstOrNull { isHealthy(provider, it) } ?: chain.first()

	/** The first healthy model of [chain] other than [model], or null (second leg of a hedged request). */
	fun alternative(provider: AiProvider, chain: List<String>, model: String): String? =
		chain.firstOrNull { it != model && isHealthy(provider, it) }

	private fun key(provider: AiProvider, model: String) = "${provider.name}|$model"

	companion object {

		const val COOLDOWN_BASE_MS = 30_000L
		const val COOLDOWN_MAX_MS = 300_000L
	}
}
