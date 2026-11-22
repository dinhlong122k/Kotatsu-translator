package org.koitharu.kotatsu.translator.context

import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.util.MultiMutex
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores one [SeriesProfile] per manga (key: `manga_id`) as JSON in the `translation_contexts` table.
 */
@Singleton
class SeriesProfileRepository @Inject constructor(
	private val db: MangaDatabase,
) {

	private val mutex = MultiMutex<Long>()

	/** Returns null if there is no profile or its stored JSON is corrupt */
	suspend fun get(mangaId: Long): SeriesProfile? = decode(db.getTranslationContextDao().find(mangaId)?.data)

	/**
	 * Read-modify-write under a lock per [mangaId]. If [transform] returns null nothing is written.
	 * [transform] receives null when there is no (or a corrupt) profile.
	 * @return the profile that was written, or null if nothing was written
	 */
	suspend fun update(mangaId: Long, transform: (SeriesProfile?) -> SeriesProfile?): SeriesProfile? {
		return mutex.withLock(mangaId) {
			val dao = db.getTranslationContextDao()
			val current = decode(dao.find(mangaId)?.data)
			val updated = transform(current)
			if (updated != null) {
				dao.upsert(
					TranslationContextEntity(
						mangaId = mangaId,
						data = SeriesProfileCodec.encode(updated),
						lastReadAt = updated.lastReadAt,
					),
				)
			}
			updated
		}
	}

	suspend fun clear(mangaId: Long) {
		mutex.withLock(mangaId) {
			db.getTranslationContextDao().delete(mangaId)
		}
	}

	suspend fun clearAll() {
		db.getTranslationContextDao().deleteAll()
	}

	/** Deletes profiles not read for more than [retentionDays] days (clamped to 1..30) */
	suspend fun deleteExpired(retentionDays: Int, now: Long = System.currentTimeMillis()) {
		val days = retentionDays.coerceIn(1, 30)
		db.getTranslationContextDao().deleteOlderThan(now - TimeUnit.DAYS.toMillis(days.toLong()))
	}

	private fun decode(data: String?): SeriesProfile? {
		if (data == null) return null
		return runCatchingCancellable { SeriesProfileCodec.decode(data) }
			.onFailure { it.printStackTraceDebug() }
			.getOrNull()
	}
}
