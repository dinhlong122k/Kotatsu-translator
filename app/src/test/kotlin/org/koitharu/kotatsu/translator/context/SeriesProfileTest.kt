package org.koitharu.kotatsu.translator.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.translator.domain.model.AddressUpdate
import org.koitharu.kotatsu.translator.domain.model.GlossaryUpdate
import org.koitharu.kotatsu.translator.domain.model.TranslateResult

class SeriesProfileTest {

	private fun result(
		translations: Map<String, String> = emptyMap(),
		glossary: List<GlossaryUpdate> = emptyList(),
		addressing: List<AddressUpdate> = emptyList(),
		summary: String = "",
	) = TranslateResult(translations, glossary, addressing, summary)

	private fun base() = emptyProfile("ko", "vi", 0L)

	private fun ge(i: Int, src: String = "s$i", dst: String = "d$i", pinned: Boolean = false) =
		GlossaryEntry(src, dst, "term", i, pinned)

	private fun ok(r: ProfileEditResult): SeriesProfile = (r as ProfileEditResult.Ok).profile

	private fun rejected(r: ProfileEditResult): ProfileEditError = (r as ProfileEditResult.Rejected).error

	private fun pin(p: SeriesProfile, src: String, dst: String) = ok(addPinned(p, src, dst, "name"))

	@Test
	fun emptyProfileAndBytes() {
		val p = base()
		assertEquals("ko", p.sourceLang)
		assertEquals("vi", p.targetLang)
		assertTrue(p.glossary.isEmpty() && p.addressing.isEmpty() && p.recentLines.isEmpty())
		assertEquals(0, p.pageCounter)
		assertEquals(60, ProfileLimits.GLOSSARY)
		assertEquals(20, ProfileLimits.ADDRESSING)
		assertEquals(500, ProfileLimits.SUMMARY)
		assertEquals(5, ProfileLimits.RECENT_LINES)
		assertEquals(4096, ProfileLimits.BYTES)
		assertEquals(30, ProfileLimits.PINNED)
		assertEquals(32, ProfileLimits.PINNED_FIELD)
		assertEquals(SeriesProfileCodec.encode(p).toByteArray(Charsets.UTF_8).size, profileBytes(p))
		assertEquals(profileBytes(p) + 3, profileBytes(p.copy(summary = "수")))
	}

	@Test
	fun jsonRoundTripAndPinnedKey() {
		val p = base().copy(glossary = listOf(ge(1), ge(2, pinned = true)), genres = listOf("murim"))
		val json = SeriesProfileCodec.encode(p)
		assertEquals(1, Regex("\"pinned\"").findAll(json).count())
		assertEquals(p, SeriesProfileCodec.decode(json))
	}

	@Test
	fun mergeIncreasesPageCounterAndSetsLastSeen() {
		val p = mergeResult(base(), result(glossary = listOf(GlossaryUpdate("수희", "Su-hee", "name"))), 88L, 1000L)
		assertEquals(1, p.pageCounter)
		assertEquals(GlossaryEntry("수희", "Su-hee", "name", 1), p.glossary[0])
		assertEquals(88L, p.lastChapter)
		assertEquals(1000L, p.lastReadAt)
		assertEquals(3, mergeResult(p, result(), 88L, 1001L, pages = 2).pageCounter)
	}

	@Test
	fun mergeOverwritesGlossaryAndAddressing() {
		var p = mergeResult(
			base(),
			result(
				glossary = listOf(GlossaryUpdate("a", "old", "name")),
				addressing = listOf(AddressUpdate("x", "y", "anh/em")),
			),
			1L, 1L,
		)
		p = mergeResult(
			p,
			result(
				glossary = listOf(GlossaryUpdate("a", "new", "name")),
				addressing = listOf(AddressUpdate("x", "y", "chị/em"), AddressUpdate("y", "x", "em/anh")),
			),
			1L, 2L,
		)
		assertEquals(1, p.glossary.size)
		assertEquals("new", p.glossary[0].dst)
		assertEquals(2, p.glossary[0].lastSeen)
		assertEquals(2, p.addressing.size)
		assertEquals(AddressEntry("x", "y", "chị/em", 2), p.addressing.first { it.from == "x" })
	}

