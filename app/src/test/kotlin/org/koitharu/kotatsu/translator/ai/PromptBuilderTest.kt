package org.koitharu.kotatsu.translator.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.translator.domain.model.LanguagePair

class PromptBuilderTest {

	private val pair = LanguagePair("ko", "vi")
	private val bubbles = listOf(PromptBubble("0:b1", "안녕"))

	private fun pack(id: String, genre: String? = null, size: Int = 2500) = SkillPack(
		id = id,
		version = 1,
		langPair = "ko-vi",
		genre = genre,
		title = id,
		instructions = "x".repeat(size),
		terms = emptyList(),
		reviewed = true,
	)

	private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

	@Test
	fun rulesAreTheOriginalTemplateWithComicInsteadOfManhwa() {
		val rules = PromptBuilder.rules("ko", "vi")
		val expected = "You translate comic speech bubbles from ko to vi.\n" +
			"Priority when sources conflict: \"pinned\" names > \"glossary\" > skill pack terms.\n" +
			"Keep names exactly as in pinned/glossary and pronoun pairs exactly as in addressing.\n" +
			"Natural spoken dialogue fitting each character; keep length close to the source so it fits the bubble.\n" +
			"OCR text may lack spaces or have a few wrong characters; infer from context.\n" +
			"Return ONLY a JSON object: {\"translations\":[{\"id\",\"text\"}],\"glossary\":[{\"src\",\"dst\",\"kind\"}],\n" +
			"\"addressing\":[{\"from\",\"to\",\"form\"}],\"summary\":\"<=500 chars, updated story summary in vi\"}.\n" +
			"Exactly one translation per input id; bubbles may come from several consecutive pages, listed in reading order, " +
			"and the id is opaque (copy it unchanged). \"kind\" is one of name|place|term.\n"
		assertEquals(expected, rules)
		assertFalse(rules.contains("manhwa"))
	}

	@Test
	fun renderPacksFormat() {
		val p = pack("p", size = 3).copy(instructions = "ins", terms = listOf(SkillTerm("a", "b"), SkillTerm("c", "d")))
		assertEquals("## p\nins\nTerms: a → b; c → d\n", PromptBuilder.renderPacks(listOf(p)))
		assertEquals("## p\nins\n", PromptBuilder.renderPacks(listOf(p.copy(terms = emptyList()))))
	}

	@Test
	fun overBudgetDropsGenrePacksByDescendingIdAndKeepsBase() {
		val packs = listOf(pack("genre.a", "a"), pack("genre.b", "b"), pack("genre.c", "c"), pack("ko-vi.base"))
		val built = PromptBuilder.buildDetailed(pair, PromptContext(), packs, bubbles)
		assertEquals(listOf("genre.c", "genre.b"), built.droppedPacks)
		val stable = built.request.stablePrompt
		assertTrue(stable.contains("## ko-vi.base"))
		assertTrue(stable.contains("## genre.a"))
		assertFalse(stable.contains("## genre.b"))
		assertFalse(stable.contains("## genre.c"))
	}

	@Test
	fun basePacksAreNeverDroppedEvenOverBudget() {
		val (kept, dropped) = PromptBuilder.applyBudget(listOf(pack("a.base", size = 7000)))
		assertEquals(1, kept.size)
		assertTrue(dropped.isEmpty())
	}

	@Test
	fun stablePromptDoesNotDependOnContextOrBubbles() {
		val packs = listOf(pack("ko-vi.base", size = 50))
		val a = PromptBuilder.build(pair, PromptContext(), packs, bubbles)
		val ctx = PromptContext(
			pinned = listOf(PromptTerm("a", "b")),
			summary = "khác",
			recentLines = listOf("x"),
		)
		val b = PromptBuilder.build(pair, ctx, packs, listOf(PromptBubble("z", "다른")))
		assertEquals(a.stablePrompt, b.stablePrompt)
		assertTrue(a.stablePrompt.startsWith(PromptBuilder.rules("ko", "vi")))
	}

	@Test
	fun dynamicPromptIsCompactJsonWithTheExpectedShape() {
		val ctx = PromptContext(
			pinned = listOf(PromptTerm("A", "a")),
			glossary = listOf(PromptGlossaryEntry("B", "b", "place")),
			addressing = listOf(PromptAddress("x", "y", "anh-em")),
			summary = "S \"q\"\nline",
			recentLines = listOf("L"),
		)
		val req = PromptBuilder.build(pair, ctx, emptyList(), listOf(PromptBubble("3:b0", "안녕"), PromptBubble("3:b1", "a\\b")))
		val raw = req.dynamicPrompt
		assertTrue(raw.startsWith("{\"sourceLang\":\"ko\",\"targetLang\":\"vi\",\"pinned\":[{\"src\":\"A\",\"dst\":\"a\"}],\"glossary\":"))
		assertFalse(raw.contains("\n"))
		assertFalse(raw.contains("\": "))
		val d = JSONObject(raw)
		assertEquals("S \"q\"\nline", d.getString("summary"))
		assertEquals("place", d.getJSONArray("glossary").getJSONObject(0).getString("kind"))
		assertEquals("anh-em", d.getJSONArray("addressing").getJSONObject(0).getString("form"))
		assertEquals("L", d.getJSONArray("recentLines").getString(0))
		val b = d.getJSONArray("bubbles")
		assertEquals(2, b.length())
		assertEquals("3:b1", b.getJSONObject(1).getString("id"))
		assertEquals("a\\b", b.getJSONObject(1).getString("text"))
		assertEquals(listOf("3:b0", "3:b1"), req.blockIds)
		assertEquals(pair, req.pair)
	}

	@Test
	fun emptyContextProducesEmptyArrays() {
		val raw = PromptBuilder.dynamicPrompt(pair, PromptContext(), emptyList())
		assertEquals(
			"{\"sourceLang\":\"ko\",\"targetLang\":\"vi\",\"pinned\":[],\"glossary\":[],\"addressing\":[],\"summary\":\"\",\"recentLines\":[],\"bubbles\":[]}",
			raw,
		)
	}

	@Test
	fun allGenresOfEveryBundledPairFitTheBudget() {
		val all = SkillPackTestSupport.loadAll()
		for (key in listOf("ko-vi", "ja-vi", "zh-vi", "en-vi")) {
			val genres = SkillPackRegistry.genres(all, key).map { it.id }.toSet()
			assertEquals(setOf("murim", "school", "office", "system"), genres)
			val packs = SkillPackRegistry.select(all, key, genres)
			assertEquals(5, packs.size)
			assertTrue(key, bytes(PromptBuilder.renderPacks(packs)) <= PromptBuilder.SKILL_BUDGET_BYTES)
			assertTrue(key, PromptBuilder.applyBudget(packs).second.isEmpty())
		}
	}
}
