package org.koitharu.kotatsu.translator.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineGrouperTest {

	private fun line(x: Int, y: Int, w: Int, h: Int, text: String = "t") =
		RecognizedLine(Box(x, y, w, h), text, 0.9)

	// ---- groupLines (horizontal) ----

	@Test
	fun threeStackedCenteredLinesFormOneBlockInOrder() {
		val out = LineGrouper.groupLines(
			listOf(line(150, 120, 60, 20, "c"), line(100, 60, 160, 20, "a"), line(125, 90, 110, 20, "b")),
			500, 500,
		)
		assertEquals(1, out.size)
		assertEquals("a b c", out[0].text)
		assertEquals(0.9, out[0].conf, 1e-9)
		assertTrue(out[0].box.x < 100)
	}

	@Test
	fun sideBySideBubblesAreTwoBlocks() {
		val out = LineGrouper.groupLines(
			listOf(line(20, 100, 100, 20), line(20, 130, 100, 20), line(300, 105, 100, 20), line(300, 135, 100, 20)),
			500, 500,
		)
		assertEquals(2, out.size)
	}

	@Test
	fun tallSfxNextToDialogueIsSeparate() {
		val out = LineGrouper.groupLines(listOf(line(100, 100, 120, 20, "dialog"), line(100, 125, 120, 60, "BOOM")), 500, 500)
		assertEquals(2, out.size)
	}

	@Test
	fun verticallyDistantLinesAreSeparate() {
		assertEquals(2, LineGrouper.groupLines(listOf(line(100, 50, 100, 20), line(100, 300, 100, 20)), 500, 500).size)
	}

	@Test
	fun blockBoxIsClampedToImage() {
		val b = LineGrouper.groupLines(listOf(line(0, 0, 100, 20)), 100, 20)
		assertEquals(Box(0, 0, 100, 20), b[0].box)
	}

	// ---- groupLines (vertical) ----

	@Test
	fun adjacentColumnsFormOneBlockOrderedRightToLeft() {
		val cols = listOf(line(200, 100, 30, 200, "A"), line(160, 100, 30, 200, "B"), line(120, 110, 30, 180, "C"))
		val out = LineGrouper.groupLines(cols, 400, 500)
		assertEquals(1, out.size)
		assertEquals("A B C", out[0].text)
		assertTrue(out[0].isVertical)
	}

	@Test
	fun distantColumnsStaySeparate() {
		assertEquals(
			2,
			LineGrouper.groupLines(listOf(line(300, 100, 30, 200, "A"), line(50, 100, 30, 200, "B")), 400, 500).size,
		)
	}

	@Test
	fun columnsWithoutVerticalOverlapOrVeryDifferentWidthsDoNotMerge() {
		assertEquals(
			2,
			LineGrouper.groupLines(listOf(line(100, 0, 30, 100, "A"), line(100, 200, 30, 100, "B")), 400, 500).size,
		)
		assertEquals(
			2,
			LineGrouper.groupLines(listOf(line(100, 0, 30, 200, "A"), line(140, 0, 80, 200, "B")), 400, 500).size,
		)
	}

	@Test
	fun verticalColumnNeverMergesWithHorizontalDialogue() {
		val out = LineGrouper.groupLines(
			listOf(line(300, 100, 30, 200, "V"), line(100, 150, 190, 30, "H1"), line(100, 185, 190, 30, "H2")),
			400, 500,
		)
		assertEquals(listOf("H1 H2", "V"), out.map { it.text }.sorted())
	}

	@Test
	fun horizontalLinesAreUnchangedStackedLinesJoinTopToBottom() {
		val out = LineGrouper.groupLines(listOf(line(10, 50, 200, 30, "two"), line(10, 10, 200, 30, "one")), 400, 500)
		assertEquals(1, out.size)
		assertEquals("one two", out[0].text)
		assertFalse(out[0].isVertical)
	}

	// ---- sortLines ----

	@Test
	fun sortLinesTopToBottomForHorizontal() {
		val l = listOf(Box(0, 40, 100, 20), Box(0, 0, 100, 20))
		assertEquals(listOf(0.0, 40.0), LineGrouper.sortLines(l) { it }.map { it.y })
	}

	@Test
	fun sortLinesRightToLeftWhenMostAreVertical() {
		val l = listOf(Box(10, 0, 20, 100), Box(90, 0, 20, 100), Box(50, 0, 20, 100), Box(0, 0, 100, 20))
		assertEquals(listOf(90.0, 50.0, 10.0), LineGrouper.sortLines(l) { it }.map { it.x }.take(3))
	}

	// ---- readingOrder ----

	private class Item(val id: String, val box: Box)

	@Test
	fun readingOrderSameRowLeftToRightThenLowerRow() {
		val right = Item("R", Box(100, 0, 50, 20))
		val left = Item("L", Box(0, 5, 50, 20))
		val below = Item("B", Box(0, 50, 50, 20))
		assertEquals(listOf("L", "R", "B"), LineGrouper.readingOrder(listOf(below, right, left)) { it.box }.map { it.id })
	}

	@Test
	fun readingOrderExactHalfOverlapIsADifferentRow() {
		val a = Item("A", Box(100, 0, 50, 20))
		val b = Item("B", Box(0, 10, 50, 20))
		assertEquals(listOf("A", "B"), LineGrouper.readingOrder(listOf(b, a)) { it.box }.map { it.id })
	}

	@Test
	fun readingOrderRtlReversesXInsideRowsButNotRows() {
		val right = Item("R", Box(100, 0, 50, 20))
		val left = Item("L", Box(0, 5, 50, 20))
		val below = Item("B", Box(0, 50, 50, 20))
		val belowRight = Item("BR", Box(100, 52, 50, 20))
		assertEquals(
			listOf("R", "L", "BR", "B"),
			LineGrouper.readingOrder(listOf(below, left, belowRight, right), rtl = true) { it.box }.map { it.id },
		)
	}
}
