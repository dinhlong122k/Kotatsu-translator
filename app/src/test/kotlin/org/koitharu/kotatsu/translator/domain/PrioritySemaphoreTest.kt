package org.koitharu.kotatsu.translator.domain

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrioritySemaphoreTest {

	@Test
	fun foregroundFirstThenSmallestPageIndex() = runTest {
		val sem = PrioritySemaphore(1)
		sem.acquire(1L, 0)
		val order = mutableListOf<String>()
		fun waiter(name: String, index: Int, background: Boolean) = launch {
			sem.acquire(1L, index) { background }
			order.add(name)
			sem.release()
		}
		waiter("bg2", 2, true)
		waiter("fg5", 5, false)
		waiter("fg3", 3, false)
		waiter("bg1", 1, true)
		runCurrent()
		assertTrue(order.isEmpty())
		sem.release()
		advanceUntilIdle()
		assertEquals(listOf("fg3", "fg5", "bg1", "bg2"), order)
	}

	@Test
	fun promotedWaiterCountsAsForeground() = runTest {
		val sem = PrioritySemaphore(1)
		sem.acquire(1L, 0)
		val order = mutableListOf<String>()
		var promoted = false
		launch {
			sem.acquire(1L, 1) { true }
			order.add("a")
			sem.release()
		}
		launch {
			sem.acquire(1L, 9) { !promoted }
			order.add("b")
			sem.release()
		}
		runCurrent()
		promoted = true
		sem.release()
		advanceUntilIdle()
		assertEquals(listOf("b", "a"), order)
	}

	@Test
	fun seriesThatWaitedLongestGoesFirst() = runTest {
		val sem = PrioritySemaphore(1)
		sem.acquire(0L, 0)
		val order = mutableListOf<String>()
		fun waiter(series: Long, index: Int) = launch {
			sem.acquire(series, index)
			order.add("$series:$index")
			sem.release()
		}
		waiter(2L, 9)
		runCurrent()
		waiter(1L, 0)
		runCurrent()
		waiter(2L, 4)
		runCurrent()
		sem.release()
		advanceUntilIdle()
		assertEquals(listOf("2:4", "2:9", "1:0"), order)
	}

	@Test
	fun cancelledWaiterLeavesQueueWithoutLosingSlot() = runTest {
		val sem = PrioritySemaphore(1)
		sem.acquire(1L, 0)
		val job = launch { sem.acquire(1L, 1) }
		runCurrent()
		job.cancel()
		runCurrent()
		sem.release()
		withTimeout(1000) { sem.acquire(1L, 2) } // would time out if the slot was lost
		sem.release()
	}

	@Test
	fun slotGrantedToAlreadyCancelledWaiterIsPassedOn() = runTest {
		val sem = PrioritySemaphore(1)
		sem.acquire(1L, 0)
		val job = launch { sem.acquire(1L, 1) }
		runCurrent()
		sem.release() // the slot is given to the waiter ...
		job.cancel() // ... which is cancelled before it resumes
		runCurrent()
		withTimeout(1000) { sem.acquire(1L, 2) }
		sem.release()
	}

	@Test
	fun withPermitReleasesOnException() = runTest {
		val sem = PrioritySemaphore(1)
		val e = runCatching {
			sem.withPermit(1L, 0) { throw IllegalStateException("boom") }
		}.exceptionOrNull()
		assertTrue(e is IllegalStateException)
		withTimeout(1000) { sem.acquire(1L, 1) }
		sem.release()
	}

	@Test
	fun twoSlotsAllowTwoHolders() = runTest {
		val sem = PrioritySemaphore(2)
		sem.acquire(1L, 0)
		sem.acquire(1L, 1)
		var third = false
		val job = launch {
			sem.acquire(1L, 2)
			third = true
		}
		runCurrent()
		assertTrue(!third)
		sem.release()
		advanceUntilIdle()
		assertTrue(third)
		assertTrue(job.isCompleted)
	}
}
