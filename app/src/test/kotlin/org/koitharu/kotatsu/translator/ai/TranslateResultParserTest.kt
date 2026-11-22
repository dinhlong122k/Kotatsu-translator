package org.koitharu.kotatsu.translator.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.koitharu.kotatsu.translator.domain.model.AddressUpdate
import org.koitharu.kotatsu.translator.domain.model.AiException
import org.koitharu.kotatsu.translator.domain.model.GlossaryUpdate

class TranslateResultParserTest {

	private val ok = """
		{"translations":[{"id":"b1","text":"Chào"},{"id":"b2","text":"Tạm biệt"}],
		"glossary":[{"src":"A","dst":"B","kind":"name"}],
		"addressing":[{"from":"x","to":"y","form":"anh"}],"summary":"s"}
	""".trimIndent()

	private fun failure(raw: String, ids: List<String>): AiException {
		try {
			TranslateResultParser.parse(raw, ids)
		} catch (e: AiException) {
			return e
		}
		fail("did not throw")
		throw IllegalStateException()
	}

	@Test
	fun validJson() {
		val r = TranslateResultParser.parse(ok, listOf("b1", "b2"))
		assertEquals(mapOf("b1" to "Chào", "b2" to "Tạm biệt"), r.translations)
		assertEquals(listOf("b1", "b2"), r.translations.keys.toList())
		assertEquals(listOf(GlossaryUpdate("A", "B", "name")), r.glossary)
		assertEquals(listOf(AddressUpdate("x", "y", "anh")), r.addressing)
		assertEquals("s", r.summary)
	}

	@Test
	fun jsonInsideFenceWithLeadIn() {
		val raw = "Here is the result:\n```json\n" +
			"{\"translations\":[{\"id\":\"b1\",\"text\":\"Chào\"}],\"glossary\":[],\"addressing\":[],\"summary\":\"s\"}\n```"
		assertEquals(mapOf("b1" to "Chào"), TranslateResultParser.parse(raw, listOf("b1")).translations)
	}

	@Test
	fun leadInWithoutFenceTakesFirstBraceToLastBrace() {
		val raw = "Sure! $ok hope it helps"
		assertEquals(2, TranslateResultParser.parse(raw, listOf("b1", "b2")).translations.size)
	}

	@Test
	fun missingIdIsBadOutputWithPartial() {
		val raw = """{"translations":[{"id":"b1","text":"Chào"}],"glossary":[],"addressing":[],"summary":""}"""
		val e = failure(raw, listOf("b1", "b2"))
		assertEquals(AiException.Kind.BAD_OUTPUT, e.kind)
		assertEquals(mapOf("b1" to "Chào"), e.partial?.translations)
	}

	@Test
	fun unknownAndDuplicateIdsAndBadFieldsAreDropped() {
		val raw = """
			{"translations":[{"id":"b1","text":"first","extra":1},{"id":"zzz","text":"stray"},{"id":"b1","text":"second"},
			{"id":2,"text":"num"},{"id":"b1"}],
			"glossary":[{"src":"a","dst":"b","kind":"bogus"},{"src":"c","dst":"d","kind":"place","junk":true},{"src":"e","dst":"f"}],
			"addressing":[{"from":"x","to":"y"}],"summary":42,"other":"x"}
		""".trimIndent()
		val r = TranslateResultParser.parse(raw, listOf("b1"))
		assertEquals(mapOf("b1" to "first"), r.translations)
		assertEquals(listOf(GlossaryUpdate("c", "d", "place")), r.glossary)
		assertTrue(r.addressing.isEmpty())
		assertEquals("", r.summary)
	}

	@Test
	fun notJsonIsBadOutputWithEmptyPartial() {
		val e = failure("not json at all", listOf("b1"))
		assertEquals(AiException.Kind.BAD_OUTPUT, e.kind)
		assertTrue(e.partial?.translations.orEmpty().isEmpty())
	}

	@Test
	fun emptyTextIsBadOutput() {
		assertEquals(AiException.Kind.BAD_OUTPUT, failure("", listOf("b1")).kind)
	}

	@Test
	fun extractJsonPrefersFence() {
		val o = TranslateResultParser.extractJson("text {\"a\":1} ```json\n{\"b\":2}\n```")
		assertNotNull(o)
		assertTrue(o!!.has("b"))
	}
}
