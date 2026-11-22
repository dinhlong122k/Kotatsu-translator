package org.koitharu.kotatsu.translator.ai

import org.json.JSONException
import org.json.JSONObject
import org.koitharu.kotatsu.translator.domain.model.AddressUpdate
import org.koitharu.kotatsu.translator.domain.model.AiException
import org.koitharu.kotatsu.translator.domain.model.GlossaryUpdate
import org.koitharu.kotatsu.translator.domain.model.TranslateResult

/**
 * Parses the final model text. The model may wrap JSON in a Markdown fence or add a lead-in sentence: both are accepted.
 */
internal object TranslateResultParser {

	private val KINDS = setOf("name", "place", "term")
	private val FENCE = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

	/**
	 * Tries, in order: the first fenced block, the whole text, the text from the first "{" to the last "}".
	 */
	fun extractJson(raw: String): JSONObject? {
		val candidates = ArrayList<String>(3)
		FENCE.find(raw)?.let { candidates.add(it.groupValues[1]) }
		candidates.add(raw)
		val a = raw.indexOf('{')
		val b = raw.lastIndexOf('}')
		if (a >= 0 && b > a) candidates.add(raw.substring(a, b + 1))
		for (c in candidates) {
			try {
				return JSONObject(c.trim())
			} catch (e: JSONException) {
				// try next candidate
			}
		}
		return null
	}

	/**
	 * @param ids ids that were requested. Unknown and duplicate ids are dropped.
	 * @throws AiException with kind BAD_OUTPUT if the text is not JSON or some ids are missing; in the latter case
	 * [AiException.partial] holds the translations that are valid (the other fields of the partial result are empty).
	 */
	fun parse(raw: String, ids: List<String>): TranslateResult {
		val obj = extractJson(raw)
			?: throw AiException(AiException.Kind.BAD_OUTPUT, "Response is not valid JSON", partial = emptyResult())

		val wanted = ids.toHashSet()
		val translations = LinkedHashMap<String, String>()
		val arr = obj.optJSONArray("translations")
		if (arr != null) {
			for (i in 0 until arr.length()) {
				val t = arr.optJSONObject(i) ?: continue
				val id = t.opt("id") as? String ?: continue
				val text = t.opt("text") as? String ?: continue
				if (id !in wanted || translations.containsKey(id)) continue
				translations[id] = text
			}
		}

		val glossary = ArrayList<GlossaryUpdate>()
		obj.optJSONArray("glossary")?.let { a ->
			for (i in 0 until a.length()) {
				val g = a.optJSONObject(i) ?: continue
				val src = g.opt("src") as? String ?: continue
				val dst = g.opt("dst") as? String ?: continue
				val kind = g.opt("kind") as? String ?: continue
				if (kind !in KINDS) continue
				glossary.add(GlossaryUpdate(src, dst, kind))
			}
		}

		val addressing = ArrayList<AddressUpdate>()
		obj.optJSONArray("addressing")?.let { a ->
			for (i in 0 until a.length()) {
				val o = a.optJSONObject(i) ?: continue
				val from = o.opt("from") as? String ?: continue
				val to = o.opt("to") as? String ?: continue
				val form = o.opt("form") as? String ?: continue
				addressing.add(AddressUpdate(from, to, form))
			}
		}

		val missing = ids.filter { it !in translations }
		if (missing.isNotEmpty()) {
			throw AiException(
				AiException.Kind.BAD_OUTPUT,
				"Missing translations for: " + missing.joinToString(", "),
				partial = TranslateResult(translations, emptyList(), emptyList(), ""),
			)
		}
		return TranslateResult(translations, glossary, addressing, obj.opt("summary") as? String ?: "")
	}

	fun emptyResult() = TranslateResult(emptyMap(), emptyList(), emptyList(), "")
}
