package org.koitharu.kotatsu.translator.context

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface TranslationContextDao {

	@Query("SELECT * FROM translation_contexts WHERE manga_id = :mangaId")
	suspend fun find(mangaId: Long): TranslationContextEntity?

	@Upsert
	suspend fun upsert(entity: TranslationContextEntity)

	@Query("DELETE FROM translation_contexts WHERE manga_id = :mangaId")
	suspend fun delete(mangaId: Long)

	@Query("DELETE FROM translation_contexts WHERE last_read_at < :timestamp")
	suspend fun deleteOlderThan(timestamp: Long)

	@Query("DELETE FROM translation_contexts")
	suspend fun deleteAll()
}
