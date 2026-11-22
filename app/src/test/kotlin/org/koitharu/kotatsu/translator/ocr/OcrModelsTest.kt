package org.koitharu.kotatsu.translator.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrModelsTest {

	@Test
	fun everyPackHasKeysWithRightKindsAndBytesSum() {
		for (p in OcrModels.packs) {
			assertEquals(OcrModelKind.ONNX, OcrModels.spec(p.recKey).kind)
			assertTrue(OcrModels.spec(p.dictKey).kind != OcrModelKind.ONNX)
			assertEquals(OcrModels.spec(p.recKey).bytes + OcrModels.spec(p.dictKey).bytes, OcrModels.packBytes(p))
			assertTrue(p.label.isNotEmpty())
		}
	}

	@Test
	fun everyModelHasUniquePinnedUrlSha256AndSize() {
		val urls = HashSet<String>()
		val urlRegex = Regex("^https://huggingface\\.co/.+/resolve/[0-9a-f]{40}/.+")
		for ((key, m) in OcrModels.specs) {
			assertEquals(key, m.key)
			assertTrue(m.sha256, Regex("^[0-9a-f]{64}$").matches(m.sha256))
			assertTrue(m.bytes > 0)
			assertTrue(m.url, urlRegex.matches(m.url))
			urls.add(m.url)
		}
		assertEquals(OcrModels.specs.size, urls.size)
		// det, bubble and 7 packs x (rec + dict)
		assertEquals(2 + 7 * 2, OcrModels.specs.size)
	}

	@Test
	fun packIdsUniqueAndEveryLanguageInExactlyOnePack() {
		assertEquals(OcrModels.packs.size, OcrModels.packs.map { it.id }.toSet().size)
		for (l in OcrModels.sourceLangs) {
			assertEquals(1, OcrModels.packs.count { l in it.langs })
		}
		assertEquals(OcrModels.sourceLangs.size, OcrModels.sourceLangs.toSet().size)
	}

	@Test
	fun koreanBundleKeepsItsUrlsAndHashes() {
		assertEquals("322f140154c820fcb83c3d24cfe42c9ec70dd1a1834163306a7338136e4f1eaa", OcrModels.spec("rec-ko").sha256)
		assertEquals(
			"https://huggingface.co/monkt/paddleocr-onnx/resolve/7b02d0a30a07ba2b92ad1ff5a8941ae2c633de65/languages/korean/rec.onnx",
			OcrModels.spec("rec-ko").url,
		)
		assertEquals("6bdf743af375b13fbbeeb47a91a40063af5bfb7a7f197f6df8cb990dc827e5d6", OcrModels.spec("det").sha256)
		assertEquals(4766440L, OcrModels.spec("det").bytes)
		assertEquals("5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79", OcrModels.spec("bubble").sha256)
		assertEquals(11120765L, OcrModels.spec("bubble").bytes)
	}

	@Test
	fun packForMapsLanguages() {
		assertEquals("ko", OcrModels.packFor("ko")?.id)
		assertEquals("cjk", OcrModels.packFor("ja")?.id)
		assertEquals("cjk", OcrModels.packFor("zh")?.id)
		assertEquals("latin", OcrModels.packFor("es")?.id)
		assertEquals("cyrillic", OcrModels.packFor("uk")?.id)
		assertNull(OcrModels.packFor("xx"))
		assertTrue(OcrModels.packFor("ja")!!.vertical)
		assertFalse(OcrModels.packFor("ko")!!.vertical)
	}

	@Test
	fun englishFallsBackToCjkPack() {
		assertEquals(listOf("en", "cjk"), OcrModels.packCandidates("en").map { it.id })
		assertEquals(listOf("cjk"), OcrModels.packCandidates("ja").map { it.id })
		assertEquals(emptyList<OcrPack>(), OcrModels.packCandidates("xx"))
	}

	@Test
	fun isSourceLangValidates() {
		assertTrue(OcrModels.isSourceLang("ja"))
		assertFalse(OcrModels.isSourceLang("ar"))
		assertFalse(OcrModels.isSourceLang(null))
		assertNotNull(OcrModels.packById("cjk"))
	}
}
