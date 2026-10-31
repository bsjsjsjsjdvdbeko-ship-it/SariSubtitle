package com.sari.subtitle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NetworkRequestRegistryTest {
    private class FakeConnection(url: URL) : HttpURLConnection(url) {
        val wasDisconnected = AtomicBoolean(false)
        override fun connect() = Unit
        override fun disconnect() { wasDisconnected.set(true) }
        override fun usingProxy(): Boolean = false
    }

    @Test
    fun cancellingOneWorkerDoesNotDisconnectAnotherWorkersConnection() {
        val first = FakeConnection(URL("https://example.com/first"))
        val second = FakeConnection(URL("https://example.com/second"))
        val registered = CountDownLatch(1)
        val worker = Thread {
            NetworkRequestRegistry.register(first)
            registered.countDown()
        }
        try {
            worker.start()
            assertTrue("worker did not register its connection", registered.await(2, TimeUnit.SECONDS))
            worker.join(2_000)
            NetworkRequestRegistry.register(second) // owned by this test thread

            NetworkRequestRegistry.cancelForThread(worker)

            assertTrue(first.wasDisconnected.get())
            assertFalse(second.wasDisconnected.get())
        } finally {
            NetworkRequestRegistry.unregister(first)
            NetworkRequestRegistry.unregister(second)
            first.disconnect()
            second.disconnect()
        }
    }
}
