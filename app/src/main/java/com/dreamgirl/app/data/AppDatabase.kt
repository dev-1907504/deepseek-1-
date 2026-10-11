package com.dreamgirl.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MessageEntity::class,
        DaySessionEntity::class,
        DaySummaryEntity::class,
        MemoryItemEntity::class,
        PersonaEntity::class,
        MonthDigestEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun daySessionDao(): DaySessionDao
    abstract fun daySummaryDao(): DaySummaryDao
    abstract fun memoryDao(): MemoryDao
    abstract fun personaDao(): PersonaDao
    abstract fun monthDigestDao(): MonthDigestDao

    companion object {
        /**
         * v1 → v2：日总结多了一个 skippedCount。
         * 用真迁移而不是销毁重建 —— 用户已经聊过的内容不该因为升级而消失。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE day_summaries ADD COLUMN skippedCount INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * v2 → v3：消息多了 kind 和 mediaPath（支持图片/视频/文件/表情）。
         * 同样用真迁移，聊天记录一条不丢。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'text'")
                db.execSQL("ALTER TABLE messages ADD COLUMN mediaPath TEXT")
            }
        }

        /** v3 → v4：日总结多一个 herMood（她自己的心情）。 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE day_summaries ADD COLUMN herMood TEXT")
            }
        }

        /**
         * v4 → v5：消息多了 quoteId / quoteText（长按可以引用某一句话）。
         * 老消息这两列是 NULL，等于"没引用"，行为不变。聊天记录依旧一条不丢。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN quoteId INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN quoteText TEXT")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "dreamgirl.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
