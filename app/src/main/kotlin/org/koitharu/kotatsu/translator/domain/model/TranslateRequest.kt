package org.koitharu.kotatsu.translator.domain.model

data class TranslateRequest(
	val pair: LanguagePair,
	val stablePrompt: String,
	val dynamicPrompt: String,
	val blockIds: List<String>,
)

data class TranslateResult(
	val translations: Map<String, String>,
	val glossary: List<GlossaryUpdate>,
	val addressing: List<AddressUpdate>,
	val summary: String,
)

/** [kind] is one of: name, place, term */
data class GlossaryUpdate(val src: String, val dst: String, val kind: String)

data class AddressUpdate(val from: String, val to: String, val form: String)
