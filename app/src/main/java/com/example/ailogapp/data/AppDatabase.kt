package com.example.ailogapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.ailogapp.data.daos.AnalysisDao
import com.example.ailogapp.data.daos.AudioRecordDao
import com.example.ailogapp.data.daos.ChatMessageDao
import com.example.ailogapp.data.daos.ChatSessionDao
import com.example.ailogapp.data.daos.NoteDao
import com.example.ailogapp.data.daos.TranscriptDao
import com.example.ailogapp.data.entities.AnalysisEntity
import com.example.ailogapp.data.entities.AudioRecordEntity
import com.example.ailogapp.data.entities.ChatMessageEntity
import com.example.ailogapp.data.entities.ChatSessionEntity
import com.example.ailogapp.data.entities.NoteEntity
import com.example.ailogapp.data.entities.TranscriptEntity

@Database(
    entities = [
        AudioRecordEntity::class,
        TranscriptEntity::class,
        AnalysisEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        NoteEntity::class
    ],
    // 版本 6 -> 7：新增 notes 笔记表
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun audioRecordDao(): AudioRecordDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun analysisDao(): AnalysisDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * 3 -> 4：为 audio_records 表新增 dateStr / transcribeStatus / startTime 索引。
         * 索引名需与 Room 根据 @Index 默认生成的命名一致：index_<table>_<column>，
         * 否则 schema 校验会认为迁移后的结构与 @Entity 声明不符。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audio_records_dateStr` ON `audio_records`(`dateStr`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audio_records_transcribeStatus` ON `audio_records`(`transcribeStatus`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audio_records_startTime` ON `audio_records`(`startTime`)"
                )
            }
        }

        /**
         * 4 -> 5：为 audio_records 表新增 isDeleted 软删除字段，
         * 用于标记录音文件已删除但保留转写和数据库记录的情况。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `audio_records` ADD COLUMN `isDeleted` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * 5 -> 6：为 transcripts 表新增 speakerId 说话人ID字段，
         * 用于区分不同说话人的转写内容。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `transcripts` ADD COLUMN `speakerId` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * 6 -> 7：新增 notes 笔记表，用于在详情页编写并独立展示在 AI 分析界面。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `notes` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `dateStr` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `sourceType` TEXT NOT NULL,
                        `sourceId` INTEGER NOT NULL,
                        `sourceLabel` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_notes_dateStr` ON `notes`(`dateStr`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_notes_sourceId` ON `notes`(`sourceId`)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ailog.db"
                )
                    // 不再使用 fallbackToDestructiveMigration，避免升级时静默清库丢数据；
                    // 后续结构变更需通过显式 Migration 处理。
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
