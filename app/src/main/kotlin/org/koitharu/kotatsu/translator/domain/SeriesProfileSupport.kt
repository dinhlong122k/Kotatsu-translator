package org.koitharu.kotatsu.translator.domain

import org.koitharu.kotatsu.translator.ai.PromptAddress
import org.koitharu.kotatsu.translator.ai.PromptContext
import org.koitharu.kotatsu.translator.ai.PromptGlossaryEntry
import org.koitharu.kotatsu.translator.ai.PromptTerm
import org.koitharu.kotatsu.translator.context.SeriesProfile
import org.koitharu.kotatsu.translator.context.emptyProfile
import org.koitharu.kotatsu.translator.context.resetForLanguage
import org.koitharu.kotatsu.translator.domain.model.LanguagePair

/**
 * The profile to build on: an empty one, the stored one, or the stored one restarted when the language pair has
 * changed since it was written (only pinned names and genres survive). Nothing is written to the database here.
 */
fun baseProfileFor(stored: SeriesProfile?, pair: LanguagePair, now: Long): SeriesProfile = when {
	stored == null -> emptyProfile(pair.source, pair.target, now)
	stored.sourceLang != pair.source || stored.targetLang != pair.target ->
		resetForLanguage(stored, pair.source, pair.target, now)

	else -> stored
}

/** Series knowledge in the form the prompt builder expects: pinned names first, learned glossary apart. */
fun SeriesProfile.toPromptContext(): PromptContext = PromptContext(
	pinned = glossary.filter { it.pinned }.map { PromptTerm(it.src, it.dst) },
	glossary = glossary.filter { !it.pinned }.map { PromptGlossaryEntry(it.src, it.dst, it.kind) },
	addressing = addressing.map { PromptAddress(it.from, it.to, it.form) },
	summary = summary,
	recentLines = recentLines,
)
