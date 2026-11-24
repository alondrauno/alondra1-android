package com.alondra1.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Versioned schema; JSON schemas are exported to app/schemas and checked by migration tests.
 * Production builds never use destructive migrations.
 */
@Database(
    entities = [TaskListEntity::class, TaskEntity::class, NoteEntity::class, EventEntity::class, OutboxEntity::class, SyncIssueEntity::class,
        NoteFts::class, NoteFolderEntity::class, NoteAssetEntity::class],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskLists(): TaskListDao
    abstract fun tasks(): TaskDao
    abstract fun notes(): NoteDao
    abstract fun events(): EventDao
    abstract fun maintenance(): MaintenanceDao
    abstract fun sync(): SyncDao
    abstract fun noteFolders(): NoteFolderDao
    abstract fun noteAssets(): NoteAssetDao

    companion object {
        const val NAME = "alondra1.db"

        /** 1 -> 2: tables for optional Cloud Sync (outbox and sync issues). Existing data is untouched. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_outbox` (`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `entityType` TEXT NOT NULL, `entityId` TEXT NOT NULL, `mutationId` TEXT NOT NULL, `attempted` INTEGER NOT NULL, `createdUtc` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_outbox_entityType_entityId` ON `sync_outbox` (`entityType`, `entityId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_issues` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `entityType` TEXT NOT NULL, `entityId` TEXT NOT NULL, `title` TEXT NOT NULL, `message` TEXT NOT NULL, `createdUtc` INTEGER NOT NULL)")
            }
        }

        /**
         * 2 -> 3: rich notes (Notes spec). New note columns with defaults (existing plain-text notes stay valid and show
         * as paragraphs), note folders, note pictures and a full-text index filled from the existing notes.
         */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                listOf(
                    "ALTER TABLE `notes` ADD COLUMN `docJson` TEXT",
                    "ALTER TABLE `notes` ADD COLUMN `folderId` TEXT",
                    "ALTER TABLE `notes` ADD COLUMN `favorite` INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE `notes` ADD COLUMN `archivedUtc` INTEGER",
                    "ALTER TABLE `notes` ADD COLUMN `trashedUtc` INTEGER",
                    "ALTER TABLE `notes` ADD COLUMN `assetsJson` TEXT NOT NULL DEFAULT '[]'",
                    "ALTER TABLE `notes` ADD COLUMN `linksJson` TEXT NOT NULL DEFAULT '[]'",
                    "ALTER TABLE `notes` ADD COLUMN `remoteJson` TEXT",
                    "CREATE INDEX IF NOT EXISTS `index_notes_folderId` ON `notes` (`folderId`)",
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `notes_fts` USING FTS4(`title` TEXT NOT NULL, `body` TEXT NOT NULL, content=`notes`)",
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_notes_fts_BEFORE_UPDATE BEFORE UPDATE ON `notes` BEGIN DELETE FROM `notes_fts` WHERE `docid`=OLD.`rowid`; END",
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_notes_fts_BEFORE_DELETE BEFORE DELETE ON `notes` BEGIN DELETE FROM `notes_fts` WHERE `docid`=OLD.`rowid`; END",
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_notes_fts_AFTER_UPDATE AFTER UPDATE ON `notes` BEGIN INSERT INTO `notes_fts`(`docid`, `title`, `body`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`body`); END",
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_notes_fts_AFTER_INSERT AFTER INSERT ON `notes` BEGIN INSERT INTO `notes_fts`(`docid`, `title`, `body`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`body`); END",
                    "INSERT INTO `notes_fts`(`notes_fts`) VALUES ('rebuild')",
                    "CREATE TABLE IF NOT EXISTS `note_folders` (`id` TEXT NOT NULL, `parentId` TEXT, `name` TEXT NOT NULL, `position` REAL NOT NULL, `createdUtc` INTEGER NOT NULL, `modifiedUtc` INTEGER NOT NULL, `deletedUtc` INTEGER, `serverVersion` INTEGER, `remoteJson` TEXT, PRIMARY KEY(`id`))",
                    "CREATE INDEX IF NOT EXISTS `index_note_folders_deletedUtc` ON `note_folders` (`deletedUtc`)",
                    "CREATE TABLE IF NOT EXISTS `note_assets` (`sha256` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `bytes` INTEGER NOT NULL, `width` INTEGER, `height` INTEGER, `createdUtc` INTEGER NOT NULL, `uploaded` INTEGER NOT NULL, `present` INTEGER NOT NULL, PRIMARY KEY(`sha256`))",
                ).forEach(db::execSQL)
            }
        }

        /** 3 -> 4: a note's sync conflict remembers its conflict copy, so both versions can be compared and resolved. */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sync_issues` ADD COLUMN `copyId` TEXT")
            }
        }

        val MIGRATIONS = arrayOf<androidx.room.migration.Migration>(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        fun build(context: Context, name: String? = NAME): AppDatabase {
            val builder = if (name == null) {
                Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            } else {
                Room.databaseBuilder(context, AppDatabase::class.java, name)
            }
            return builder.addMigrations(*MIGRATIONS).build()
        }
    }
}
