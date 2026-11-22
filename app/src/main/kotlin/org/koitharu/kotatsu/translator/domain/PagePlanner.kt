package org.koitharu.kotatsu.translator.domain

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Page indices to start and to cancel, both ascending for [cancel]. */
data class PagePlan(
	val start: List<Int>,
	val cancel: List<Int>,
)

/**
 * Seconds-per-page estimate of the reader: EWMA over forward moves of the first visible page.
 * Not thread safe; use from one thread. Port of the pace tracking in `pageQueue.ts`.
 *
 * @param clock monotonic time in milliseconds
 */
class ReadingPace(
	private val clock: () -> Long = RateLimiter.DEFAULT_CLOCK,
) {

	private var lastVisible = -1
	private var lastAt = 0L
	private var pace = 0.0

	/** Number of samples taken so far */
	var samples = 0
		private set

	/** Smoothed seconds per page; meaningful only when [samples] > 0 */
	val secondsPerPage: Double
		get() = pace

	/** Pages to keep translated ahead of the reader; the default until [MIN_SAMPLES] samples exist. */
	val lookahead: Int
		get() = if (samples < MIN_SAMPLES) PagePlanner.DEFAULT_LOOKAHEAD else PagePlanner.lookaheadFor(pace)

	/** Reports the index of the first visible page. Jumps back and repeats take no sample. */
	fun track(visible: Int) {
		val t = clock()
		if (lastVisible < 0 || visible < lastVisible) {
			lastVisible = visible
			lastAt = t
			return
		}
		if (visible == lastVisible) return
		val sample = ((t - lastAt) / 1000.0 / (visible - lastVisible)).coerceIn(MIN_SAMPLE_SEC, MAX_SAMPLE_SEC)
		pace = if (samples == 0) sample else ALPHA * sample + (1 - ALPHA) * pace
		samples++
		lastVisible = visible
		lastAt = t
	}

	fun reset() {
		lastVisible = -1
		lastAt = 0L
		pace = 0.0
		samples = 0
	}

	companion object {

		const val ALPHA = 0.3
		const val MIN_SAMPLE_SEC = 0.5
		const val MAX_SAMPLE_SEC = 60.0
		const val MIN_SAMPLES = 2
	}
}

/**
 * Decides which pages of a chapter to translate from the first visible page, the page count and the reading pace.
 * One instance per chapter. Synchronized, so it may be called from any thread.
 *
 * [update] returns the plan and marks the pages to start as active; the caller reports their outcome with
 * [markDone] / [markFailed], and [reset] to plan a page again.
 */
class PagePlanner(
	clock: () -> Long = RateLimiter.DEFAULT_CLOCK,
) {

	private val pace = ReadingPace(clock)
	private val active = HashSet<Int>()
	private val done = HashSet<Int>()

	@Synchronized
	fun update(visible: Int, total: Int): PagePlan {
		pace.track(visible)
		val lookahead = pace.lookahead
		val plan = planJobs(visible, total, active, done, lookahead, lookahead + 3)
		active.removeAll(plan.cancel.toSet())
		active.addAll(plan.start)
		return plan
	}

	@Synchronized
	fun markDone(index: Int) {
		active.remove(index)
		done.add(index)
	}

	@Synchronized
	fun markFailed(index: Int) {
		active.remove(index)
	}

	/** Forgets the state of the page so that the next [update] plans it again. */
	@Synchronized
	fun reset(index: Int) {
		active.remove(index)
		done.remove(index)
	}

	@Synchronized
	fun clear() {
		active.clear()
		done.clear()
		pace.reset()
	}

	companion object {

		const val DEFAULT_LOOKAHEAD = 8
		const val DEFAULT_CANCEL_DISTANCE = 10
		const val MIN_LOOKAHEAD = 6
		const val MAX_LOOKAHEAD = 14

		/** Seconds of reading to keep translated ahead of the reader */
		const val TARGET_AHEAD_SECONDS = 30.0

		/** Pages ahead to translate for a reading pace (seconds per page), clamped to 6..14 */
		fun lookaheadFor(secPerPage: Double): Int {
			if (!(secPerPage > 0.0)) return MAX_LOOKAHEAD
			val n = floor(TARGET_AHEAD_SECONDS / secPerPage + 0.5)
			return min(MAX_LOOKAHEAD.toDouble(), max(MIN_LOOKAHEAD.toDouble(), n)).toInt()
		}

		fun planJobs(
			visible: Int,
			total: Int,
			active: Set<Int>,
			done: Set<Int>,
			lookahead: Int = DEFAULT_LOOKAHEAD,
			cancelDistance: Int = DEFAULT_CANCEL_DISTANCE,
		): PagePlan {
			val start = ArrayList<Int>()
			var i = visible
			while (i < visible + lookahead && i < total) {
				if (i !in active && i !in done) start.add(i)
				i++
			}
			// scrolled past (the page just above the visible one is kept), or far away
			val cancel = active
				.filter { it < visible - 1 || abs(it - visible) > cancelDistance }
				.sorted()
			return PagePlan(start, cancel)
		}
	}
}