	@Test
	fun mergeKeepsOldSummaryWhenNewIsEmpty() {
		var p = mergeResult(base(), result(summary = "cũ"), 1L, 1L)
		p = mergeResult(p, result(summary = ""), 1L, 2L)
		assertEquals("cũ", p.summary)
		p = mergeResult(p, result(summary = "mới"), 1L, 3L)
		assertEquals("mới", p.summary)
	}

	@Test
	fun mergeDoesNotChangeInput() {
		val p = base().copy(glossary = listOf(ge(1)))
		val snapshot = SeriesProfileCodec.encode(p)
		mergeResult(p, result(glossary = listOf(GlossaryUpdate("a", "b", "name")), summary = "s"), 3L, 9L)
		assertEquals(snapshot, SeriesProfileCodec.encode(p))
	}

	@Test
	fun recentLinesKeepLastFiveInOrder() {
		val translations = LinkedHashMap<String, String>()
		for (i in 0 until 7) translations["b$i"] = "c$i"
		val p = mergeResult(base(), result(translations = translations), 1L, 1L)
		assertEquals(listOf("c2", "c3", "c4", "c5", "c6"), p.recentLines)
		val q = mergeResult(p, result(translations = mapOf("z" to "c7")), 1L, 2L)
		assertEquals(listOf("c3", "c4", "c5", "c6", "c7"), q.recentLines)
	}

	@Test
	fun aiCannotOverwritePinnedName() {
		var p = pin(base(), "수희", "Su-hee")
		p = mergeResult(p, result(glossary = listOf(GlossaryUpdate("수희", "Khác", "place"))), 1L, 5L)
		assertEquals(1, p.glossary.size)
		val g = p.glossary[0]
		assertEquals("Su-hee", g.dst)
		assertEquals("name", g.kind)
		assertTrue(g.pinned)
		assertEquals(1, g.lastSeen)
	}

	@Test
	fun trimCutsSummaryTo500() {
		assertEquals(500, trimProfile(base().copy(summary = "a".repeat(900))).summary.length)
	}

	@Test
	fun trimDoesNotSplitSurrogatePair() {
		val s = "a".repeat(499) + "😀" + "b"
		val t = trimProfile(base().copy(summary = s)).summary
		assertEquals(499, t.length)
	}

	@Test
	fun trimKeepsNewest60GlossaryAnd20Addressing() {
		val t = trimProfile(base().copy(glossary = (0 until 80).map { ge(it, src = "$it", dst = "d") }))
		assertEquals(60, t.glossary.size)
		assertEquals(20, t.glossary.minOf { it.lastSeen })
		val u = trimProfile(
			base().copy(addressing = (0 until 30).map { AddressEntry("f$it", "t", "x", it) }),
		)
		assertEquals(20, u.addressing.size)
		assertEquals(10, u.addressing.minOf { it.lastSeen })
	}

	@Test
	fun trimKeepsPinnedEvenWithSmallestLastSeen() {
		val p = base().copy(
			glossary = listOf(ge(0, dst = "d", pinned = true)) + (0 until 70).map { ge(it + 1, dst = "d") },
		)
		val t = trimProfile(p)
		assertEquals(60, t.glossary.size)
		assertTrue(t.glossary.any { it.src == "s0" && it.pinned })
		assertFalse(t.glossary.any { it.src == "s11" })
		assertTrue(t.glossary.any { it.src == "s12" })
	}

	@Test
	fun trimFitsBytesDroppingRecentLinesBeforeGlossary() {
		val vi = "ậếỡờủạắẵữỳ".repeat(3)
		assertEquals(30, vi.length)
		val big = base().copy(
			glossary = (0 until 60).map { ge(it, dst = vi) },
			recentLines = List(5) { "ớ".repeat(200) },
		)
		assertTrue(profileBytes(big) > 4096)
		val t = trimProfile(big)
		assertTrue(profileBytes(t) <= 4096)
		assertTrue(t.recentLines.size < 5)

		// dropping recent lines alone is enough: glossary is kept whole
		val small = base().copy(
			glossary = (0 until 10).map { ge(it) },
			recentLines = (0 until 5).map { "$it" + "ớ".repeat(450) },
		)
		val t2 = trimProfile(small)
		assertTrue(profileBytes(t2) <= 4096)
		assertEquals(10, t2.glossary.size)
		assertTrue(t2.recentLines.size < 5)
		assertEquals(small.recentLines.takeLast(t2.recentLines.size), t2.recentLines)
	}

