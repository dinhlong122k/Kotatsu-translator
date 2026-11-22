package org.koitharu.kotatsu.translator.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetPostProcessorTest {

	// ---- tileRects ----

	@Test
	fun tileRectsSingleTileWhenNotTaller() {
		assertEquals(listOf(Box(0, 0, 690, 400)), DetPostProcessor.tileRects(690, 400))
		assertEquals(listOf(Box(0, 0, 690, 690)), DetPostProcessor.tileRects(690, 690))
	}

	@Test
	fun tileRectsTwoTilesWhenOnePixelTaller() {
		assertEquals(
			listOf(Box(0, 0, 690, 690), Box(0, 1, 690, 690)),
			DetPostProcessor.tileRects(690, 691),
		)
	}

	@Test
	fun tileRectsLongPageOverlapsAndCoversEverything() {
		val t = DetPostProcessor.tileRects(690, 15000)
		assertEquals(Box(0, 0, 690, 690), t[0])
		assertEquals(587.0, t[1].y, 0.0)
		assertEquals(Box(0, 14310, 690, 690), t.last())
		for (i in 1 until t.size) {
			assertTrue(t[i].y - t[i - 1].y <= 587)
			assertTrue(t[i].y > t[i - 1].y)
		}
		assertTrue(t.all { it.bottom <= 15000 })
		assertEquals(26, t.size)
	}

	// ---- iou / mergeDetections ----

	@Test
	fun iouIdenticalAndDisjoint() {
		val a = Box(0, 0, 10, 10)
		assertEquals(1.0, DetPostProcessor.iou(a, a), 0.0)
		assertEquals(0.0, DetPostProcessor.iou(a, Box(20, 20, 5, 5)), 0.0)
	}

	@Test
	fun iouContained() {
		assertEquals(0.3, DetPostProcessor.iou(Box(0, 0, 10, 10), Box(0, 0, 3, 10)), 1e-6)
	}

	private val detA = Det(Box(0, 0, 10, 10), 0.8f, 0)

	@Test
	fun mergeDetectionsUnionsOverlappingKeepsBetterScoreAndLabel() {
		val b = Det(Box(2.5, 0.0, 10.0, 10.0), 0.9f, 1)
		assertEquals(0.6, DetPostProcessor.iou(detA.box, b.box), 1e-6)
		assertEquals(
			listOf(Det(Box(0.0, 0.0, 12.5, 10.0), 0.9f, 1)),
			DetPostProcessor.mergeDetections(listOf(detA, b)),
		)
	}

	@Test
	fun mergeDetectionsKeepsLowIouSeparate() {
		val c = Det(Box(0, 0, 3, 10), 0.7f, 2)
		val r = DetPostProcessor.mergeDetections(listOf(detA, c))
		assertEquals(2, r.size)
		assertTrue(detA in r)
		assertTrue(c in r)
	}

	@Test
	fun mergeDetectionsCustomThreshold() {
		val c = Det(Box(0, 0, 3, 10), 0.7f, 2)
		assertEquals(
			listOf(Det(detA.box, 0.8f, 0)),
			DetPostProcessor.mergeDetections(listOf(detA, c), 0.2),
		)
	}

	// ---- extractBoxes ----

	private fun prob(w: Int, h: Int, vararg rects: IntArray): FloatArray {
		val p = FloatArray(w * h)
		for (r in rects) {
			for (y in r[1] until r[1] + r[3]) for (x in r[0] until r[0] + r[2]) p[y * w + x] = 0.9f
		}
		return p
	}

	@Test
	fun extractBoxesGrowsAndScalesOneBlock() {
		// d = 200*1.6/(2*30) = 5.3333 ; x0=(10-d)*2=9.33 -> 9 ; y0=(20-d)*2=29.33 -> 29 ; w=round(59.33)=59, h=round(39.33)=39
		val r = DetPostProcessor.extractBoxes(prob(64, 64, intArrayOf(10, 20, 20, 10)), 64, 64, 2.0, 2.0)
		assertEquals(listOf(Box(9, 29, 59, 39)), r)
	}

	@Test
	fun extractBoxesDropsSmallComponents() {
		assertTrue(DetPostProcessor.extractBoxes(prob(64, 64, intArrayOf(5, 5, 5, 5)), 64, 64, 1.0, 1.0).isEmpty())
	}

	@Test
	fun extractBoxesDropsTooLowBlockEvenWithEnoughPixels() {
		assertTrue(DetPostProcessor.extractBoxes(prob(64, 64, intArrayOf(5, 5, 40, 5)), 64, 64, 1.0, 1.0).isEmpty())
	}

	@Test
	fun extractBoxesDropsHollowBlockUnderThirtyPixels() {
		val p = prob(64, 64, intArrayOf(5, 5, 6, 6))
		for (y in 6 until 10) for (x in 6 until 10) p[y * 64 + x] = 0f
		assertTrue(DetPostProcessor.extractBoxes(p, 64, 64, 1.0, 1.0).isEmpty())
	}

	@Test
	fun extractBoxesClampsToImage() {
		// 8x8 block in the corner: d = 64*1.6/32 = 3.2 -> x0 clamped to 0, x1 = 7+3.2 = 10.2 -> w = 10
		val r = DetPostProcessor.extractBoxes(prob(32, 32, intArrayOf(0, 0, 8, 8)), 32, 32, 1.0, 1.0)
		assertEquals(listOf(Box(0, 0, 10, 10)), r)
	}

	@Test
	fun extractBoxesDoesNotWrapAroundTheEdges() {
		val p = FloatArray(32 * 32)
		for (y in 0 until 8) for (x in 24 until 32) p[y * 32 + x] = 0.9f
		for (y in 8 until 16) for (x in 0 until 8) p[y * 32 + x] = 0.9f
		assertEquals(2, DetPostProcessor.extractBoxes(p, 32, 32, 1.0, 1.0).size)
	}

	@Test
	fun extractBoxesSortedByYThenX() {
		val r = DetPostProcessor.extractBoxes(
			prob(64, 64, intArrayOf(30, 30, 8, 8), intArrayOf(2, 2, 8, 8)), 64, 64, 1.0, 1.0,
		)
		assertEquals(listOf(0.0, 27.0), r.map { it.y })
	}

	// ---- detTiles ----

	@Test
	fun detTilesShortPageIsOneTile() {
		assertEquals(listOf(Box(0, 0, 690, 800)), DetPostProcessor.detTiles(690, 800))
	}

	@Test
	fun detTilesWidePageIsScaledAndNeverUpscaled() {
		assertEquals(listOf(Box(0, 0, 1920, 1000)), DetPostProcessor.detTiles(1920, 1000))
		val t = DetPostProcessor.detTiles(1920, 5000)
		assertEquals(1920.0, t[0].h, 0.0)
		assertTrue(t.all { it.w == 1920.0 })
	}

	@Test
	fun detTilesOverlapAtLeast128AndLastIsBottomAligned() {
		for ((w, h) in listOf(690 to 1600, 690 to 10000, 1920 to 7000, 960 to 961)) {
			val scale = if (w > 960) 960.0 / w else 1.0
			val t = DetPostProcessor.detTiles(w, h)
			assertEquals(0.0, t[0].y, 0.0)
			assertEquals(h.toDouble(), t.last().bottom, 0.0)
			for (i in 1 until t.size) {
				val overlap = (t[i - 1].bottom - t[i].y) * scale
				assertTrue("overlap $overlap for ${w}x$h", overlap >= 128 - 1e-6)
			}
		}
	}

	// ---- mergeTileLines ----

	private val tiles = listOf(Box(0, 0, 500, 960), Box(0, 640, 500, 960))

	@Test
	fun mergeTileLinesDropsBoxesTouchingInteriorCutsOnly() {
		val out = DetPostProcessor.mergeTileLines(
			listOf(
				TileLines(tiles[0], listOf(Box(10, 0, 100, 20), Box(10, 940, 100, 20))),
				TileLines(tiles[1], listOf(Box(10, 640, 100, 20), Box(10, 1580, 100, 20))),
			),
			1600,
		)
		assertEquals(listOf(Box(10, 0, 100, 20), Box(10, 1580, 100, 20)), out)
	}

	@Test
	fun mergeTileLinesUnionsDuplicatesBetweenTiles() {
		val out = DetPostProcessor.mergeTileLines(
			listOf(
				TileLines(tiles[0], listOf(Box(10, 700, 100, 20))),
				TileLines(tiles[1], listOf(Box(12, 702, 100, 20), Box(10, 900, 100, 20))),
			),
			1600,
		)
		assertEquals(2, out.size)
		assertEquals(Box(10, 700, 102, 22), out.first { it.y < 800 })
	}

	@Test
	fun mergeTileLinesUnionsContainedBoxes() {
		val out = DetPostProcessor.mergeTileLines(
			listOf(TileLines(tiles[0], listOf(Box(0, 100, 200, 40), Box(10, 105, 50, 30)))),
			960,
		)
		assertEquals(listOf(Box(0, 100, 200, 40)), out)
	}

	// ---- separateLines ----

	@Test
	fun separateLinesCutsDenseOverlapsAtMidpoint() {
		// pitch 17 px, boxes 20-22 px tall (translator note)
		val out = DetPostProcessor.separateLines(
			listOf(Box(381, 855, 427, 20), Box(376, 873, 437, 20), Box(380, 890, 429, 20)),
		)
		assertEquals(
			listOf(
				Box(381, 855, 427, 19), // cut at (875 + 873) / 2 = 874
				Box(376, 874, 437, 18), // bottom cut at round((893 + 890) / 2) = 892
				Box(380, 892, 429, 18),
			),
			out,
		)
	}

	@Test
	fun separateLinesKeepsNonOverlappingLines() {
		val boxes = listOf(Box(100, 100, 200, 24), Box(110, 126, 180, 24))
		assertEquals(boxes, DetPostProcessor.separateLines(boxes))
	}

	@Test
	fun separateLinesKeepsSideBySideBubbles() {
		val boxes = listOf(Box(0, 100, 100, 24), Box(150, 110, 100, 24))
		assertEquals(boxes, DetPostProcessor.separateLines(boxes))
	}

	@Test
	fun separateLinesKeepsDuplicatesOfOneLine() {
		val boxes = listOf(Box(0, 100, 300, 24), Box(50, 110, 300, 24))
		assertEquals(boxes, DetPostProcessor.separateLines(boxes))
	}

	@Test
	fun separateLinesKeepsVerticalColumns() {
		val boxes = listOf(Box(100, 100, 30, 200), Box(100, 290, 30, 200))
		assertEquals(boxes, DetPostProcessor.separateLines(boxes))
	}
}
