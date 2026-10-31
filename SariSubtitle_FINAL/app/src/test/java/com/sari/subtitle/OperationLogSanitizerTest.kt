package com.sari.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OperationLogSanitizerTest {
    @Test
    fun redactsApiKeyAssignmentsWithoutThrowing() {
        val result = OperationLogStore.sanitizeLogValue("provider failed api_key=sk-secret-123")
        assertFalse(result.contains("sk-secret-123"))
        assertEquals("provider failed api_key=[REDACTED]", result)
    }

    @Test
    fun redactsAuthorizationAndBearerValues() {
        val result = OperationLogStore.sanitizeLogValue("Authorization: Bearer abc.def token")
        assertFalse(result.contains("abc.def"))
        assertFalse(result.contains("Bearer abc.def"))
        assertEquals("Authorization=[REDACTED]", result)
    }

    @Test
    fun normalizesControlCharactersAndCapsLength() {
        assertEquals("one two three", OperationLogStore.sanitizeLogValue("one\n two\tthree"))
        assertEquals(500, OperationLogStore.sanitizeLogValue("x".repeat(700)).length)
    }
}
