package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration27To28 : Migration(27, 28) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("CREATE TABLE IF NOT EXISTS `translation_contexts` (`manga_id` INTEGER NOT NULL, `data` TEXT NOT NULL, `last_read_at` INTEGER NOT NULL, PRIMARY KEY(`manga_id`), FOREIGN KEY(`manga_id`) REFERENCES `manga`(`manga_id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
	}
}
