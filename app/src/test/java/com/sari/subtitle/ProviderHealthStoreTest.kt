package com.sari.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderHealthStoreTest {
    @Test fun classifyAuth() {
        assertEquals(ProviderHealthStore.State.AUTH_ERROR, ProviderHealthStore.classify(401))
        assertEquals(ProviderHealthStore.State.AUTH_ERROR, ProviderHealthStore.classify(403))
    }

    @Test fun classifyRateLimit() {
        assertEquals(ProviderHealthStore.State.RATE_LIMITED, ProviderHealthStore.classify(429))
    }

    @Test fun classifyServerError() {
        assertEquals(ProviderHealthStore.State.SERVER_ERROR, ProviderHealthStore.classify(503))
    }

    @Test fun classifySuccess() {
        assertEquals(ProviderHealthStore.State.READY, ProviderHealthStore.classify(200))
    }
}
