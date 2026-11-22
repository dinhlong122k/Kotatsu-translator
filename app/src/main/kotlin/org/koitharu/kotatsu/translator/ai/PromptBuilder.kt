package org.koitharu.kotatsu.translator.ai

import org.koitharu.kotatsu.translator.domain.model.LanguagePair
import org.koitharu.kotatsu.translator.domain.model.TranslateRequest

/**
 * Series knowledge that goes into the dynamic part of the prompt. Neutral on purpose: the series profile type of the
 * `context` package is mapped to this one by the caller.
 *
 * @param pinned names fixed by the user (highest priority)
 * @param glossary names and terms learned from previous translations
 * @param addressing pronoun pairs between characters
 * @param summary short story summary in the target language
 * @param recentLines last translated lines
 */
data class PromptContext(
	val pinned: List<PromptTerm> = emptyList(),
	val glossary: List<PromptGlossaryEntry> = emptyList(),
	val addressing: List<PromptAddress> = emptyList(),
	val summary: String = "",
	val recentLines: List<String> = emptyList(),
)

data class PromptTerm(val src: String, val dst: String)

/** [kind] is one of name, place, term */
data class PromptGlossaryEntry(val src: String, val dst: String, val kind: String)

data class PromptAddress(val from: String, val to: String, val form: String)

/** Text to translate. [id] is opaque for the model (it copies it back), for example "3:b0". */
data class PromptBubble(val id: String, val text: String)

class BuiltPrompt(
	val request: TranslateRequest,
	/** Ids of the genre packs that did not fit into [PromptBuilder.SKILL_BUDGET_BYTES] */
	val droppedPacks: List<String>,
)

object PromptBuilder {

	const val SKILL_BUDGET_BYTES = 6000

	/**
	 * System message: the fixed rules (the same for a pair of languages) followed by the skill packs. Stable between
	 * requests of one series so providers can cache it.
	 */
	fun rules(sourceLang: String, targetLang: String): String = listOf(
		"You translate comic speech bubbles from $sourceLang to $targetLang.",
		"Priority when sources conflict: \"pinned\" names > \"glossary\" > skill pack terms.",
		"Keep names exactly as in pinned/glossary and pronoun pairs exactly as in addressing.",
		"Natural spoken dialogue fitting each character; keep length close to the source so it fits the bubble.",
		"OCR text may lack spaces or have a few wrong characters; infer from context.",
		"Return ONLY a JSON object: {\"translations\":[{\"id\",\"text\"}],\"glossary\":[{\"src\",\"dst\",\"kind\"}],",
		"\"addressing\":[{\"from\",\"to\",\"form\"}],\"summary\":\"<=500 chars, updated story summary in $targetLang\"}.",
		"Exactly one translation per input id; bubbles may come from several consecutive pages, listed in reading order, " +
			"and the id is opaque (copy it unchanged). \"kind\" is one of name|place|term.",
	).joinToString(separator = "\n", postfix = "\n")

	fun renderPacks(packs: List<SkillPack>): String = buildString {
		for (p in packs) {
			append("## ").append(p.title).append('\n').append(p.instructions).append('\n')
			if (p.terms.isNotEmpty()) {
				append("Terms: ")
				append(p.terms.joinToString("; ") { it.src + " → " + it.dst })
				append('\n')
			}
		}
	}

	/**
	 * Over budget: drops genre packs by id descending; base packs (no genre) are always kept.
	 *
	 * @return the packs that fit and the ids of the dropped ones
	 */
	fun applyBudget(packs: List<SkillPack>, budgetBytes: Int = SKILL_BUDGET_BYTES): Pair<List<SkillPack>, List<String>> {
		var result = packs
		val dropped = ArrayList<String>()
		val droppable = ArrayDeque(packs.filter { it.genre != null }.sortedByDescending { it.id })
		while (utf8Size(renderPacks(result)) > budgetBytes && droppable.isNotEmpty()) {
			val d = droppable.removeFirst()
			result = result.filter { it !== d }
			dropped.add(d.id)
		}
		return result to dropped
	}

	/**
	 * The dynamic message: a compact (no indentation) JSON with the series context and the bubbles.
	 */
	fun dynamicPrompt(pair: LanguagePair, context: PromptContext, bubbles: List<PromptBubble>): String {
		val sb = StringBuilder()
		sb.append("{\"sourceLang\":"); AiHttp.jsonQuote(pair.source, sb)
		sb.append(",\"targetLang\":"); AiHttp.jsonQuote(pair.target, sb)
		sb.append(",\"pinned\":[")
		context.pinned.forEachIndexed { i, t ->
			if (i > 0) sb.append(',')
			sb.append("{\"src\":"); AiHttp.jsonQuote(t.src, sb)
			sb.append(",\"dst\":"); AiHttp.jsonQuote(t.dst, sb)
			sb.append('}')
		}
		sb.append("],\"glossary\":[")
		context.glossary.forEachIndexed { i, g ->
			if (i > 0) sb.append(',')
			sb.append("{\"src\":"); AiHttp.jsonQuote(g.src, sb)
			sb.append(",\"dst\":"); AiHttp.jsonQuote(g.dst, sb)
			sb.append(",\"kind\":"); AiHttp.jsonQuote(g.kind, sb)
			sb.append('}')
		}
		sb.append("],\"addressing\":[")
		context.addressing.forEachIndexed { i, a ->
			if (i > 0) sb.append(',')
			sb.append("{\"from\":"); AiHttp.jsonQuote(a.from, sb)
			sb.append(",\"to\":"); AiHttp.jsonQuote(a.to, sb)
			sb.append(",\"form\":"); AiHttp.jsonQuote(a.form, sb)
			sb.append('}')
		}
		sb.append("],\"summary\":"); AiHttp.jsonQuote(context.summary, sb)
		sb.append(",\"recentLines\":[")
		context.recentLines.forEachIndexed { i, line ->
			if (i > 0) sb.append(',')
			AiHttp.jsonQuote(line, sb)
		}
		sb.append("],\"bubbles\":[")
		bubbles.forEachIndexed { i, b ->
			if (i > 0) sb.append(',')
			sb.append("{\"id\":"); AiHttp.jsonQuote(b.id, sb)
			sb.append(",\"text\":"); AiHttp.jsonQuote(b.text, sb)
			sb.append('}')
		}
		sb.append("]}")
		return sb.toString()
	}

	/**
	 * @param packs packs selected by [SkillPacks.select]
	 */
	fun buildDetailed(
		pair: LanguagePair,
		context: PromptContext,
		packs: List<SkillPack>,
		bubbles: List<PromptBubble>,
	): BuiltPrompt {
		val (fit, dropped) = applyBudget(packs)
		val request = TranslateRequest(
			pair = pair,
			stablePrompt = rules(pair.source, pair.target) + renderPacks(fit),
			dynamicPrompt = dynamicPrompt(pair, context, bubbles),
			blockIds = bubbles.map { it.id },
		)
		return BuiltPrompt(request, dropped)
	}

	fun build(
		pair: LanguagePair,
		context: PromptContext,
		packs: List<SkillPack>,
		bubbles: List<PromptBubble>,
	): TranslateRequest = buildDetailed(pair, context, packs, bubbles).request

	private fun utf8Size(s: String): Int = s.toByteArray(Charsets.UTF_8).size
}
