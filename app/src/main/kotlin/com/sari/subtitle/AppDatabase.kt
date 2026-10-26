package com.sari.subtitle

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TranslationEntity::class, TranslationMemoryEntity::class, ProviderMetricEntity::class, ProviderCircuitEntity::class, ProviderRuntimeEntity::class],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun translationDao(): TranslationDao
    abstract fun translationMemoryDao(): TranslationMemoryDao
    abstract fun providerMetricsDao(): ProviderMetricsDao
    abstract fun providerRuntimeDao(): ProviderRuntimeDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS translation_memory (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceHash TEXT NOT NULL, sourceText TEXT NOT NULL, translatedText TEXT NOT NULL, sourceLanguage TEXT NOT NULL, targetLanguage TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_translation_memory_sourceHash_sourceLanguage_targetLanguage ON translation_memory(sourceHash, sourceLanguage, targetLanguage)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS provider_metrics (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, modelId TEXT NOT NULL, taskType TEXT NOT NULL, latencyMs INTEGER NOT NULL, success INTEGER NOT NULL, error TEXT, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_provider_metrics_modelId_taskType ON provider_metrics(modelId, taskType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_provider_metrics_createdAt ON provider_metrics(createdAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS provider_circuit (circuitKey TEXT PRIMARY KEY NOT NULL, openUntil INTEGER NOT NULL)")
            }
        }


        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_metrics ADD COLUMN qualityScore REAL NOT NULL DEFAULT 0.0")
                db.execSQL("CREATE TABLE IF NOT EXISTS provider_runtime (runtimeKey TEXT PRIMARY KEY NOT NULL, modelId TEXT NOT NULL, taskType TEXT NOT NULL, rateLimitRemaining INTEGER, rateLimitResetMs INTEGER, quotaRemaining INTEGER, quotaLimit INTEGER, lastStatusCode INTEGER, lastError TEXT, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_provider_runtime_modelId_taskType ON provider_runtime(modelId, taskType)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_metrics ADD COLUMN environmentKey TEXT NOT NULL DEFAULT 'generic'")
                db.execSQL("ALTER TABLE provider_metrics ADD COLUMN noiseLevel REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE provider_metrics ADD COLUMN speechSpeed REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE provider_metrics ADD COLUMN agreementScore REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE provider_metrics ADD COLUMN firstTokenLatencyMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE provider_metrics ADD COLUMN maxInterTokenGapMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE provider_runtime ADD COLUMN firstTokenLatencyMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE provider_runtime ADD COLUMN maxInterTokenGapMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_provider_metrics_modelId_taskType_environmentKey ON provider_metrics(modelId, taskType, environmentKey)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_runtime ADD COLUMN providerId TEXT")
                db.execSQL("ALTER TABLE provider_runtime ADD COLUMN effectiveModel TEXT")
                db.execSQL("ALTER TABLE provider_runtime ADD COLUMN responseCostUsd REAL")
                db.execSQL("ALTER TABLE provider_runtime ADD COLUMN fallbackAttempts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE provider_runtime ADD COLUMN requestId TEXT")
                db.execSQL("ALTER TABLE provider_runtime ADD COLUMN streamCompleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_provider_runtime_providerId_taskType ON provider_runtime(providerId, taskType)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_runtime ADD COLUMN decisionTrace TEXT")
            }
        }

        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "sari_subtitles.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build().also { instance = it }
        }
    }
}
