package org.koitharu.kotatsu.translator.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.translator.domain.model.OcrMode
import org.koitharu.kotatsu.translator.domain.model.TextBlock
import org.koitharu.kotatsu.translator.domain.model.TranslatedBlock

class SessionCachesTest {

	@Test
	fun keysIncludeLanguagePairModeAndPage() {
		assertEquals("ko|vi|FAST|42", SessionCaches.translationKey("ko", "vi", OcrMode.FAST, 42L))
		assertEquals("FAST|ko|42", SessionCaches.ocrKey(OcrMode.FAST, "ko", 42L))
		val k = SessionCaches.translationKey("ko", "vi", OcrMode.FAST, 1L)
		assertTrue(k != SessionCaches.translationKey("ko", "en", OcrMode.FAST, 1L))
		assertTrue(k != SessionCaches.translationKey("ko", "vi", OcrMode.ACCURATE, 1L))
		assertTrue(k != SessionCaches.translationKey("ja", "vi", OcrMode.FAST, 1L))
		assertTrue(SessionCaches.ocrKey(OcrMode.FAST, "ko", 1L) != SessionCaches.ocrKey(OcrMode.ACCURATE, "ko", 1L))
		assertTrue(SessionCaches.ocrKey(OcrMode.FAST, "ko", 1L) != SessionCaches.ocrKey(OcrMode.FAST, "ja", 1L))
	}

	@Test
	fun defaultCapacities() {
		assertEquals(400, SessionCaches.TRANSLATION_CAPACITY)
		assertEquals(200, SessionCaches.OCR_CAPACITY)
	}

	@Test
	fun emptyListIsAValidValue() {
		val caches = SessionCaches()
		assertNull(caches.translations["k"])
		val empty = emptyList<TranslatedBlock>()
		caches.translations["k"] = empty
		assertSame(empty, caches.translations["k"])
		caches.ocr["k"] = emptyList<TextBlock>()
		assertEquals(emptyList<TextBlock>(), caches.ocr["k"])
	}

	@Test
	fun evictsLeastRecentlyUsedBeyondCapacity() {
		val cache = LruCache<String, Int>(2)
		cache["a"] = 1
		cache["b"] = 2
		assertEquals(1, cache["a"]) // a is now the newest
		cache["c"] = 3
		assertNull(cache["b"])
		assertEquals(1, cache["a"])
		assertEquals(3, cache["c"])
		assertEquals(2, cache.size)
	}

	@Test
	fun overwritingRefreshesRecency() {
		val cache = LruCache<String, Int>(2)
		cache["a"] = 1
		cache["b"] = 2
		cache["a"] = 10
		cache["c"] = 3
		assertNull(cache["b"])
		assertEquals(10, cache["a"])
	}

	@Test
	fun clearAndRemove() {
		val caches = SessionCaches(translationCapacity = 3, ocrCapacity = 3)
		caches.translations["a"] = emptyList()
		caches.ocr["a"] = emptyList()
		caches.translations.remove("a")
		assertNull(caches.translations["a"])
		caches.clear()
		assertNull(caches.ocr["a"])
		assertEquals(0, caches.ocr.size)
	}

	@Test
	fun staysWithinCapacityUnderConcurrentUse() {
		val cache = LruCache<Int, Int>(50)
		val threads = List(4) { n ->
			Thread {
				repeat(2000) { i ->
					cache[n * 10_000 + i] = i
					cache[i]
				}
			}
		}
		threads.forEach { it.start() }
		threads.forEach { it.join() }
		assertEquals(50, cache.size)
	}
}
