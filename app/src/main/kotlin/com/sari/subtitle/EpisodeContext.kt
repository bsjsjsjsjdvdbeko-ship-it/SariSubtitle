package com.sari.subtitle

import android.content.Context
import java.util.LinkedHashMap

/**
 * Small rolling translation memory for the current viewing session.
 * It stores text only, never raw audio.
 */
class EpisodeContextStore(context: Context) {
    private val prefs = context.getSharedPreferences("sari_stage9_context", Context.MODE_PRIVATE)
    private val turns = ArrayDeque<ContextTurn>()
    private val glossary = LinkedHashMap<String, String>()
    private val characterNotes = LinkedHashMap<String, String>()

    init { loadGlossary() }

    @Synchronized
    fun addTurn(source: String, translation: String) {
        val cleanSource = source.trim()
        val cleanTranslation = translation.trim()
        if (cleanSource.isBlank()) return
        turns.addLast(ContextTurn(cleanSource, cleanTranslation))
        while (turns.size > MAX_CONTEXT_TURNS) turns.removeFirst()
        extractSpeakerLabel(cleanSource)
    }

    /** Backward-compatible helper. */
    @Synchronized
    fun addTranscript(text: String) = addTurn(text, "")

    @Synchronized
    fun snapshot(): TranslationContext = TranslationContext(
        previousSegments = turns.map { it.source },
        previousTranslations = turns.map { it },
        glossary = glossary.toMap(),
        characterNotes = characterNotes.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    )

    @Synchronized
    fun setGlossaryFromText(value: String) {
        glossary.clear()
        value.lineSequence().forEach { line ->
            val separator = line.indexOf('=')
            if (separator > 0) {
                val source = line.substring(0, separator).trim()
                val target = line.substring(separator + 1).trim()
                if (source.isNotBlank() && target.isNotBlank()) glossary[source] = target
            }
        }
        prefs.edit().putString(KEY_GLOSSARY, value).apply()
    }

    @Synchronized
    fun clearSession() {
        turns.clear()
        characterNotes.clear()
    }

    fun glossaryText(): String = prefs.getString(KEY_GLOSSARY, "").orEmpty()

    private fun extractSpeakerLabel(text: String) {
        val match = Regex("^\\s*([\\p{L}][\\p{L}0-9 _-]{0,40})\\s*:\\s*").find(text) ?: return
        val name = match.groupValues[1].trim()
        if (name.length in 2..40) characterNotes[name] = "speaker label seen in transcript; keep this name consistent"
    }

    private fun loadGlossary() {
        val value = prefs.getString(KEY_GLOSSARY, "").orEmpty()
        value.lineSequence().forEach { line ->
            val separator = line.indexOf('=')
            if (separator > 0) {
                val source = line.substring(0, separator).trim()
                val target = line.substring(separator + 1).trim()
                if (source.isNotBlank() && target.isNotBlank()) glossary[source] = target
            }
        }
    }

    companion object {
        private const val KEY_GLOSSARY = "glossary"
        private const val MAX_CONTEXT_TURNS = 8
    }
}

data class ContextTurn(val source: String, val translation: String)
