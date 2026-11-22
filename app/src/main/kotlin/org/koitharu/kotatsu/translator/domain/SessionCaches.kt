package org.koitharu.kotatsu.translator.domain

import org.koitharu.kotatsu.translator.domain.model.OcrMode
import org.koitharu.kotatsu.translator.domain.model.TextBlock
import org.koitharu.kotatsu.translator.domain.model.TranslatedBlock

/**
 * Thread safe LRU map kept in memory only. Reading an entry makes it the newest; the oldest entry is evicted
 * when the size exceeds [capacity].
 */
class LruCache<K : Any, V : Any>(private val capacity: Int) {

	private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > capacity
	}

	init {
		require(capacity > 0) { "capacity must be positive" }
	}

	val size: Int
		get() = synchronized(map) { map.size }

	operator fun get(key: K): V? = synchronized(map) { map[key] }

	operator fun set(key: K, value: V) {
		synchronized(map) { map[key] = value }
	}

	fun remove(key: K) {
		synchronized(map) { map.remove(key) }
	}

	fun clear() {
		synchronized(map) { map.clear() }
	}
}

/**
 * Finished page translations and OCR results of the current session. Memory only: never written to disk
 * (NFR-06). Only complete results are stored (an empty list = a page without text); partial results and
 * failures must not be cached by the caller.
 */
class SessionCaches(
	translationCapacity: Int = TRANSLATION_CAPACITY,
	ocrCapacity: Int = OCR_CAPACITY,
) {

	val translations = LruCache<String, List<TranslatedBlock>>(translationCapacity)
	val ocr = LruCache<String, List<TextBlock>>(ocrCapacity)

	fun clear() {
		translations.clear()
		ocr.clear()
	}

	companion object {

		const val TRANSLATION_CAPACITY = 400
		const val OCR_CAPACITY = 200

		/** Key of a translated page: `source|target|ocrMode|pageId`; the language pair and mode are part of it */
		fun translationKey(source: String, target: String, mode: OcrMode, pageId: Long): String =
			"$source|$target|${mode.name}|$pageId"

		/** Key of an OCR result: `mode|lang|pageId` */
		fun ocrKey(mode: OcrMode, lang: String, pageId: Long): String = "${mode.name}|$lang|$pageId"
	}
}
