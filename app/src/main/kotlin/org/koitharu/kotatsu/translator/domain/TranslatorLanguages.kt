package org.koitharu.kotatsu.translator.domain

import org.koitharu.kotatsu.translator.ocr.OcrModels

/**
 * Choice of the language of the text on the pages.
 */
object TranslatorLanguages {

	/**
	 * @param override language chosen by the user, null = by the manga source
	 * @param sourceLanguage language of the manga source (ISO code like "ko", "ja"), null if unknown
	 * @param target target language code
	 * @return the OCR language, or null if the text is already in the target language (nothing to translate)
	 */
	fun resolveSource(override: String?, sourceLanguage: String?, target: String): String? {
		val chosen = override?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
		if (chosen != null) {
			return if (chosen == target) null else chosen
		}
		val fromSource = sourceLanguage?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
		if (fromSource != null && fromSource == target) {
			return null
		}
		return if (OcrModels.isSourceLang(fromSource)) fromSource else OcrModels.DEFAULT_SOURCE_LANG
	}
}
