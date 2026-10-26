package com.sari.subtitle

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Entity(
    tableName = "provider_metrics",
    indices = [
        Index(value = ["modelId", "taskType"]),
        Index(value = ["modelId", "taskType", "environmentKey"]),
        Index(value = ["createdAt"])
    ]
)
data class ProviderMetricEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modelId: String,
    val taskType: String,
    val latencyMs: Long,
    val success: Boolean,
    val error: String?,
    @androidx.room.ColumnInfo(defaultValue = "0.0") val qualityScore: Double = 0.0,
    @androidx.room.ColumnInfo(defaultValue = "generic") val environmentKey: String = "generic",
    @androidx.room.ColumnInfo(defaultValue = "0.0") val noiseLevel: Double = 0.0,
    @androidx.room.ColumnInfo(defaultValue = "0.0") val speechSpeed: Double = 0.0,
    @androidx.room.ColumnInfo(defaultValue = "1.0") val agreementScore: Double = 1.0,
    @androidx.room.ColumnInfo(defaultValue = "0") val firstTokenLatencyMs: Long = 0L,
    @androidx.room.ColumnInfo(defaultValue = "0") val maxInterTokenGapMs: Long = 0L,
    val createdAt: Long
)

@Entity(tableName = "provider_circuit")
data class ProviderCircuitEntity(
    @androidx.room.PrimaryKey val circuitKey: String,
    val openUntil: Long
)

@Dao
interface ProviderMetricsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: ProviderMetricEntity)

    @Query("SELECT * FROM provider_metrics WHERE modelId = :modelId AND taskType = :taskType ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(modelId: String, taskType: String, limit: Int): List<ProviderMetricEntity>

    @Query("SELECT * FROM provider_metrics WHERE modelId = :modelId AND taskType = :taskType AND environmentKey = :environmentKey ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentByEnvironment(modelId: String, taskType: String, environmentKey: String, limit: Int): List<ProviderMetricEntity>

    suspend fun recentFor(key: String, limit: Int): List<ProviderMetricEntity> = recent(key.substringAfter('|'), key.substringBefore('|'), limit)

    @Query("SELECT * FROM provider_metrics ORDER BY createdAt DESC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<ProviderMetricEntity>

    @Query("SELECT COUNT(*) FROM provider_metrics WHERE modelId = :modelId AND taskType = :taskType AND success = 0 AND createdAt >= :since")
    suspend fun failuresSince(modelId: String, taskType: String, since: Long): Int

    suspend fun recentFailures(key: String, count: Int): Int = recentFor(key, count).take(count).count { !it.success }

    @Query("SELECT openUntil FROM provider_circuit WHERE circuitKey = :key LIMIT 1")
    suspend fun circuitUntil(key: String): Long?

    @Query("INSERT OR REPLACE INTO provider_circuit(circuitKey, openUntil) VALUES(:key, :until)")
    suspend fun setCircuit(key: String, until: Long)

    suspend fun isCircuitOpen(key: String): Boolean = (circuitUntil(key) ?: 0L) > System.currentTimeMillis()
    suspend fun openCircuit(key: String, until: Long) = setCircuit(key, until)
    suspend fun closeCircuit(key: String) = setCircuit(key, 0L)

    @Query("DELETE FROM provider_metrics WHERE id NOT IN (SELECT id FROM provider_metrics ORDER BY createdAt DESC LIMIT :limit)")
    suspend fun trim(limit: Int)
}
