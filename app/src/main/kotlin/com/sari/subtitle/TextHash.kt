package com.sari.subtitle

import java.security.MessageDigest

object TextHash {
    fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.trim().toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
