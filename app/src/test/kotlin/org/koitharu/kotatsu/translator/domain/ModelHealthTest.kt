package org.koitharu.kotatsu.translator.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.translator.domain.model.AiProvider

class ModelHealthTest {

	private var t = 0L
	private val health = ModelHealth { t }

	@Test
	fun healthyByDefaultAndCooldownIs30sAfterFirstFailure() {
		assertTrue(health.isHealthy(AiProvider.GEMINI, "m"))
		health.markFailed(AiProvider.GEMINI, "m")
		assertFalse(health.isHealthy(AiProvider.GEMINI, "m"))
		t = 29_999
		assertFalse(health.isHealthy(AiProvider.GEMINI, "m"))
		t = 30_000
		assertTrue(health.isHealthy(AiProvider.GEMINI, "m"))
	}

	@Test
	fun consecutiveFailuresDoubleCooldownUpTo300s() {
		val cooldowns = mutableListOf<Long>()
		repeat(6) {
			health.markFailed(AiProvider.GEMINI, "m")
			var ms = 0L
			while (!health.isHealthy(AiProvider.GEMINI, "m")) {
				t++
				ms++
			}
			cooldowns.add(ms)
		}
		assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L), cooldowns)
	}

	@Test
	fun retryAfterOverridesCooldownAndMarkOkResets() {
		health.markFailed(AiProvider.GEMINI, "m", 5_000L)
		t = 4_999
		assertFalse(health.isHealthy(AiProvider.GEMINI, "m"))
		t = 5_000
		assertTrue(health.isHealthy(AiProvider.GEMINI, "m"))
		health.markFailed(AiProvider.GEMINI, "m")
		health.markFailed(AiProvider.GEMINI, "m")
		health.markOk(AiProvider.GEMINI, "m")
		assertTrue(health.isHealthy(AiProvider.GEMINI, "m"))
		health.markFailed(AiProvider.GEMINI, "m")
		t += 30_000
		assertTrue(health.isHealthy(AiProvider.GEMINI, "m")) // back to 30 s, not 120 s
	}

	@Test
	fun modelsAndProvidersAreIndependent() {
		health.markFailed(AiProvider.GEMINI, "a")
		assertTrue(health.isHealthy(AiProvider.GEMINI, "b"))
		assertTrue(health.isHealthy(AiProvider.CLAUDE, "a"))
	}

	@Test
	fun pickAndAlternative() {
		val chain = listOf("main", "fb1", "fb2")
		assertEquals("main", health.pick(AiProvider.GEMINI, chain))
		health.markFailed(AiProvider.GEMINI, "main")
		assertEquals("fb1", health.pick(AiProvider.GEMINI, chain))
		assertEquals("fb1", health.alternative(AiProvider.GEMINI, chain, "main"))
		assertEquals("fb2", health.alternative(AiProvider.GEMINI, chain, "fb1"))
		health.markFailed(AiProvider.GEMINI, "fb1")
		health.markFailed(AiProvider.GEMINI, "fb2")
		assertEquals("main", health.pick(AiProvider.GEMINI, chain)) // nothing healthy: the primary
		assertNull(health.alternative(AiProvider.GEMINI, chain, "main"))
	}
}
