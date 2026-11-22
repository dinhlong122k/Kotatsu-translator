package org.koitharu.kotatsu.translator.ocr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A recognized text line. */
class RecognizedLine(val box: Box, val text: String, val conf: Double)

/** A text block: one or several lines grouped together. */
class TextGroup(val box: Box, val text: String, val conf: Double, val isVertical: Boolean)

/**
 * Grouping of recognized lines into blocks and reading order. Port of `groupLines`, `sortLines` and
 * `readingOrder` of `src/ocr/post.ts`, plus the [readingOrder] `rtl` flag that the original lacks.
 */
object LineGrouper {

	/** Vertical columns: close horizontally (<= 0.8 x narrower width), overlapping vertically (>= 30%), similar widths. */
	private fun linkedVertical(a: Box, b: Box): Boolean {
		val wHi = max(a.w, b.w)
		val wLo = min(a.w, b.w)
		if (wHi / wLo > 1.6) return false
		val gap = max(a.x, b.x) - min(a.right, b.right) // < 0 when overlapping horizontally
		if (gap > 0.8 * wLo) return false
		val oy = min(a.bottom, b.bottom) - max(a.y, b.y)
		return oy >= 0.3 * min(a.h, b.h)
	}

	private fun linked(a: Box, b: Box): Boolean {
		// Columns only ever join other columns; a tall box never joins horizontal dialogue.
		val va = a.isVertical
		val vb = b.isVertical
		if (va || vb) return va && vb && linkedVertical(a, b)
		val hi = max(a.h, b.h)
		val lo = min(a.h, b.h)
		if (hi / lo > 1.6) return false
		val gap = max(a.y, b.y) - min(a.bottom, b.bottom) // < 0 when overlapping vertically
		if (gap > 0.8 * lo) return false
		val ox = min(a.right, b.right) - max(a.x, b.x)
		val narrow = min(a.w, b.w)
		val wide = max(a.w, b.w)
		val dc = abs(a.x + a.w / 2 - (b.x + b.w / 2))
		return ox >= 0.3 * narrow || dc <= 0.5 * wide
	}

	/** True when more than half of the boxes are vertical columns. */
	fun isMostlyVertical(boxes: List<Box>): Boolean = boxes.count { it.isVertical } * 2 > boxes.size

	/** Reading order of the lines of one block: top to bottom, or right to left when most are vertical columns. */
	fun <T> sortLines(lines: List<T>, boxOf: (T) -> Box): List<T> {
		val vertical = isMostlyVertical(lines.map(boxOf))
		val comparator: Comparator<T> = if (vertical) {
			Comparator { a, b ->
				val ba = boxOf(a)
				val bb = boxOf(b)
				val c = bb.x.compareTo(ba.x)
				if (c != 0) c else ba.y.compareTo(bb.y)
			}
		} else {
			Comparator { a, b ->
				val ba = boxOf(a)
				val bb = boxOf(b)
				val c = ba.y.compareTo(bb.y)
				if (c != 0) c else ba.x.compareTo(bb.x)
			}
		}
		return lines.sortedWith(comparator)
	}

	/** Groups recognized lines into text blocks (union-find on a vertical/horizontal proximity rule). */
	fun groupLines(lines: List<RecognizedLine>, imgW: Int, imgH: Int): List<TextGroup> {
		val parent = IntArray(lines.size) { it }
		fun find(i: Int): Int {
			var root = i
			while (parent[root] != root) root = parent[root]
			var cur = i
			while (parent[cur] != root) {
				val next = parent[cur]
				parent[cur] = root
				cur = next
			}
			return root
		}
		for (i in lines.indices) {
			for (j in i + 1 until lines.size) {
				if (linked(lines[i].box, lines[j].box)) {
					parent[find(i)] = find(j)
				}
			}
		}
		val groups = LinkedHashMap<Int, MutableList<RecognizedLine>>()
		for (i in lines.indices) {
			groups.getOrPut(find(i)) { ArrayList() }.add(lines[i])
		}
		return groups.values.map { g ->
			val sorted = sortLines(g) { it.box }
			val u = sorted.map { it.box }.reduce(DetPostProcessor::union)
			val pad = 0.25 * (g.sumOf { if (it.box.isVertical) it.box.w else it.box.h } / g.size)
			val x0 = max(0.0, floor(u.x - pad))
			val y0 = max(0.0, floor(u.y - pad))
			val x1 = min(imgW.toDouble(), ceil(u.right + pad))
			val y1 = min(imgH.toDouble(), ceil(u.bottom + pad))
			TextGroup(
				box = Box(x0, y0, x1 - x0, y1 - y0),
				text = sorted.joinToString(" ") { it.text },
				conf = g.sumOf { it.conf } / g.size,
				isVertical = isMostlyVertical(g.map { it.box }),
			)
		}
	}

	/**
	 * Reading order of blocks: rows top to bottom (a block belongs to the first row whose first element overlaps it
	 * vertically by more than half of the lower box height), inside a row left to right, or right to left when
	 * [rtl] is set (manga).
	 */
	fun <T> readingOrder(items: List<T>, rtl: Boolean = false, boxOf: (T) -> Box): List<T> {
		val sorted = items.sortedWith(
			Comparator { a, b ->
				val ba = boxOf(a)
				val bb = boxOf(b)
				val c = ba.y.compareTo(bb.y)
				if (c != 0) c else ba.x.compareTo(bb.x)
			},
		)
		val rows = ArrayList<MutableList<T>>()
		for (item in sorted) {
			val box = boxOf(item)
			val row = rows.firstOrNull { r ->
				val ref = boxOf(r[0])
				val top = max(ref.y, box.y)
				val bottom = min(ref.bottom, box.bottom)
				val lower = if (ref.y >= box.y) ref else box
				bottom - top > 0.5 * lower.h
			}
			if (row != null) row.add(item) else rows.add(arrayListOf(item))
		}
		val byX: Comparator<T> = if (rtl) {
			Comparator { a, b -> boxOf(b).x.compareTo(boxOf(a).x) }
		} else {
			Comparator { a, b -> boxOf(a).x.compareTo(boxOf(b).x) }
		}
		return rows.flatMap { it.sortedWith(byX) }
	}
}
