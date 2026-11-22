package org.koitharu.kotatsu.translator.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * Counting semaphore for OCR (1 slot by default) that hands a freed slot to the best waiter:
 * foreground before background, then the series (group) that has waited longest, then the smallest page index.
 * [acquire] takes a lambda to read the priority when a slot frees up, so a waiter can be promoted in place.
 * Port of `createSemaphore` in `orchestrator.ts`. Cancelled waiters leave the queue.
 */
class PrioritySemaphore(slots: Int = 1) {

	private class Waiter(
		val seriesKey: Long,
		val pageIndex: Int,
		val isBackground: () -> Boolean,
	) {

		val gate = CompletableDeferred<Unit>()
	}

	private val lock = Any()
	private val waiters = ArrayList<Waiter>()
	private var free = slots

	init {
		require(slots > 0) { "slots must be positive" }
	}

	/**
	 * Suspends until a slot is held; pair with [release].
	 * @param seriesKey the series (manga); among equal priority the series that waits longest goes first
	 * @param isBackground read when a slot frees up
	 */
	suspend fun acquire(seriesKey: Long, pageIndex: Int, isBackground: () -> Boolean = { false }) {
		val waiter = Waiter(seriesKey, pageIndex, isBackground)
		val granted = synchronized(lock) {
			if (free > 0) {
				free--
				true
			} else {
				waiters.add(waiter)
				false
			}
		}
		if (granted) return
		try {
			waiter.gate.await()
		} catch (e: CancellationException) {
			// not in the queue any more = a slot was already given to this waiter: pass it on
			val wasGranted = synchronized(lock) { !waiters.remove(waiter) }
			if (wasGranted) release()
			throw e
		}
	}

	fun release() {
		val next = synchronized(lock) {
			if (waiters.isEmpty()) {
				free++
				null
			} else {
				// Background waiters only get a slot when no foreground waiter is queued.
				val foreground = waiters.filter { !it.isBackground() }
				val pool = foreground.ifEmpty { waiters }
				val series = pool[0].seriesKey
				var best = pool[0]
				for (w in pool) {
					if (w.seriesKey == series && w.pageIndex < best.pageIndex) best = w
				}
				waiters.remove(best)
				best
			}
		}
		next?.gate?.complete(Unit)
	}

	suspend fun <T> withPermit(
		seriesKey: Long,
		pageIndex: Int,
		isBackground: () -> Boolean = { false },
		block: suspend () -> T,
	): T {
		acquire(seriesKey, pageIndex, isBackground)
		try {
			return block()
		} finally {
			release()
		}
	}
}
