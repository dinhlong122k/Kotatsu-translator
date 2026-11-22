package org.koitharu.kotatsu.translator.ocr

import org.json.JSONArray
import org.json.JSONException

/**
 * Parses a recognition dictionary. Class 0 of the CTC output is the blank; class k+1 is dict[k]; the class
 * after the last entry is the space (CtcDecoder emits " " for any index beyond the dictionary).
 * txt: one character per line (a final newline is not an entry). json: a JSON array of strings.
 */
object OcrDict {

	fun parse(bytes: ByteArray, kind: OcrModelKind): List<String> {
		val text = String(bytes, Charsets.UTF_8)
		if (kind == OcrModelKind.DICT_JSON) {
			val array = JSONArray(text)
			val result = ArrayList<String>(array.length())
			for (i in 0 until array.length()) {
				val item = array.get(i)
				if (item !is String) {
					throw JSONException("dict: expected a JSON array of strings")
				}
				result.add(item)
			}
			return result
		}
		val lines = text.split('\n').mapTo(ArrayList()) { if (it.endsWith('\r')) it.dropLast(1) else it }
		if (lines.isNotEmpty() && lines.last().isEmpty()) {
			lines.removeAt(lines.lastIndex)
		}
		return lines
	}
}
