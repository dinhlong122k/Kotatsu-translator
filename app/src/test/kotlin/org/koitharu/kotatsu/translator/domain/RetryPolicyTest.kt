package org.koitharu.kotatsu.translator.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.translator.domain.model.AiException

class RetryPolicyTest {

	private val waits = mutableListOf<Long>()
	private val policy = RetryPolicy { waits.add(it) }

	private class Failing(private val kind: AiException.Kind, private val failTimes: Int) {

		var calls = 0

		suspend fun run(): String {
			calls++
			if (calls <= failTimes) throw AiException(kind, "x")
			return "ok"
		}
	}

	private suspend fun <T> failure(block: suspend () -> T): AiException {
		val e = runCatching { block() }.exceptionOrNull()
		return e as? AiException ?: throw AssertionError("expected AiException but got $e")
	}

	@Test
	fun retryableThreeTimesThenSuccess() = runTest {
		val f = Failing(AiException.Kind.RETRYABLE, 3)
		assertEquals("ok", policy.run { f.run() })
		assertEquals(listOf(2000L, 5000L, 10000L), waits)
		assertEquals(4, f.calls)
	}

	@Test
	fun retryableForeverWaitsFourTimesAndRunsFiveTimes() = runTest {
		val f = Failing(AiException.Kind.RETRYABLE, 99)
		val e = failure { policy.run { f.run() } }
		assertEquals(AiException.Kind.RETRYABLE, e.kind)
		assertEquals(5, f.calls)
		assertEquals(listOf(2000L, 5000L, 10000L, 20000L), waits)
	}

	@Test
	fun retryAfterHintReplacesBackoffAndIsCappedAt60s() = runTest {
		var calls = 0
		val result = policy.run {
			calls++
			when (calls) {
				1 -> throw AiException(AiException.Kind.RETRYABLE, "x", retryAfterMs = 37_000L)
				2 -> throw AiException(AiException.Kind.RETRYABLE, "x", retryAfterMs = 500_000L)
				3 -> throw AiException(AiException.Kind.RETRYABLE, "x") // no hint: backoff step 3 (10 s)
				else -> "ok"
			}
		}
		assertEquals("ok", result)
		// the backoff index still advances on hinted waits
		assertEquals(listOf(37_000L, 60_000L, 10_000L), waits)
	}

	@Test
	fun badOutputRunsExactlyTwice() = runTest {
		val f = Failing(AiException.Kind.BAD_OUTPUT, 99)
		assertEquals(AiException.Kind.BAD_OUTPUT, failure { policy.run { f.run() } }.kind)
		assertEquals(2, f.calls)
		assertTrue(waits.isEmpty())
	}

	@Test
	fun authRejectedAndAbortedAreNotRetried() = runTest {
		for (kind in listOf(AiException.Kind.AUTH, AiException.Kind.REJECTED, AiException.Kind.ABORTED)) {
			val f = Failing(kind, 99)
			assertEquals(kind, failure { policy.run { f.run() } }.kind)
			assertEquals(1, f.calls)
		}
		assertTrue(waits.isEmpty())
	}

	@Test
	fun otherExceptionsPassThrough() = runTest {
		var calls = 0
		val e = runCatching { policy.run<Unit> { calls++; throw IllegalStateException("boom") } }.exceptionOrNull()
		assertTrue(e is IllegalStateException)
		assertEquals(1, calls)
	}

	@Test
	fun cancelDuringWaitStopsRetrying() = runTest {
		lateinit var job: Job
		val cancelling = RetryPolicy { job.cancel() }
		var calls = 0
		var cancelled = false
		job = launch {
			try {
				cancelling.run<String> {
					calls++
					throw AiException(AiException.Kind.RETRYABLE, "x")
				}
			} catch (e: CancellationException) {
				cancelled = true
				throw e
			}
		}
		advanceUntilIdle()
		assertTrue(cancelled)
		assertEquals(1, calls)
	}
}
