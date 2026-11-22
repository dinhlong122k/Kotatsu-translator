package org.koitharu.kotatsu.translator.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

internal object SkillPackTestSupport {

	val dir = File("src/main/assets/translator/packs")

	fun files(): List<File> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }

	fun loadAll(): List<SkillPack> = files().map {
		SkillPackRegistry.parse(it.readText(Charsets.UTF_8)) ?: throw AssertionError("Invalid pack: ${it.name}")
	}
}

class SkillPackRegistryTest {

	private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

	@Test
	fun everyPackFileIsValidJsonWithRequiredFields() {
		val files = SkillPackTestSupport.files()
		assertEquals(21, files.size)
		for (f in files) {
			assertNotNull(f.name, SkillPackRegistry.parse(f.readText(Charsets.UTF_8)))
		}
	}

	@Test
	fun idsAreUniqueAndTheBundledSetIsComplete() {
		val ids = SkillPackTestSupport.loadAll().map { it.id }
		assertEquals(ids.size, ids.toSet().size)
		val expected = setOf(
			"generic",
			"ko-vi.base", "genre.murim", "genre.office", "genre.school", "genre.system",
		) + listOf("ja-vi", "zh-vi", "en-vi").flatMap { p ->
			listOf("$p.base") + listOf("murim", "office", "school", "system").map { "$p.genre.$it" }
		}
		assertEquals(expected, ids.toSet())
	}

	@Test
	fun newPacksAreMarkedUnreviewed() {
		for (p in SkillPackTestSupport.loadAll()) {
			if (p.langPair == "ko-vi") assertTrue(p.id, p.reviewed) else assertFalse(p.id, p.reviewed)
		}
	}

	@Test
	fun sizeLimitsOfTheOriginalRegistry() {
		for (p in SkillPackTestSupport.loadAll()) {
			if (p.genre == null) {
				assertTrue(p.id, bytes(p.instructions) <= 2000)
			} else {
				val terms = p.terms.joinToString("") { it.src + it.dst }
				assertTrue(p.id, bytes(p.instructions) + bytes(terms) <= 1000)
			}
		}
	}

	@Test
	fun genrePacksHaveTheSameGenresInEveryPair() {
		val all = SkillPackTestSupport.loadAll()
		for (key in listOf("ko-vi", "ja-vi", "zh-vi", "en-vi")) {
			assertEquals(key, listOf("murim", "office", "school", "system"), SkillPackRegistry.genres(all, key).map { it.id }.sorted())
		}
		assertTrue(SkillPackRegistry.genres(all, "ko-en").isEmpty())
		assertTrue(SkillPackRegistry.genres(all, "generic").isEmpty())
	}

	@Test
	fun selectKoViWithMurim() {
		val all = SkillPackTestSupport.loadAll()
		assertEquals(listOf("genre.murim", "ko-vi.base"), SkillPackRegistry.select(all, "ko-vi", setOf("murim")).map { it.id })
		assertEquals(listOf("ko-vi.base"), SkillPackRegistry.select(all, "ko-vi", emptySet()).map { it.id })
	}

	@Test
	fun selectUsesOnlyPacksOfThePair() {
		val all = SkillPackTestSupport.loadAll()
		assertEquals(listOf("ja-vi.base"), SkillPackRegistry.select(all, "ja-vi", emptySet()).map { it.id })
		assertEquals(
			listOf("zh-vi.base", "zh-vi.genre.system"),
			SkillPackRegistry.select(all, "zh-vi", setOf("system", "unknown")).map { it.id },
		)
	}

	@Test
	fun unknownPairFallsBackToGenericAndIgnoresGenres() {
		val all = SkillPackTestSupport.loadAll()
		assertEquals(listOf("generic"), SkillPackRegistry.select(all, "ko-en", setOf("murim")).map { it.id })
	}

	@Test
	fun genreTitlesComeFromThePack() {
		val genres = SkillPackRegistry.genres(SkillPackTestSupport.loadAll(), "ko-vi")
		assertEquals("Võ hiệp / Murim", genres.first { it.id == "murim" }.title)
	}

	@Test
	fun parseValidation() {
		val ok = """{"id":"a","version":1,"langPair":"ko-vi","title":"t","instructions":"i"}"""
		val p = SkillPackRegistry.parse(ok)
		assertNotNull(p)
		assertTrue(p!!.terms.isEmpty())
		assertNull(p.genre)
		assertTrue(p.reviewed)
		assertNull(SkillPackRegistry.parse("""{"id":"a","version":1,"langPair":"ko-vi","title":"t"}"""))
		assertNull(SkillPackRegistry.parse("""{"id":"a","version":1,"langPair":"ko-vi","title":"t","instructions":"i","terms":"x"}"""))
		assertNull(SkillPackRegistry.parse("""{"id":"a","version":1,"langPair":"ko-vi","title":"t","instructions":"i","terms":[{"src":"a"}]}"""))
		assertNull(SkillPackRegistry.parse("""{"id":"a","langPair":"ko-vi","title":"t","instructions":"i"}"""))
		assertNull(SkillPackRegistry.parse("not json"))
		assertEquals(false, SkillPackRegistry.parse(ok.dropLast(1) + ""","reviewed":false}""")!!.reviewed)
	}
}