	@Test
	fun thirtyAsciiPinnedFitAfterTrim() {
		var p = base()
		for (i in 0 until 30) {
			val n = i.toString().padStart(2, '0')
			p = pin(p, "s$n".padEnd(32, 'x'), "d$n".padEnd(32, 'y'))
		}
		val t = trimProfile(p.copy(summary = "s".repeat(500), recentLines = listOf("ớ".repeat(200))))
		assertEquals(30, t.glossary.size)
		assertTrue(t.glossary.all { it.pinned })
		assertTrue(profileBytes(t) <= 4096)
	}

	@Test
	fun addPinnedRejectsThirtyFirstAndTooLongField() {
		var p = base()
		for (i in 0 until 30) p = pin(p, "s$i", "d$i")
		assertEquals(30, p.glossary.size)
		assertEquals(ProfileEditError.TOO_MANY, rejected(addPinned(p, "s30", "d30", "name")))
		assertEquals(ProfileEditError.TOO_LONG, rejected(addPinned(base(), "a".repeat(33), "b", "name")))
		assertEquals(ProfileEditError.TOO_LONG, rejected(addPinned(base(), "a", "b".repeat(33), "name")))
		assertEquals(1, ok(addPinned(base(), "a".repeat(32), "b".repeat(32), "name")).glossary.size)
	}

	@Test
	fun pinnedFieldLengthIsCountedInCodePoints() {
		val emoji = "😀".repeat(32) // 32 code points, 64 UTF-16 chars
		assertEquals(1, ok(addPinned(base(), emoji, "b", "name")).glossary.size)
		assertEquals(ProfileEditError.TOO_LONG, rejected(addPinned(base(), emoji + "a", "b", "name")))
	}

	@Test
	fun addPinnedKoreanFieldsRejectedBeforePinnedPartExceedsBytes() {
		var p = base()
		var error: ProfileEditError? = null
		for (i in 0 until 30) {
			val n = (0xac00 + i).toChar().toString()
			val res = addPinned(p, n.padEnd(32, '가'), n.padEnd(32, '나'), "name")
			if (res is ProfileEditResult.Rejected) {
				error = res.error
				break
			}
			p = ok(res)
			assertTrue(profileBytes(pinnedOnly(p)) <= 4096)
		}
		assertEquals(ProfileEditError.TOO_LARGE, error)
		val n = p.glossary.count { it.pinned }
		assertTrue(n > 0)
		assertTrue(n < 30)
		assertTrue(profileBytes(trimProfile(p)) <= 4096)
		assertEquals(n, trimProfile(p).glossary.size)
	}

	@Test
	fun addPinnedOnExistingUnpinnedEntryPinsItWithNewDst() {
		val p = base().copy(pageCounter = 4, glossary = listOf(ge(3, src = "a", dst = "cũ")))
		val q = ok(addPinned(p, "a", "mới", "name"))
		assertEquals(1, q.glossary.size)
		assertEquals(GlossaryEntry("a", "mới", "name", 3, true), q.glossary[0])
		assertFalse(p.glossary[0].pinned)
	}

	@Test
	fun removePinnedKeepsUnpinnedAndSetGenresCopies() {
		val p = pin(base().copy(glossary = listOf(ge(1, src = "free"))), "a", "b")
		val q = removePinned(p, "a")
		assertEquals(listOf("free"), q.glossary.map { it.src })
		assertEquals(1, removePinned(q, "free").glossary.size)
		val genres = mutableListOf("murim")
		val s = ok(setGenres(p, genres))
		genres.add("x")
		assertEquals(listOf("murim"), s.genres)
		assertTrue(p.genres.isEmpty())
	}

	@Test
	fun addPinnedRejectsBlankFields() {
		val p = base()
		val cases = listOf("" to "b", "   " to "b", "a" to "", "a" to " \t\n", "　" to "b")
		for ((src, dst) in cases) {
			assertEquals("$src/$dst", ProfileEditError.EMPTY, rejected(addPinned(p, src, dst, "name")))
		}
		assertTrue(p.glossary.isEmpty())
	}

