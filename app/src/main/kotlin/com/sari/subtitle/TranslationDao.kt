package com.sari.subtitle

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranslationDao {
    @Insert suspend fun insert(item: TranslationEntity): Long
    @Query("SELECT * FROM translations ORDER BY createdAt DESC LIMIT :limit")
    suspend fun latest(limit: Int = 50): List<TranslationEntity>
    @Query("SELECT * FROM translations ORDER BY createdAt DESC LIMIT 50")
    fun observeLatest(): Flow<List<TranslationEntity>>
    @Query("DELETE FROM translations WHERE id = :id")
    suspend fun deleteById(id: Long)
    @Query("DELETE FROM translations")
    suspend fun deleteAll()
    @Query("DELETE FROM translations WHERE id NOT IN (SELECT id FROM translations ORDER BY createdAt DESC LIMIT 50)")
    suspend fun trimTo50()
}
