package org.koitharu.kotatsu.translator.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchPlannerTest {

	private class Item(
		override val chapterId: Long,
		override val pageIndex: Int,
		override val blockCount: Int = 3,
		override val charCount: Int = 30,
		override val isBackground: Boolean = false,
	) : BatchItem

	@Test
	fun slowStartCaps() {
		assertEquals(BatchCaps(2, 24, 2000), BatchPlanner.capsFor(0))
		assertEquals(BatchCaps(2, 24, 2000), BatchPlanner.capsFor(1))
		assertEquals(BatchCaps(4, 48, 4000), BatchPlanner.capsFor(2))
		assertEquals(BatchCaps(8, 64, 5000), BatchPlanner.capsFor(3))
		assertEquals(BatchCaps(10, 80, 6000), BatchPlanner.capsFor(4))
		assertEquals(BatchCaps(10, 80, 6000), BatchPlanner.capsFor(50))
	}

	@Test
	fun takesPagesInAscendingOrderUpToPageCap() {
		val pending = listOf(Item(1, 5), Item(1, 3), Item(1, 4), Item(1, 6))
		assertEquals(listOf(3, 4), BatchPlanner.take(pending, BatchPlanner.capsFor(0)).map { it.pageIndex })
	}

	@Test
	fun alwaysTakesAtLeastOnePage() {
		val pending = listOf(Item(1, 0, blockCount = 100, charCount = 9000))
		assertEquals(1, BatchPlanner.take(pending, BatchPlanner.capsFor(0)).size)
	}

	@Test
	fun blockAndCharCapsStopTheBatch() {
		val byBlocks = listOf(Item(1, 0, blockCount = 20), Item(1, 1, blockCount = 20))
		assertEquals(1, BatchPlanner.take(byBlocks, BatchPlanner.capsFor(0)).size)
		val byChars = listOf(Item(1, 0, charCount = 1500), Item(1, 1, charCount = 600))
		assertEquals(1, BatchPlanner.take(byChars, BatchPlanner.capsFor(0)).size)
		assertEquals(2, BatchPlanner.take(byChars, BatchPlanner.capsFor(2)).size)
	}

	@Test
	fun onlyOneChapterPerBatch() {
		val pending = listOf(Item(2, 0), Item(1, 7), Item(1, 8))
		val taken = BatchPlanner.take(pending, BatchPlanner.capsFor(4))
		assertEquals(listOf(7, 8), taken.map { it.pageIndex })
		assertTrue(taken.all { it.chapterId == 1L })
	}

	@Test
	fun samePageIndexDoesNotShareABatch() {
		val pending = listOf(Item(1, 2), Item(1, 2), Item(1, 3))
		assertEquals(listOf(2, 3), BatchPlanner.take(pending, BatchPlanner.capsFor(4)).map { it.pageIndex })
	}

	@Test
	fun backgroundWaitsForForeground() {
		val pending = listOf(Item(1, 0, isBackground = true), Item(1, 5), Item(1, 6, isBackground = true))
		assertEquals(listOf(5), BatchPlanner.take(pending, BatchPlanner.capsFor(4)).map { it.pageIndex })
		val onlyBackground = listOf(Item(1, 0, isBackground = true), Item(1, 1, isBackground = true))
		assertEquals(2, BatchPlanner.take(onlyBackground, BatchPlanner.capsFor(4)).size)
	}

	@Test
	fun emptyPending() {
		assertTrue(BatchPlanner.take(emptyList<Item>(), BatchPlanner.capsFor(0)).isEmpty())
		assertEquals(0, BatchPlanner.nextChapterPages(emptyList<Item>()))
	}

	@Test
	fun nextChapterPagesCountsDistinctPages() {
		val pending = listOf(Item(1, 1), Item(1, 1), Item(1, 2), Item(2, 0))
		assertEquals(2, BatchPlanner.nextChapterPages(pending))
	}

	@Test
	fun waitRules() {
		// two requests are the maximum
		assertTrue(BatchPlanner.shouldWait(2, 0, false, false, 100))
		// first page never waits
		assertFalse(BatchPlanner.shouldWait(0, 0, false, false, 0))
		// slow start: a second request overlaps
		assertFalse(BatchPlanner.shouldWait(1, 1, false, false, 1))
		// steady state: wait for a full batch
		assertTrue(BatchPlanner.shouldWait(1, 4, false, false, 3))
		assertFalse(BatchPlanner.shouldWait(1, 4, false, false, 10))
		// stale request does not block
		assertFalse(BatchPlanner.shouldWait(1, 4, true, false, 3))
		// foreground behind a background-only request does not wait
		assertFalse(BatchPlanner.shouldWait(1, 4, false, true, 3))
	}

	@Test
	fun resolveSourceLanguage() {
		assertEquals("ja", TranslatorLanguages.resolveSource("ja", "ko", "vi"))
		assertEquals("ko", TranslatorLanguages.resolveSource(null, "ko", "vi"))
		assertEquals("ko", TranslatorLanguages.resolveSource(null, null, "vi"))
		assertEquals("ko", TranslatorLanguages.resolveSource(null, "xx", "vi"))
		assertNull(TranslatorLanguages.resolveSource(null, "en", "en"))
		assertNull(TranslatorLanguages.resolveSource("vi", "ko", "vi"))
	}
}
