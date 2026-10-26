package com.sari.subtitle

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TranslationMemoryDao {
    @Query("SELECT * FROM translation_memory WHERE sourceHash = :sourceHash AND sourceLanguage = :sourceLanguage AND targetLanguage = :targetLanguage LIMIT 1")
    suspend fun find(sourceHash: String, sourceLanguage: String, targetLanguage: String): TranslationMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: TranslationMemoryEntity): Long

    @Query("DELETE FROM translation_memory WHERE id NOT IN (SELECT id FROM translation_memory ORDER BY createdAt DESC LIMIT :limit)")
    suspend fun trim(limit: Int = 1000)

    @Query("DELETE FROM translation_memory")
    suspend fun clear()
}
