package org.koitharu.kotatsu.translator.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.koitharu.kotatsu.translator.domain.model.AiException
import kotlin.coroutines.coroutineContext

/**
 * Retry policy for AI requests. Port of `retry.ts`:
 * - [AiException.Kind.RETRYABLE]: up to 4 retries with backoff 2, 5, 10, 20 s (or the server hint, at most 60 s);
 * - [AiException.Kind.BAD_OUTPUT]: one retry, no wait;
 * - AUTH, REJECTED, ABORTED and any other exception: thrown at once.
 *
 * Cancellation of the calling coroutine is honoured before every attempt and while waiting.
 *
 * @param sleep suspends for the given number of milliseconds
 */
class RetryPolicy(
	private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

	suspend fun <T> run(block: suspend () -> T): T {
		var retryableCount = 0
		var badOutputCount = 0
		while (true) {
			coroutineContext.ensureActive()
			try {
				return block()
			} catch (e: AiException) {
				when (e.kind) {
					AiException.Kind.RETRYABLE -> {
						if (retryableCount >= BACKOFF_MS.size) throw e
						val hint = e.retryAfterMs
						val backoff = BACKOFF_MS[retryableCount++]
						sleep(if (hint != null && hint >= 0) minOf(hint, MAX_HINT_MS) else backoff)
					}

					AiException.Kind.BAD_OUTPUT -> {
						if (badOutputCount++ >= 1) throw e
					}

					else -> throw e
				}
			}
		}
	}

	companion object {

		val BACKOFF_MS = longArrayOf(2000L, 5000L, 10000L, 20000L)
		const val MAX_HINT_MS = 60_000L
	}
}
