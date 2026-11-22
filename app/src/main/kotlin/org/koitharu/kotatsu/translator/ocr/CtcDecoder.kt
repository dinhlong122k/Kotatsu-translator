package org.koitharu.kotatsu.translator.ocr

class CtcResult(val text: String, val conf: Double) {

	override fun equals(other: Any?): Boolean =
		other is CtcResult && text == other.text && conf == other.conf

	override fun hashCode(): Int = 31 * text.hashCode() + conf.hashCode()

	override fun toString(): String = "CtcResult(text=$text, conf=$conf)"
}

/** Greedy CTC decoding. Class 0 is the blank, class k+1 is dict[k], any class past the dictionary is a space. */
object CtcDecoder {

	/**
	 * @param data output of the recognizer, row-major [steps, classes] (batch 1)
	 * @return decoded text and the mean probability of the emitted characters (0 when nothing was emitted)
	 */
	fun decode(data: FloatArray, steps: Int, classes: Int, dict: List<String>): CtcResult {
		var last = 0
		val text = StringBuilder()
		var conf = 0.0
		var k = 0
		for (t in 0 until steps) {
			var best = 0
			var bv = -1f
			val base = t * classes
			for (c in 0 until classes) {
				val v = data[base + c]
				if (v > bv) {
					bv = v
					best = c
				}
			}
			if (best != 0 && best != last) {
				text.append(if (best - 1 < dict.size) dict[best - 1] else " ")
				conf += bv
				k++
			}
			last = best
		}
		return CtcResult(text.toString(), if (k > 0) conf / k else 0.0)
	}
}
