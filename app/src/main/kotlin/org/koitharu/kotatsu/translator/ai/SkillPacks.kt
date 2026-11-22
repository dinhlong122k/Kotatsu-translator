package org.koitharu.kotatsu.translator.ai

import android.content.Context
import android.content.res.AssetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONException
import org.json.JSONObject
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.translator.domain.model.LanguagePair
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Translation conventions for a language pair or a genre, rendered into the system prompt.
 * [reviewed] is false for packs not yet checked by a native speaker.
 */
data class SkillPack(
	val id: String,
	val version: Int,
	/** Like "ko-vi", or [GENERIC_PAIR] for the pack that is used when a pair has no pack of its own */
	val langPair: String,
	/** Genre id (like "murim") or null for a base pack */
	val genre: String?,
	val title: String,
	val instructions: String,
	val terms: List<SkillTerm>,
	val reviewed: Boolean,
) {

	companion object {

		const val GENERIC_PAIR = "generic"
	}
}

data class SkillTerm(val src: String, val dst: String)

/**
 * Genre that can be enabled for a series: [id] is stored in the series context, [title] is shown in the UI.
 */
data class GenreInfo(val id: String, val title: String)

/**
 * Skill packs from `assets/translator/packs`. The files are read once, on first use (blocking IO: call from a
 * background thread). Parsing and selection are in [SkillPackRegistry] and do not depend on Android.
 */
@Singleton
class SkillPacks @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	private val packs: List<SkillPack> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
		SkillPackRegistry.loadFromAssets(context.assets)
	}

	/**
	 * Packs for the [pair] sorted by id: the base pack and the packs of the enabled [genres]. If there is no base pack
	 * for the pair, the generic pack is returned instead (and no genre packs).
	 */
	fun select(pair: LanguagePair, genres: Set<String>): List<SkillPack> =
		SkillPackRegistry.select(packs, SkillPackRegistry.pairKey(pair), genres)

	/**
	 * Genres that have a pack for the [pair].
	 */
	fun genres(pair: LanguagePair): List<GenreInfo> = SkillPackRegistry.genres(packs, SkillPackRegistry.pairKey(pair))
}

internal object SkillPackRegistry {

	const val ASSETS_DIR = "translator/packs"

	fun pairKey(pair: LanguagePair) = pair.source + "-" + pair.target

	fun loadFromAssets(assets: AssetManager): List<SkillPack> {
		val names = try {
			assets.list(ASSETS_DIR).orEmpty()
		} catch (e: java.io.IOException) {
			e.printStackTraceDebug()
			emptyArray<String>()
		}
		return names.filter { it.endsWith(".json") }.sorted().mapNotNull { name ->
			try {
				val text = assets.open("$ASSETS_DIR/$name").use { it.readBytes().toString(Charsets.UTF_8) }
				parse(text)
			} catch (e: java.io.IOException) {
				e.printStackTraceDebug()
				null
			}
		}
	}

	/**
	 * @return null if the text is not valid JSON or misses a required field (id, langPair, title, instructions, version)
	 */
	fun parse(json: String): SkillPack? {
		val o = try {
			JSONObject(json)
		} catch (e: JSONException) {
			return null
		}
		val id = o.opt("id") as? String ?: return null
		val langPair = o.opt("langPair") as? String ?: return null
		val title = o.opt("title") as? String ?: return null
		val instructions = o.opt("instructions") as? String ?: return null
		val version = (o.opt("version") as? Number)?.toInt() ?: return null
		val genreRaw = o.opt("genre")
		if (genreRaw != null && genreRaw != JSONObject.NULL && genreRaw !is String) return null
		val genre = genreRaw as? String
		val terms = ArrayList<SkillTerm>()
		val termsRaw = o.opt("terms")
		if (termsRaw != null && termsRaw != JSONObject.NULL) {
			val arr = termsRaw as? org.json.JSONArray ?: return null
			for (i in 0 until arr.length()) {
				val t = arr.optJSONObject(i) ?: return null
				val src = t.opt("src") as? String ?: return null
				val dst = t.opt("dst") as? String ?: return null
				terms.add(SkillTerm(src, dst))
			}
		}
		val reviewed = o.opt("reviewed") as? Boolean ?: true
		return SkillPack(id, version, langPair, genre, title, instructions, terms, reviewed)
	}

	/**
	 * Packs of [pairKey] ("ko-vi") whose genre is not set or is in [genres], sorted by id. Falls back to the generic
	 * pack when there is no base pack for the pair.
	 */
	fun select(all: List<SkillPack>, pairKey: String, genres: Set<String>): List<SkillPack> {
		val own = all.filter { it.langPair == pairKey }
		if (own.none { it.genre == null }) {
			return all.filter { it.langPair == SkillPack.GENERIC_PAIR && it.genre == null }.sortedBy { it.id }
		}
		return own.filter { it.genre == null || it.genre in genres }.sortedBy { it.id }
	}

	fun genres(all: List<SkillPack>, pairKey: String): List<GenreInfo> = all
		.filter { it.langPair == pairKey && it.genre != null }
		.sortedBy { it.id }
		.map { GenreInfo(it.genre!!, it.title) }
}
