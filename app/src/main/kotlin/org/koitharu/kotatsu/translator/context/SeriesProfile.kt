package org.koitharu.kotatsu.translator.context

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.translator.domain.model.TranslateResult

/**
 * Per-series translation context (glossary, forms of address, summary, recent lines).
 * Pure data and pure functions, ported from `profile.ts`. All functions return new instances.
 */
@Serializable
data class SeriesProfile(
	val sourceLang: String,
	val targetLang: String,
	val genres: List<String> = emptyList(),
	val glossary: List<GlossaryEntry> = emptyList(),
	val addressing: List<AddressEntry> = emptyList(),
	val summary: String = "",
	val recentLines: List<String> = emptyList(),
	val pageCounter: Int = 0,
	val lastChapter: Long = 0L,
	val lastReadAt: Long = 0L,
)

/** [kind] is one of: name, place, term. [pinned] entries are set by the user and never replaced by the AI. */
@Serializable
data class GlossaryEntry(
	val src: String,
	val dst: String,
	val kind: String,
	val lastSeen: Int,
	/** Not encoded when false (like an absent key in the web version) to save bytes of the 4096 budget */
	@EncodeDefault(EncodeDefault.Mode.NEVER)
	val pinned: Boolean = false,
)

@Serializable
data class AddressEntry(
	val from: String,
	val to: String,
	val form: String,
	val lastSeen: Int,
)

object ProfileLimits {

	const val GLOSSARY = 60
	const val ADDRESSING = 20
	const val SUMMARY = 500
	const val RECENT_LINES = 5
	const val BYTES = 4096
	const val PINNED = 30

	/** Counted in Unicode code points */
	const val PINNED_FIELD = 32
}

/**
 * The single [Json] configuration used both to measure the size of a profile ([profileBytes]) and to store it
 * ([SeriesProfileRepository]). Everything is encoded except `pinned = false`.
 */
object SeriesProfileCodec {

	private val json = Json {
		ignoreUnknownKeys = true
		coerceInputValues = true
		encodeDefaults = true
	}

	fun encode(profile: SeriesProfile): String = json.encodeToString(SeriesProfile.serializer(), profile)

	/** @throws kotlinx.serialization.SerializationException (or IllegalArgumentException) if [data] is corrupt */
	fun decode(data: String): SeriesProfile = json.decodeFromString(SeriesProfile.serializer(), data)
}

enum class ProfileEditError {
	/** src or dst is blank */
	EMPTY,

	/** a field is longer than [ProfileLimits.PINNED_FIELD] code points */
	TOO_LONG,

	/** more than [ProfileLimits.PINNED] pinned entries */
	TOO_MANY,

	/** the part of the profile that can never be trimmed would exceed [ProfileLimits.BYTES] */
	TOO_LARGE,
}

sealed interface ProfileEditResult {

	data class Ok(val profile: SeriesProfile) : ProfileEditResult

	data class Rejected(val error: ProfileEditError) : ProfileEditResult
}

fun emptyProfile(sourceLang: String, targetLang: String, now: Long): SeriesProfile = SeriesProfile(
	sourceLang = sourceLang,
	targetLang = targetLang,
	lastReadAt = now,
)

/** Size in bytes of the exact JSON string that gets stored */
fun profileBytes(p: SeriesProfile): Int = SeriesProfileCodec.encode(p).toByteArray(Charsets.UTF_8).size

/**
 * Merges the result of a successful translation request.
 * @param pages number of pages the request covered
 * @param chapter id of the chapter being read
 */
