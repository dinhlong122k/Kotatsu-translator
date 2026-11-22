package org.koitharu.kotatsu.translator.ai

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationStreamExtractorTest {

	private val tricky = "He said \"hi\" {x} [y] \\ done"
	private val full = "{\"translations\":[{\"id\":\"0:b1\",\"text\":" + AiHttp.jsonQuote(tricky) +
		"},{\"id\":\"0:b2\",\"text\":\"Hai\"},{\"id\":\"9:zz\",\"text\":\"lạ\"}]," +
		"\"glossary\":[{\"src\":\"a\",\"dst\":\"b\",\"kind\":\"name\"}],\"addressing\":[],\"summary\":\"s\"}"

	private fun run(chunks: List<String>): List<Pair<String, String>> {
		val out = ArrayList<Pair<String, String>>()
		val ex = TranslationStreamExtractor { id, text -> out.add(id to text) }
		chunks.forEach { ex.push(it) }
		return out
	}

	@Test
	fun emitsEveryItemWhateverTheChunkSize() {
		val expected = listOf("0:b1" to tricky, "0:b2" to "Hai", "9:zz" to "lạ")
		for (n in listOf(1, 2, 3, 7, 1000)) {
			assertEquals("chunk size $n", expected, run(full.chunked(n)))
		}
	}

	@Test
	fun emitsAnItemAsSoonAsItsClosingBraceArrives() {
		val out = ArrayList<Pair<String, String>>()
		val ex = TranslationStreamExtractor { id, text -> out.add(id to text) }
		ex.push("{\"translations\":[{\"id\":\"a\",\"text\":\"x\"")
		assertEquals(0, out.size)
		ex.push("}")
		assertEquals(listOf("a" to "x"), out)
		ex.push(",{\"id\":\"b\",\"text\":\"y\"}")
		assertEquals(2, out.size)
	}

	@Test
	fun toleratesFenceLeadInAndIgnoresOtherArraysAndKeysAfterTheArray() {
		val text = "Sure!\n```json\n{ \"glossary\": [{\"id\":\"nope\",\"text\":\"no\"}], \"translations\" : [ {\"id\":\"a\",\"text\":\"x\"} ],\n" +
			"\"extra\": {\"id\":\"c\",\"text\":\"z\"}, \"translations\": [{\"id\":\"d\",\"text\":\"w\"}] }\n```"
		assertEquals(listOf("a" to "x"), run(listOf(text)))
	}

	@Test
	fun stringValueTranslationsIsNotMistakenForTheKey() {
		val text = "{\"summary\":\"translations\",\"translations\":[{\"id\":\"a\",\"text\":\"x\"}]}"
		assertEquals(listOf("a" to "x"), run(listOf(text)))
	}

	@Test
	fun skipsElementsWithoutStringIdOrText() {
		val text = "{\"translations\":[{\"id\":1,\"text\":\"x\"},{\"id\":\"a\"},{\"id\":\"b\",\"text\":\"ok\"},\"str\",{\"id\":\"c\",\"text\":\"ok2\"}]}"
		assertEquals(listOf("b" to "ok", "c" to "ok2"), run(listOf(text)))
	}

	@Test
	fun partialFeedForwardsOnlyRequestedIdsAndKeepsFullText() {
		val seen = ArrayList<Pair<String, String>>()
		val feed = PartialFeed(listOf("0:b1", "0:b2")) { id, text -> seen.add(id to text) }
		val cut1 = full.indexOf("hi") + 1
		val cut2 = full.indexOf("{x}") + 1
		feed.push(full.substring(0, cut1))
		feed.push(full.substring(cut1, cut2))
		feed.push(full.substring(cut2))
		assertEquals(listOf("0:b1" to tricky, "0:b2" to "Hai"), seen)
		assertEquals(full, feed.text)
	}

	@Test
	fun partialFeedWithoutCallbackJustAccumulates() {
		val feed = PartialFeed(listOf("a"), null)
		feed.push("ab")
		feed.push("c")
		assertEquals("abc", feed.text)
	}

	private fun readSse(text: String): List<String> {
		val out = ArrayList<String>()
		SseReader.read(Buffer().writeUtf8(text)) { out.add(it) }
		return out
	}

	@Test
	fun sseDataLinesCrlfCommentsOtherFieldsAndDone() {
		val text = ": keep-alive\r\nevent: x\r\ndata: {\"a\":1}\r\n\r\ndata: {\"b\":\r\ndata: 2}\n\nid: 5\n\ndata:[DONE]\n\ndata: tail"
		assertEquals(listOf("{\"a\":1}", "{\"b\":\n2}", "tail"), readSse(text))
	}

	@Test
	fun sseMultiByteCharactersSurvive() {
		assertEquals(listOf("\"안녕\""), readSse("data: \"안녕\"\n\n"))
	}

	@Test
	fun sseEmptyBody() {
		assertEquals(emptyList<String>(), readSse(""))
	}
}
