package com.sari.subtitle.v2

import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Shared cancellation primitives for one foreground media/translation operation. */
class OperationCancellation {
    private val cancelled = AtomicBoolean(false)
    @Volatile var worker: Thread? = null
    fun isCancelled(): Boolean = cancelled.get() || Thread.currentThread().isInterrupted
    fun check() { if (isCancelled()) throw CancellationException("تم إلغاء العملية") }
    fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            NetworkRequestRegistry.cancelAll()
            worker?.interrupt()
        }
    }
}

/** Lets a UI cancellation close currently blocking HTTP connections instead of only hiding progress. */
object NetworkRequestRegistry {
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    fun beginOperation() { cancelAll() }
    fun register(connection: HttpURLConnection): HttpURLConnection { connections.add(connection); return connection }
    fun unregister(connection: HttpURLConnection) { connections.remove(connection) }
    fun cancelAll() {
        val snapshot = connections.toList()
        connections.clear()
        snapshot.forEach { try { it.disconnect() } catch (_: Exception) {} }
    }
}
