package org.koitharu.kotatsu.translator.domain

/**
 * A page waiting for translation, seen by [BatchPlanner]. [pageIndex] is the index inside the chapter.
 */
interface BatchItem {

	val chapterId: Long
	val pageIndex: Int
	val blockCount: Int
	val charCount: Int
	val isBackground: Boolean
}

data class BatchCaps(val pages: Int, val blocks: Int, val chars: Int)

/**
 * Pure part of the batching of `orchestrator.ts`: slow start caps, which pending pages go into the next request
 * and when a second concurrent request may start. Nothing here touches threads or time.
 */
object BatchPlanner {

	const val MAX_BATCH_PAGES = 10
	const val MAX_BATCH_BLOCKS = 80
	const val MAX_BATCH_CHARS = 6000

	/** Concurrent translation requests per series */
	const val MAX_IN_FLIGHT = 2

	/** A request in flight for longer than this no longer holds back the second one */
	const val STALE_MS = 6000L

	/** After this idle time a series starts again from the smallest batch */
	const val IDLE_RESTART_MS = 60_000L

	/** A request that streamed nothing for this long is duplicated (hedging) */
	const val HEDGE_MS = 1500L

	/** A model that lost a hedged race is not used for this long */
	const val SLOW_COOLDOWN_MS = 120_000L

	/** Slow start: the k-th batch (from 0) of a reading burst is smaller than the steady state one. */
	fun capsFor(k: Int): BatchCaps = when {
		k <= 1 -> BatchCaps(2, 24, 2000)
		k == 2 -> BatchCaps(4, 48, 4000)
		k == 3 -> BatchCaps(8, 64, 5000)
		else -> BatchCaps(MAX_BATCH_PAGES, MAX_BATCH_BLOCKS, MAX_BATCH_CHARS)
	}

	/** Background items wait while any foreground item is pending. */
	fun <T : BatchItem> eligible(pending: List<T>): List<T> {
		val foreground = pending.filter { !it.isBackground }
		return foreground.ifEmpty { pending }
	}

	/**
	 * The items to send in the next request, in ascending page order. The chapter with the lowest id wins (then its lowest page);
	 * at least one page is always taken; two items with the same page index never share a batch.
	 * Does not modify [pending].
	 */
	fun <T : BatchItem> take(pending: List<T>, caps: BatchCaps): List<T> {
		val pool = eligible(pending)
		if (pool.isEmpty()) return emptyList()
		val first = pool.minWith(compareBy<T> { it.chapterId }.thenBy { it.pageIndex })
		val candidates = pool.filter { it.chapterId == first.chapterId }.sortedBy { it.pageIndex }
		val taken = ArrayList<T>()
		val pages = HashSet<Int>()
		var blocks = 0
		var chars = 0
		for (e in candidates) {
			if (e.pageIndex in pages) continue
			if (taken.isNotEmpty() && (
					taken.size + 1 > caps.pages ||
						blocks + e.blockCount > caps.blocks ||
						chars + e.charCount > caps.chars
					)
			) {
				break
			}
			taken.add(e)
			pages.add(e.pageIndex)
			blocks += e.blockCount
			chars += e.charCount
		}
		return taken
	}

	/** Distinct pages of the chapter that would be batched next */
	fun <T : BatchItem> nextChapterPages(pending: List<T>): Int {
		val pool = eligible(pending)
		if (pool.isEmpty()) return 0
		val first = pool.minWith(compareBy<T> { it.chapterId }.thenBy { it.pageIndex })
		return pool.filter { it.chapterId == first.chapterId }.mapTo(HashSet()) { it.pageIndex }.size
	}

	/**
	 * Whether the next request has to wait. The first page never waits; a second request may overlap during the
	 * slow start burst (fewer than two batches sent), once a full batch is pending, when the running one is stale, or
	 * when a foreground page waits while every running batch carries only background pages.
	 *
	 * @param foregroundBlockedByBackground a foreground page is pending and every running batch is background only
	 * @param nextChapterPages see [nextChapterPages]
	 */
	fun shouldWait(
		inFlight: Int,
		started: Int,
		hasStale: Boolean,
		foregroundBlockedByBackground: Boolean,
		nextChapterPages: Int,
	): Boolean {
		if (inFlight >= MAX_IN_FLIGHT) return true
		return inFlight == 1 && started >= 2 && !hasStale && !foregroundBlockedByBackground &&
			nextChapterPages < capsFor(started).pages
	}
}
