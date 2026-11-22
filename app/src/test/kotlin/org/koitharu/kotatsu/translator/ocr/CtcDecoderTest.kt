package org.koitharu.kotatsu.translator.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class CtcDecoderTest {

	@Test
	fun mergesRepeatsDropsBlankAndMapsOutOfDictToSpace() {
		val dict = listOf("a", "b")
		val classes = 4
		// argmax per frame: a(.9) a(.8) blank(.7) a(.6) class3(.5) b(.4)
		val frames = listOf(1 to 0.9f, 1 to 0.8f, 0 to 0.7f, 1 to 0.6f, 3 to 0.5f, 2 to 0.4f)
		val data = FloatArray(frames.size * classes)
		frames.forEachIndexed { t, (c, v) -> data[t * classes + c] = v }
		val r = CtcDecoder.decode(data, frames.size, classes, dict)
		assertEquals("aa b", r.text)
		assertEquals((0.9 + 0.6 + 0.5 + 0.4) / 4, r.conf, 1e-5)
	}

	@Test
	fun allBlankGivesEmptyTextAndZeroConf() {
		val data = FloatArray(3 * 3)
		for (t in 0 until 3) data[t * 3] = 0.99f
		assertEquals(CtcResult("", 0.0), CtcDecoder.decode(data, 3, 3, listOf("a", "b")))
	}
}