fun mergeResult(p: SeriesProfile, r: TranslateResult, chapter: Long, now: Long, pages: Int = 1): SeriesProfile {
	val pageCounter = p.pageCounter + pages

	val glossary = p.glossary.toMutableList()
	for (e in r.glossary) {
		val i = glossary.indexOfFirst { it.src == e.src }
		when {
			i < 0 -> glossary.add(GlossaryEntry(e.src, e.dst, e.kind, pageCounter))
			glossary[i].pinned -> glossary[i] = glossary[i].copy(lastSeen = pageCounter)
			else -> glossary[i] = GlossaryEntry(e.src, e.dst, e.kind, pageCounter)
		}
	}

	val addressing = p.addressing.toMutableList()
	for (e in r.addressing) {
		val i = addressing.indexOfFirst { it.from == e.from && it.to == e.to }
		val next = AddressEntry(e.from, e.to, e.form, pageCounter)
		if (i < 0) addressing.add(next) else addressing[i] = next
	}

	val recentLines = (p.recentLines + r.translations.values).takeLast(ProfileLimits.RECENT_LINES)

	return p.copy(
		glossary = glossary,
		addressing = addressing,
		recentLines = recentLines,
		summary = if (r.summary.isEmpty()) p.summary else r.summary,
		pageCounter = pageCounter,
		lastChapter = chapter,
		lastReadAt = now,
	)
}

/**
 * Keeps at most [max] items with the highest lastSeen, preserving the original order.
 * [locked] items are always kept and reduce the room for the others.
 */
private fun <T> keepNewest(
	items: List<T>,
	max: Int,
	lastSeen: (T) -> Int,
	locked: (T) -> Boolean = { false },
): List<T> {
	val freeIndices = items.indices.filter { !locked(items[it]) }
	val lockedCount = items.size - freeIndices.size
	val room = maxOf(0, max - lockedCount)
	if (freeIndices.size <= room) return items
	val keep = freeIndices
		.sortedWith(compareByDescending<Int> { lastSeen(items[it]) }.thenByDescending { it })
		.take(room)
		.toSet()
	return items.filterIndexed { i, t -> locked(t) || i in keep }
}

/** Removes the unpinned entry with the smallest lastSeen (the last one among equals); null if there is none. */
private fun dropOldestUnpinned(glossary: List<GlossaryEntry>): List<GlossaryEntry>? {
	var idx = -1
	glossary.forEachIndexed { i, g ->
		if (!g.pinned && (idx < 0 || g.lastSeen <= glossary[idx].lastSeen)) idx = i
	}
	return if (idx < 0) null else glossary.filterIndexed { i, _ -> i != idx }
}

/** Cuts to [max] UTF-16 chars without leaving half of a surrogate pair at the end. */
private fun String.truncate(max: Int): String {
	if (length <= max) return this
	val end = if (max > 0 && this[max - 1].isHighSurrogate()) max - 1 else max
	return substring(0, end)
}

/**
 * Brings the profile within all limits. Order of shedding: summary length, glossary and addressing counts,
 * then to fit [ProfileLimits.BYTES]: recent lines, oldest unpinned glossary, oldest addressing, summary.
 */
fun trimProfile(p: SeriesProfile): SeriesProfile {
	var t = p.copy(
		summary = p.summary.truncate(ProfileLimits.SUMMARY),
		glossary = keepNewest(p.glossary, ProfileLimits.GLOSSARY, { it.lastSeen }, { it.pinned }),
		addressing = keepNewest(p.addressing, ProfileLimits.ADDRESSING, { it.lastSeen }),
		recentLines = p.recentLines.takeLast(ProfileLimits.RECENT_LINES),
	)

	while (profileBytes(t) > ProfileLimits.BYTES && t.recentLines.isNotEmpty()) {
		t = t.copy(recentLines = t.recentLines.drop(1))
	}
	while (profileBytes(t) > ProfileLimits.BYTES) {
		val g = dropOldestUnpinned(t.glossary) ?: break
		t = t.copy(glossary = g)
	}
	// Last resort: pinned entries are guaranteed to fit by addPinned, so shed other fields.
	while (profileBytes(t) > ProfileLimits.BYTES && t.addressing.isNotEmpty()) {
		var oldest = 0
		t.addressing.forEachIndexed { k, a -> if (a.lastSeen < t.addressing[oldest].lastSeen) oldest = k }
		t = t.copy(addressing = t.addressing.filterIndexed { k, _ -> k != oldest })
	}
	if (profileBytes(t) > ProfileLimits.BYTES) t = t.copy(summary = "")
	return t
}

