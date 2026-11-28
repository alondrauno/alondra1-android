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
        NoteFts::class, NoteFolderEntity::class, NoteAssetEntity::class, ListGroupEntity::class, CompletionRecordEntity::class,
        TaskFileEntity::class, TagEntity::class, TagLinkEntity::class],
    version = 7,
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
    abstract fun listGroups(): ListGroupDao
    abstract fun completions(): CompletionDao
    abstract fun taskFiles(): TaskFileDao
    abstract fun tags(): TagDao

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

        /**
         * 4 -> 5: task parity with the shared contract (completion spec v1.1 §8.3): start date, repeat rule, steps and
         * several reminders on tasks, list groups, and completion history. The single reminder of schema 4 becomes the
         * first entry of the reminder list, with the id the sync mapper already used for it, so a synced reminder keeps
         * its identity. Nothing is lost.
         */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                listOf(
                    "ALTER TABLE `tasks` ADD COLUMN `startDate` TEXT",
                    "ALTER TABLE `tasks` ADD COLUMN `recurrenceJson` TEXT",
                    "ALTER TABLE `tasks` ADD COLUMN `stepsJson` TEXT NOT NULL DEFAULT '[]'",
                    "ALTER TABLE `tasks` ADD COLUMN `remindersJson` TEXT NOT NULL DEFAULT '[]'",
                    "UPDATE `tasks` SET `remindersJson` = '[{\"id\":\"' || substr(`id` || '-r', 1, 64) || '\",\"at\":\"' || " +
                        "CASE WHEN length(`reminderAt`) = 16 THEN `reminderAt` || ':00' ELSE substr(`reminderAt`, 1, 19) END || '\"}]', " +
                        "`reminderAt` = NULL WHERE `reminderAt` IS NOT NULL",
                    "ALTER TABLE `task_lists` ADD COLUMN `groupId` TEXT",
                    "CREATE TABLE IF NOT EXISTS `list_groups` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `position` REAL NOT NULL, `collapsed` INTEGER NOT NULL, `createdUtc` INTEGER NOT NULL, `modifiedUtc` INTEGER NOT NULL, `deletedUtc` INTEGER, `serverVersion` INTEGER, `remoteJson` TEXT, PRIMARY KEY(`id`))",
                    "CREATE INDEX IF NOT EXISTS `index_list_groups_deletedUtc` ON `list_groups` (`deletedUtc`)",
                    "CREATE TABLE IF NOT EXISTS `completion_records` (`id` TEXT NOT NULL, `taskId` TEXT NOT NULL, `title` TEXT NOT NULL, `listId` TEXT NOT NULL, `completedUtc` INTEGER NOT NULL, `occurrenceDate` TEXT, `sent` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                    "CREATE INDEX IF NOT EXISTS `index_completion_records_taskId` ON `completion_records` (`taskId`)",
                    "CREATE INDEX IF NOT EXISTS `index_completion_records_sent` ON `completion_records` (`sent`)",
                ).forEach(db::execSQL)
            }
        }

        /**
         * 5 -> 6: task attachments (Android UI and task attachments spec §8). Attachments become a task column; the
         * ones other clients already synced (kept until now inside remoteJson) are copied into it, so none is lost or
         * dropped by the next upload. Files attached here are indexed in task_files.
         */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `attachmentsJson` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("CREATE TABLE IF NOT EXISTS `task_files` (`sha256` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `bytes` INTEGER NOT NULL, `createdUtc` INTEGER NOT NULL, `uploaded` INTEGER NOT NULL, `present` INTEGER NOT NULL, `lastError` TEXT, PRIMARY KEY(`sha256`))")
                val copies = mutableListOf<Pair<String, String>>()
                db.query("SELECT `id`, `remoteJson` FROM `tasks` WHERE `remoteJson` LIKE '%\"attachments\"%'").use { c ->
                    while (c.moveToNext()) {
                        val attachments = runCatching { org.json.JSONObject(c.getString(1)).optJSONArray("attachments") }.getOrNull()
                        if (attachments != null && attachments.length() > 0) copies += c.getString(0) to attachments.toString()
                    }
                }
                copies.forEach { (id, json) -> db.execSQL("UPDATE `tasks` SET `attachmentsJson` = ? WHERE `id` = ?", arrayOf<Any>(json, id)) }
            }
        }

        /** 6 -> 7: tags and tag links (FEATURES_EXTENSION Part B §2). New tables only; existing data is untouched. */
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                listOf(
                    "CREATE TABLE IF NOT EXISTS `tags` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `nameKey` TEXT NOT NULL, `color` TEXT, `createdUtc` INTEGER NOT NULL, `modifiedUtc` INTEGER NOT NULL, `deletedUtc` INTEGER, `serverVersion` INTEGER, `remoteJson` TEXT, PRIMARY KEY(`id`))",
                    "CREATE INDEX IF NOT EXISTS `index_tags_deletedUtc` ON `tags` (`deletedUtc`)",
                    "CREATE INDEX IF NOT EXISTS `index_tags_nameKey` ON `tags` (`nameKey`)",
                    "CREATE TABLE IF NOT EXISTS `tag_links` (`id` TEXT NOT NULL, `tagId` TEXT NOT NULL, `objectType` TEXT NOT NULL, `objectRef` TEXT NOT NULL, `removed` INTEGER NOT NULL, `createdUtc` INTEGER NOT NULL, `modifiedUtc` INTEGER NOT NULL, `deletedUtc` INTEGER, `serverVersion` INTEGER, `remoteJson` TEXT, PRIMARY KEY(`id`))",
                    "CREATE INDEX IF NOT EXISTS `index_tag_links_tagId` ON `tag_links` (`tagId`)",
                    "CREATE INDEX IF NOT EXISTS `index_tag_links_objectType_objectRef` ON `tag_links` (`objectType`, `objectRef`)",
                    "CREATE INDEX IF NOT EXISTS `index_tag_links_deletedUtc` ON `tag_links` (`deletedUtc`)",
                ).forEach(db::execSQL)
            }
        }

        val MIGRATIONS = arrayOf<androidx.room.migration.Migration>(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)

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
