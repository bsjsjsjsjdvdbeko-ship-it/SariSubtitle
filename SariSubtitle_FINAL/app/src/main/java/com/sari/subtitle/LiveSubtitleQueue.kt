package com.sari.subtitle

/**
 * Stage 49: small in-memory timeline queue for live Arabic subtitle rendering.
 * It never stores audio or API credentials. Items are session-scoped and ordered
 * by media/capture time rather than network arrival order.
 */
class LiveSubtitleQueue(
    private val maxItems: Int = 12,
    private val lateToleranceMs: Long = 1_500L
) {
    data class Item(
        val sessionId: Long,
        val sequence: Long,
        val revision: Int,
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val displayUntilMs: Long
    )

    sealed class Action {
        data class Show(val item: Item) : Action()
        data object Hide : Action()
        data object Keep : Action()
    }

    private val items = ArrayList<Item>(maxItems)
    private var sessionId = Long.MIN_VALUE
    private var shownKey: String? = null

    @Synchronized
    fun beginSession(newSessionId: Long) {
        sessionId = newSessionId
        items.clear()
        shownKey = null
    }

    @Synchronized
    fun clear() {
        sessionId = Long.MIN_VALUE
        items.clear()
        shownKey = null
    }

    @Synchronized
    fun offer(item: Item): Boolean {
        if (item.sessionId != sessionId || item.text.isBlank()) return false

        val sameSequence = items.filter { it.sequence == item.sequence }
        if (sameSequence.any { it.revision > item.revision }) return false
        items.removeAll { it.sequence == item.sequence && it.revision <= item.revision }

        items.add(item)
        items.sortWith(compareBy<Item> { it.startMs }.thenBy { it.sequence }.thenBy { it.revision })
        if (items.size > maxItems) {
            items.subList(maxItems, items.size).clear()
        }
        return true
    }

    @Synchronized
    fun advance(nowMs: Long): Action {
        if (sessionId == Long.MIN_VALUE) return Action.Hide

        // Drop expired items, but retain a slightly late cue so network jitter
        // can still show the newest subtitle instead of producing a blank frame.
        items.removeAll { it.displayUntilMs + lateToleranceMs < nowMs }

        val candidate = items
            .filter { it.startMs <= nowMs && it.displayUntilMs > nowMs }
            .maxWithOrNull(compareBy<Item> { it.startMs }.thenBy { it.sequence }.thenBy { it.revision })

        if (candidate == null) {
            if (items.none { it.startMs > nowMs }) {
                shownKey = null
                return Action.Hide
            }
            return Action.Keep
        }

        val key = "${candidate.sessionId}:${candidate.sequence}:${candidate.revision}"
        if (shownKey == key) return Action.Keep
        shownKey = key
        return Action.Show(candidate)
    }

    @Synchronized
    fun size(): Int = items.size
}