/** The part of a profile that [trimProfile] can never shed; it must always fit in [ProfileLimits.BYTES]. */
fun pinnedOnly(p: SeriesProfile): SeriesProfile = p.copy(
	glossary = p.glossary.filter { it.pinned },
	addressing = emptyList(),
	summary = "",
	recentLines = emptyList(),
)

/** Pins a name. [src] and [dst] are trimmed. A rejected edit leaves [p] unchanged (it is immutable anyway). */
fun addPinned(p: SeriesProfile, src: String, dst: String, kind: String): ProfileEditResult {
	val s = src.trim()
	val d = dst.trim()
	if (s.isEmpty() || d.isEmpty()) return ProfileEditResult.Rejected(ProfileEditError.EMPTY)
	if (s.codePointCount(0, s.length) > ProfileLimits.PINNED_FIELD ||
		d.codePointCount(0, d.length) > ProfileLimits.PINNED_FIELD
	) {
		return ProfileEditResult.Rejected(ProfileEditError.TOO_LONG)
	}
	val existingIndex = p.glossary.indexOfFirst { it.src == s }
	val otherPinned = p.glossary.count { it.pinned && it.src != s }
	if (otherPinned >= ProfileLimits.PINNED) return ProfileEditResult.Rejected(ProfileEditError.TOO_MANY)
	val entry = GlossaryEntry(
		src = s,
		dst = d,
		kind = kind,
		lastSeen = if (existingIndex >= 0) p.glossary[existingIndex].lastSeen else p.pageCounter,
		pinned = true,
	)
	val glossary = if (existingIndex >= 0) {
		p.glossary.mapIndexed { i, g -> if (i == existingIndex) entry else g }
	} else {
		p.glossary + entry
	}
	val next = p.copy(glossary = glossary)
	if (profileBytes(pinnedOnly(next)) > ProfileLimits.BYTES) {
		return ProfileEditResult.Rejected(ProfileEditError.TOO_LARGE)
	}
	return ProfileEditResult.Ok(next)
}

fun removePinned(p: SeriesProfile, src: String): SeriesProfile =
	p.copy(glossary = p.glossary.filter { !(it.pinned && it.src == src) })

/** Genres are part of the JSON that [trimProfile] cannot shed, so they obey the same guard as [addPinned]. */
fun setGenres(p: SeriesProfile, genres: List<String>): ProfileEditResult {
	val next = p.copy(genres = genres.toList())
	if (profileBytes(pinnedOnly(next)) > ProfileLimits.BYTES) {
		return ProfileEditResult.Rejected(ProfileEditError.TOO_LARGE)
	}
	return ProfileEditResult.Ok(next)
}

/**
 * The series is now read with another language pair: restart from an empty profile for it, keeping only what
 * the user set by hand (pinned entries, genres). Stays within [ProfileLimits.BYTES] even if the new language
 * code is longer.
 */
fun resetForLanguage(p: SeriesProfile, sourceLang: String, targetLang: String, now: Long): SeriesProfile {
	var t = emptyProfile(sourceLang, targetLang, now).copy(
		genres = p.genres,
		glossary = p.glossary.filter { it.pinned },
	)
	if (profileBytes(t) > ProfileLimits.BYTES) t = t.copy(genres = emptyList())
	// Last resort: only reachable when the pinned set was within a few bytes of the limit.
	while (profileBytes(t) > ProfileLimits.BYTES && t.glossary.isNotEmpty()) {
		var oldest = 0
		t.glossary.forEachIndexed { i, g -> if (g.lastSeen < t.glossary[oldest].lastSeen) oldest = i }
		t = t.copy(glossary = t.glossary.filterIndexed { i, _ -> i != oldest })
	}
	return t
}
