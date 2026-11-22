package org.koitharu.kotatsu.translator.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Sliding window (at most `limit` requests per 60 s) plus GCRA pacing: [BURST] requests back to back, then one
 * request per 60 s / limit. Shared by every series. Port of `rateLimiter.ts`.
 *
 * Cancelling a coroutine suspended in [acquire] or [ready] throws CancellationException and takes no slot.
 *
 * @param limit current limit in requests per minute; 0 or less = unlimited. Read on every acquire / wake-up.
 * @param clock monotonic time in milliseconds
 * @param sleep suspends for the given number of milliseconds
 */
class RateLimiter(
	private val limit: () -> Int,
	private val clock: () -> Long = DEFAULT_CLOCK,
	private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

	private val lock = Any()
	private val stamps = ArrayDeque<Long>()

	// GCRA: theoretical arrival time of the next request at the paced rate.
	private var tat = 0.0
	private var tatLimit = 0

	// Serialises waiters so they take slots in arrival order (Mutex is fair).
	private val queue = Mutex()

	/** Suspends until a request slot is free and takes it. */
	suspend fun acquire() {
		queue.withLock {
			while (true) {
				val lim = effectiveLimit()
				if (lim <= 0) return
				val wait = tryTake(lim, clock())
				if (wait <= 0.0) return
				sleep(max(1L, ceil(wait).toLong()))
			}
		}
	}

	/** Suspends until a slot is free, without taking it (callers then build their request and [acquire]). */
	suspend fun ready() {
		while (true) {
			val lim = effectiveLimit()
			if (lim <= 0) return
			val wait = synchronized(lock) { waitMs(lim, clock()) }
			if (wait <= 0.0) return
			sleep(max(1L, ceil(wait).toLong()))
		}
	}

	private fun effectiveLimit(): Int = limit().coerceIn(MIN_LIMIT, MAX_LIMIT)

	/** Takes a slot if one is free now (returns 0) else returns the milliseconds to wait. */
	private fun tryTake(lim: Int, t: Long): Double = synchronized(lock) {
		val wait = waitMs(lim, t)
		if (wait <= 0.0) {
			stamps.addLast(t)
			tat = max(tat, t.toDouble()) + WINDOW_MS.toDouble() / lim
		}
		wait
	}

	/** Milliseconds until a request may be sent at time t (0 = now). Drops stamps older than the window. */
	private fun waitMs(lim: Int, t: Long): Double {
		while (stamps.isNotEmpty() && stamps.first() <= t - WINDOW_MS) stamps.removeFirst()
		val n = stamps.size
		// The limit may have been lowered: only the newest `lim` stamps matter.
		val window = if (n < lim) 0.0 else (stamps[n - lim] + WINDOW_MS - t).toDouble()
		val interval = WINDOW_MS.toDouble() / lim
		// The limit changed: rescale the pending pacing debt to the new rate.
		if (tatLimit != 0 && tatLimit != lim && tat > t) tat = t + (tat - t) * tatLimit / lim
		tatLimit = lim
		val pace = tat - (min(BURST, lim) - 1) * interval - t
		return max(0.0, max(window, pace))
	}

	companion object {

		private const val WINDOW_MS = 60_000L
		private const val MIN_LIMIT = 0
		private const val MAX_LIMIT = 600

		/** Requests allowed back to back before pacing starts */
		const val BURST = 3

		val DEFAULT_CLOCK: () -> Long = { System.nanoTime() / 1_000_000L }
	}
}
