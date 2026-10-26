package com.sari.subtitle

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Insert
import androidx.room.OnConflictStrategy

/** Runtime facts observed from real OmniRoute/upstream responses. Unknown values remain null. */
@Entity(tableName = "provider_runtime")
data class ProviderRuntimeEntity(
    @PrimaryKey val runtimeKey: String,
    val modelId: String,
    val taskType: String,
    val rateLimitRemaining: Long?,
    val rateLimitResetMs: Long?,
    val quotaRemaining: Long?,
    val quotaLimit: Long?,
    val lastStatusCode: Int?,
    val lastError: String?,
    val providerId: String? = null,
    val effectiveModel: String? = null,
    val responseCostUsd: Double? = null,
    val fallbackAttempts: Int = 0,
    val requestId: String? = null,
    val decisionTrace: String? = null,
    val streamCompleted: Boolean = false,
    val firstTokenLatencyMs: Long = 0L,
    val maxInterTokenGapMs: Long = 0L,
    val updatedAt: Long
)

@Dao
interface ProviderRuntimeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ProviderRuntimeEntity)

    @Query("SELECT * FROM provider_runtime ORDER BY updatedAt DESC")
    suspend fun all(): List<ProviderRuntimeEntity>

    @Query("SELECT * FROM provider_runtime WHERE runtimeKey = :key LIMIT 1")
    suspend fun get(key: String): ProviderRuntimeEntity?
}
