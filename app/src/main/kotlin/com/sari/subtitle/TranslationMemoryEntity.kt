package com.sari.subtitle

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "translation_memory",
    indices = [Index(value = ["sourceHash", "sourceLanguage", "targetLanguage"], unique = true)]
)
data class TranslationMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceHash: String,
    val sourceText: String,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val createdAt: Long
)
