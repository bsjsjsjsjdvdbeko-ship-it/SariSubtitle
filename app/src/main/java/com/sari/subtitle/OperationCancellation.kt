package com.sari.subtitle

import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Cancellation state for one foreground media/translation operation. */
class OperationCancellation {
    private val cancelled = AtomicBoolean(false)
    @Volatile var worker: Thread? = null

    fun isCancelled(): Boolean = cancelled.get() || Thread.currentThread().isInterrupted

    fun check() {
        if (isCancelled()) throw CancellationException("تم إلغاء العملية")
    }

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            // Only close HTTP connections opened by this operation's worker. A global
            // disconnect can break an unrelated translation or caption request.
            worker?.let(NetworkRequestRegistry::cancelForThread)
            worker?.interrupt()
        }
    }
}

/**
 * Tracks the thread that opened each connection so cancellation remains operation-scoped.
 * Network service calls in this project are synchronous on their caller's worker thread.
 */
object NetworkRequestRegistry {
    private val owners = ConcurrentHashMap<HttpURLConnection, Thread>()

    fun register(connection: HttpURLConnection): HttpURLConnection {
        owners[connection] = Thread.currentThread()
        return connection
    }

    fun unregister(connection: HttpURLConnection) {
        owners.remove(connection)
    }

    internal fun cancelForThread(thread: Thread) {
        val snapshot = owners.entries.filter { it.value === thread }
        snapshot.forEach { entry ->
            if (owners.remove(entry.key, entry.value)) {
                try { entry.key.disconnect() } catch (_: Exception) {}
            }
        }
    }
}
