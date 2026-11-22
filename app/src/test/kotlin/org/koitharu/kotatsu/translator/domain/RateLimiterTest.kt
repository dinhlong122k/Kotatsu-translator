package org.koitharu.kotatsu.translator.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

	/** Fake clock: sleep() advances time instantly and records the wait. */
	private class FakeClock {

		var t = 1_000_000L
		val waits = mutableListOf<Long>()

		fun advance(ms: Long) {
			t += ms
		}

		suspend fun sleep(ms: Long) {
			waits.add(ms)
			t += ms
		}
	}

	private fun limiter(c: FakeClock, limit: () -> Int) = RateLimiter(limit, { c.t }, { c.sleep(it) })

	@Test
	fun limit10GivesBurstOf3ThenEvenPacing() = runTest {
		val c = FakeClock()
		val rl = limiter(c) { 10 }
		val start = c.t
		val at = mutableListOf<Long>()
		repeat(10) {
			rl.acquire()
			at.add(c.t - start)
		}
		assertEquals(listOf(0L, 0L, 0L), at.take(3))
		assertEquals(listOf(6_000L, 12_000L, 18_000L, 24_000L, 30_000L, 36_000L, 42_000L), at.drop(3))
		// never more than 10 in any 60 s window
		for (x in at) assertTrue(at.count { it >= x && it < x + 60_000 } <= 10)
	}

	@Test
	fun readyWaitsButDoesNotTakeSlot() = runTest {
		val c = FakeClock()
		val rl = limiter(c) { 2 }
		rl.ready()
		rl.ready()
		assertEquals(emptyList<Long>(), c.waits)
		rl.acquire()
		c.advance(1000)
		rl.acquire()
		rl.ready()
		assertEquals(listOf(59_000L), c.waits)
		rl.acquire() // the oldest slot left the window while ready() waited
		assertEquals(listOf(59_000L), c.waits)
	}

	@Test
	fun limitZeroIsUnlimited() = runTest {
		val c = FakeClock()
		val rl = limiter(c) { 0 }
		repeat(50) { rl.acquire() }
		assertEquals(emptyList<Long>(), c.waits)
	}

	@Test
	fun limitIsClampedTo600() = runTest {
		val c = FakeClock()
		val rl = limiter(c) { 100_000 }
		// interval 100 ms at the clamped limit of 600: the 4th request waits ~100 ms, not ~0.6 ms
		repeat(4) { rl.acquire() }
		assertEquals(listOf(100L), c.waits)
	}

	@Test
	fun limit3FourthWaitsForOldestSlotToLeaveWindow() = runTest {
		val c = FakeClock()
		val rl = limiter(c) { 3 }
		rl.acquire()
		c.advance(1000)
		rl.acquire()
		c.advance(1000)
		rl.acquire()
		assertEquals(emptyList<Long>(), c.waits)
		rl.acquire()
		assertEquals(listOf(58_000L), c.waits) // first slot was taken 2 s ago
	}

	@Test
	fun limitIsReadAgainOnEveryAcquire() = runTest {
		val c = FakeClock()
		var limit = 1
		val rl = limiter(c) { limit }
		rl.acquire()
		limit = 5
		rl.acquire()
		assertEquals(emptyList<Long>(), c.waits)
	}

	@Test
	fun cancelWhileWaitingTakesNoSlot() = runTest {
		val rl = RateLimiter({ 1 }, { testScheduler.currentTime })
		rl.acquire()
		val job = launch { rl.acquire() } // has to wait ~60 s
		runCurrent()
		assertFalse(job.isCompleted)
		job.cancel()
		advanceUntilIdle()
		assertTrue(job.isCancelled)
		delay(60_001)
		val before = testScheduler.currentTime
		rl.acquire() // the cancelled waiter took nothing
		assertEquals(before, testScheduler.currentTime)
	}

	@Test
	fun cancelledWaiterBehindSleepingOneLeavesAndFirstCompletes() = runTest {
		val rl = RateLimiter({ 1 }, { testScheduler.currentTime })
		rl.acquire()
		val first = launch { rl.acquire() }
		runCurrent()
		val second = launch { rl.acquire() } // queued behind the sleeping first
		runCurrent()
		second.cancel()
		runCurrent()
		assertTrue(second.isCancelled)
		advanceUntilIdle()
		assertTrue(first.isCompleted)
		assertFalse(first.isCancelled)
		assertEquals(60_000L, testScheduler.currentTime)
	}
}
