package org.koitharu.kotatsu.translator.context

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import org.koitharu.kotatsu.core.db.entity.MangaEntity

@Entity(
	tableName = "translation_contexts",
	foreignKeys = [
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
)
class TranslationContextEntity(
	@PrimaryKey(autoGenerate = false)
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	/** Serialized series profile (JSON, up to 4096 bytes) */
	@ColumnInfo(name = "data") val data: String,
	@ColumnInfo(name = "last_read_at") val lastReadAt: Long,
)
