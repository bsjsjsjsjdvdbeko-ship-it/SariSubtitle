package com.sari.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRoutingEngineTest {
    @Test fun authErrorIsNotRetriedAutomatically() {
        val state = ProviderHealthStore.stateFromError("HTTP 401: unauthorized")
        assertEquals(ProviderHealthStore.State.AUTH_ERROR, state)
    }

    @Test fun rateLimitIsClassified() {
        assertEquals(ProviderHealthStore.State.RATE_LIMITED,
            ProviderHealthStore.stateFromError("HTTP 429: too many requests"))
    }

    @Test fun serverErrorIsClassifiedWithoutStatusField() {
        assertEquals(ProviderHealthStore.State.SERVER_ERROR,
            ProviderHealthStore.stateFromError("HTTP 503: server error"))
    }

    @Test fun networkFailureIsClassified() {
        assertEquals(ProviderHealthStore.State.NETWORK_ERROR,
            ProviderHealthStore.stateFromError("timeout while connecting to provider"))
    }

    @Test fun healthModelDefaultsCircuitClosed() {
        val health = ProviderHealthStore.Health("Groq", ProviderHealthStore.State.READY, 120, 1, 200, "ok")
        assertEquals(0, health.failureCount)
        assertEquals(0L, health.circuitOpenUntil)
    }
}
