package org.koitharu.kotatsu.translator.ai

import org.json.JSONException
import org.json.JSONObject

/**
 * Scans accumulated model JSON text and reports each element of the top-level "translations" array
 * as soon as its closing brace arrives. Tolerates leading text and code fences. Braces and quotes inside
 * strings are ignored.
 */
internal class TranslationStreamExtractor(
	private val onItem: (id: String, text: String) -> Unit,
) {

	private val all = StringBuilder()
	private var pos = 0
	private var inStr = false
	private var esc = false
	private var depth = 0
	private val key = StringBuilder()

	// 0 seeking key, 1 saw key (want ":"), 2 saw ":" (want "["), 3 inside array, 4 array closed
	private var phase = 0
	private var objStart = -1

	fun push(chunk: String) {
		all.append(chunk)
		while (pos < all.length) {
			val c = all[pos]
			if (inStr) {
				if (esc) {
					esc = false
				} else if (c == '\\') {
					esc = true
				} else if (c == '"') {
					inStr = false
					if (depth == 1 && phase == 0 && key.toString() == "translations") phase = 1
				} else if (depth == 1 && phase == 0 && key.length < 20) {
					key.append(c)
				}
				pos++
				continue
			}
			if (c == ' ' || c == '\n' || c == '\t' || c == '\r') {
				pos++
				continue
			}
			if (phase == 1 && c != ':') {
				phase = 0
			} else if (phase == 2 && c != '[') {
				phase = 0
			}
			when (c) {
				'"' -> {
					inStr = true
					key.setLength(0)
				}

				'{' -> {
					depth++
					if (phase == 3 && depth == 3) objStart = pos
				}

				'[' -> {
					depth++
					if (phase == 2) phase = 3
				}

				'}' -> {
					depth--
					if (phase == 3 && depth == 2 && objStart >= 0) {
						emitObject(all.substring(objStart, pos + 1))
						objStart = -1
					}
				}

				']' -> {
					depth--
					if (phase == 3 && depth == 1) phase = 4
				}

				':' -> if (phase == 1 && depth == 1) phase = 2
			}
			pos++
		}
	}

	private fun emitObject(src: String) {
		try {
			val o = JSONObject(src)
			val id = o.opt("id")
			val text = o.opt("text")
			if (id is String && text is String) onItem(id, text)
		} catch (e: JSONException) {
			// malformed element: the final parse decides
		}
	}
}

/**
 * Accumulates streamed model text and forwards translations of the requested ids to [onBlock].
 */
internal class PartialFeed(
	ids: Collection<String>,
	private val onBlock: ((id: String, text: String) -> Unit)?,
) {

	private val full = StringBuilder()
	private val wanted = HashSet(ids)
	private val extractor = onBlock?.let { cb ->
		TranslationStreamExtractor { id, text -> if (id in wanted) cb(id, text) }
	}

	fun push(chunk: String) {
		full.append(chunk)
		extractor?.push(chunk)
	}

	val text: String
		get() = full.toString()
}
