package org.koitharu.kotatsu.translator.ocr

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OcrDictTest {

	private fun parseTxt(s: String) = OcrDict.parse(s.toByteArray(Charsets.UTF_8), OcrModelKind.DICT_TXT)

	private fun parseJson(s: String) = OcrDict.parse(s.toByteArray(Charsets.UTF_8), OcrModelKind.DICT_JSON)

	@Test
	fun txtOneCharPerLineCrlfStrippedFinalNewlineIsNotEntry() {
		assertEquals(listOf("a", "b", "가"), parseTxt("a\r\nb\r\n가\n"))
		assertEquals(listOf("a", "b"), parseTxt("a\nb"))
	}

	@Test
	fun txtKeepsLiteralSpaceEntry() {
		assertEquals(listOf("a", " ", "b"), parseTxt("a\n \nb\n"))
	}

	@Test
	fun txtDropsExactlyOneTrailingEmptyLine() {
		assertEquals(listOf("a", ""), parseTxt("a\n\n"))
	}

	@Test
	fun jsonArrayOfStrings() {
		assertEquals(listOf("你", "好", "a"), parseJson("[\"你\",\"好\",\"a\"]"))
	}

	@Test
	fun jsonRejectsNonArraysAndNonStrings() {
		assertThrows(JSONException::class.java) { parseJson("{\"a\":1}") }
		assertThrows(JSONException::class.java) { parseJson("[1,2]") }
	}
}
