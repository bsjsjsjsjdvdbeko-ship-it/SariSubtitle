package com.sari.subtitle

/**
 * Stage 47: bounded text-only context for live translation.
 *
 * Stores only the last few finalized source/translation pairs for the active
 * live session. No audio, API keys, or unbounded transcript history is kept.
 */
class LiveTranslationContextBuffer(
    private val maxItems: Int = 3
) {
    data class Item(
        val sequence: Long,
        val sourceLanguage: String,
        val sourceText: String,
        val translatedText: String
    )

    private var sessionId = Long.MIN_VALUE
    private val items = ArrayDeque<Item>()

    init { require(maxItems in 1..8) { "maxItems must be between 1 and 8" } }

    @Synchronized
    fun beginSession(newSessionId: Long) {
        sessionId = newSessionId
        items.clear()
    }

    @Synchronized
    fun clear() {
        sessionId = Long.MIN_VALUE
        items.clear()
    }

    @Synchronized
    fun snapshot(activeSessionId: Long): List<Item> {
        if (activeSessionId != sessionId) return emptyList()
        return items.toList()
    }

    @Synchronized
    fun add(activeSessionId: Long, item: Item) {
        if (activeSessionId != sessionId) return
        if (items.any { it.sequence == item.sequence }) {
            items.removeAll { it.sequence == item.sequence }
        }
        items.addLast(item)
        while (items.size > maxItems) items.removeFirst()
    }
}
