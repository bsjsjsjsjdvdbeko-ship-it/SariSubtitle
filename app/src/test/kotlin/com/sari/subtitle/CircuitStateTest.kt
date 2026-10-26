package com.sari.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals

class CircuitStateTest {
    @Test fun stateNamesAreExplicit() {
        assertEquals(listOf("CLOSED", "OPEN", "HALF_OPEN"), CircuitState.entries.map { it.name })
    }
}