	@Test
	fun addPinnedTrimsBeforeStoringAndMeasuring() {
		val q = ok(addPinned(base(), "  수희 ", " Su-hee  ", "name"))
		assertEquals("수희", q.glossary[0].src)
		assertEquals("Su-hee", q.glossary[0].dst)
		assertTrue(addPinned(base(), " " + "a".repeat(32) + " ", "b", "name") is ProfileEditResult.Ok)
		assertEquals(ProfileEditError.TOO_LONG, rejected(addPinned(base(), "a".repeat(33), "b", "name")))
		val r = ok(addPinned(q, " 수희 ", "Soo-hee", "name"))
		assertEquals(1, r.glossary.size)
		assertEquals("Soo-hee", r.glossary[0].dst)
	}

	/**
	 * Pinned entries (Korean, 32 chars) until addPinned refuses, then the last dst is padded with ASCII so that
	 * the pinned-only JSON is exactly [slack] bytes below the limit.
	 */
	private fun nearLimitPinned(slack: Int): SeriesProfile {
		var p = base()
		for (i in 0 until 30) {
			val n = (0xac00 + i).toChar().toString()
			val res = addPinned(p, n.padEnd(32, '가'), n.padEnd(32, '나'), "name")
			if (res !is ProfileEditResult.Ok) break
			p = res.profile
		}
		var pad = ProfileLimits.BYTES - profileBytes(p) - slack
		while (pad < 0) {
			p = removePinned(p, p.glossary.last().src)
			pad = ProfileLimits.BYTES - profileBytes(p) - slack
		}
		val last = p.glossary.last()
		p = p.copy(glossary = p.glossary.dropLast(1) + last.copy(dst = last.dst + "x".repeat(pad)))
		assertEquals(slack, ProfileLimits.BYTES - profileBytes(p))
		return p
	}

	@Test
	fun resetForLanguageKeepsPinnedAndGenresOnly() {
		val old = base().copy(
			genres = listOf("murim"),
			glossary = listOf(ge(1, src = "free", dst = "tự do"), ge(2, src = "pin", dst = "ghim", pinned = true)),
			addressing = listOf(AddressEntry("a", "b", "anh/em", 3)),
			summary = "tóm tắt cũ",
			recentLines = listOf("câu 1", "câu 2"),
			pageCounter = 9,
			lastChapter = 12L,
			lastReadAt = 111L,
		)
		val before = SeriesProfileCodec.encode(old)
		val p = resetForLanguage(old, "ko", "en", 5000L)
		assertEquals(
			emptyProfile("ko", "en", 5000L).copy(
				genres = listOf("murim"),
				glossary = listOf(ge(2, src = "pin", dst = "ghim", pinned = true)),
			),
			p,
		)
		assertEquals(before, SeriesProfileCodec.encode(old))
	}

	@Test
	fun resetForLanguageStaysWithinBytes() {
		assertTrue(resetForLanguage(base().copy(glossary = listOf(ge(1))), "ko", "en", 7L).glossary.isEmpty())

		val tight = nearLimitPinned(2)
		val kept = tight.glossary.size
		val p = resetForLanguage(tight.copy(genres = emptyList()), "ko", "zh-Hant", 0L)
		assertTrue(profileBytes(p) <= ProfileLimits.BYTES)
		assertEquals("zh-Hant", p.targetLang)
		assertTrue(p.glossary.all { it.pinned })
		// no genres to shed: the oldest pinned entry goes (last resort)
		assertEquals(kept - 1, p.glossary.size)

		// dropping genres comes first, pinned entries only as the very last resort
		val roomy = nearLimitPinned(9)
		val withGenres = resetForLanguage(roomy.copy(genres = listOf("murim")), "ko", "zh-Hant", 0L)
		assertTrue(profileBytes(withGenres) <= ProfileLimits.BYTES)
		assertEquals(roomy.glossary.size, withGenres.glossary.size)
		assertTrue(withGenres.genres.isEmpty())
	}

	@Test
	fun setGenresRejectedWhenPinnedPartWouldExceedBytes() {
		val tight = nearLimitPinned(3)
		assertEquals(ProfileEditError.TOO_LARGE, rejected(setGenres(tight, listOf("murim"))))
		assertTrue(tight.genres.isEmpty())
		val roomy = nearLimitPinned(40)
		val withGenre = ok(setGenres(roomy, listOf("murim")))
		assertEquals(listOf("murim"), withGenre.genres)
		assertTrue(profileBytes(pinnedOnly(withGenre)) <= ProfileLimits.BYTES)
		assertTrue(ok(setGenres(withGenre, emptyList())).genres.isEmpty())
		assertNotEquals(0, withGenre.glossary.size)
	}
}
