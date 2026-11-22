package org.koitharu.kotatsu.translator.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PagePlannerTest {

	private fun s(vararg n: Int) = n.toSet()

	@Test
	fun planFromStartTakesEightPages() {
		assertEquals(PagePlan((0..7).toList(), emptyList()), PagePlanner.planJobs(0, 20, s(), s()))
	}

	@Test
	fun planNearEndTakesOnlyExistingPages() {
		assertEquals(listOf(8, 9), PagePlanner.planJobs(8, 10, s(), s()).start)
	}

	@Test
	fun pagesBehindTheReaderAreCancelled() {
		assertEquals(listOf(2, 3, 9, 15), PagePlanner.planJobs(20, 40, s(2, 3, 9, 15), s()).cancel)
	}

	@Test
	fun pageJustAboveVisibleIsKept() {
		assertEquals(listOf(18), PagePlanner.planJobs(20, 40, s(18, 19, 20, 25), s()).cancel)
	}

	@Test
	fun doneAndActivePagesAreNotStarted() {
		assertEquals(listOf(0, 2), PagePlanner.planJobs(0, 30, s(), s(1), 3, 5).start)
		assertEquals(listOf(25), PagePlanner.planJobs(10, 40, s(15, 16, 25), s(), 6, 6).cancel)
		assertEquals(listOf(3, 4, 5, 6, 7), PagePlanner.planJobs(0, 10, s(1), s(0, 2)).start)
	}

	@Test
	fun cancelDistanceBoundaryIsInclusive() {
		assertEquals(listOf(31, 40), PagePlanner.planJobs(20, 50, s(30, 31, 40), s()).cancel)
	}

	@Test
	fun lookaheadForClampsTo6to14() {
		assertEquals(14, PagePlanner.lookaheadFor(1.0))
		assertEquals(10, PagePlanner.lookaheadFor(3.0))
		assertEquals(6, PagePlanner.lookaheadFor(5.0))
		assertEquals(6, PagePlanner.lookaheadFor(60.0))
		assertEquals(14, PagePlanner.lookaheadFor(0.5))
		assertEquals(14, PagePlanner.lookaheadFor(0.0))
		assertEquals(6, PagePlanner.lookaheadFor(Double.POSITIVE_INFINITY))
	}

	@Test
	fun lookaheadRoundsHalfUp() {
		assertEquals(8, PagePlanner.lookaheadFor(30.0 / 7.5)) // 7.5 -> 8
	}

	// ---------- stateful planner with a fake clock ----------

	private var now = 0L

	private fun planner() = PagePlanner { now }

	/** Moves the first visible page to [to] after [sec] seconds */
	private fun PagePlanner.go(to: Int, sec: Double, total: Int): PagePlan {
		now += (sec * 1000).toLong()
		return update(to, total)
	}

	@Test
	fun lookaheadStaysEightUntilTwoPaceSamples() {
		val p = planner()
		assertEquals(8, p.go(0, 0.0, 60).start.size)
		assertEquals(listOf(8), p.go(1, 1.0, 60).start) // one sample: still the default window
	}

	@Test
	fun fastReadersGetWindowOf14AndSlowReadersGet6() {
		now = 0
		val fast = planner()
		fast.go(0, 0.0, 80)
		fast.go(1, 1.0, 80)
		val plan = fast.go(2, 1.0, 80)
		assertEquals(listOf(9, 10, 11, 12, 13, 14, 15), plan.start) // window now 14: pages 2..15
		assertEquals(listOf(0), plan.cancel)

		now = 0
		val slow = planner()
		slow.go(0, 0.0, 80)
		slow.go(1, 10.0, 80)
		slow.go(2, 10.0, 80)
		assertEquals(emptyList<Int>(), slow.go(3, 10.0, 80).start) // window shrank to 6 (3..8), all requested
	}

	@Test
	fun markDoneNeverRestartsAndMarkFailedAllowsRetry() {
		val p = planner()
		assertEquals((0..7).toList(), p.update(0, 10).start)
		p.markDone(0)
		p.markFailed(1)
		assertEquals(PagePlan(listOf(1), emptyList()), p.update(0, 10))
	}

	@Test
	fun resetForgetsDonePageSoItIsPlannedAgain() {
		val p = planner()
		p.update(0, 3)
		p.markDone(0)
		p.markDone(1)
		p.markDone(2)
		assertEquals(emptyList<Int>(), p.update(0, 3).start)
		p.reset(0)
		assertEquals(listOf(0), p.update(0, 3).start)
	}

	@Test
	fun movingFarCancelsAndStartsNewWindow() {
		val p = planner()
		p.update(0, 30)
		val plan = p.update(9, 30)
		assertEquals((0..7).toList(), plan.cancel)
		assertEquals((9..16).toList(), plan.start)
	}

	// ---------- ReadingPace ----------

	@Test
	fun paceIgnoresJumpsBackAndClampsSample() {
		now = 0
		val pace = ReadingPace { now }
		pace.track(10)
		now += 100_000
		pace.track(4) // jump back: no sample
		assertEquals(0, pace.samples)
		now += 10
		pace.track(5) // 0.01 s -> clamped to 0.5
		assertEquals(0.5, pace.secondsPerPage, 1e-9)
		now += 10
		pace.track(6)
		assertEquals(2, pace.samples)
		assertEquals(14, pace.lookahead)
	}

	@Test
	fun paceCountsJumpOfNPagesAsNPagesAndSmoothsWithEwma() {
		now = 0
		val pace = ReadingPace { now }
		pace.track(0)
		now += 20_000
		pace.track(10) // 2 s/page
		assertEquals(2.0, pace.secondsPerPage, 1e-9)
		assertEquals(PagePlanner.DEFAULT_LOOKAHEAD, pace.lookahead) // one sample only
		now += 1_000
		pace.track(11) // 1 s/page: 0.3 * 1 + 0.7 * 2
		assertEquals(1.7, pace.secondsPerPage, 1e-9)
		assertEquals(PagePlanner.lookaheadFor(1.7), pace.lookahead)
		assertTrue(pace.lookahead in 6..14)
	}

	@Test
	fun sameVisiblePageTakesNoSample() {
		now = 0
		val pace = ReadingPace { now }
		pace.track(3)
		now += 5_000
		pace.track(3)
		assertEquals(0, pace.samples)
	}
}
