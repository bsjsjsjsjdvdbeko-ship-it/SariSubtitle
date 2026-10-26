package com.sari.subtitle

import java.io.File

interface SttClient {
    suspend fun transcribe(audioFile: File, language: String = "auto", prompt: String = ""): TranscriptResult
}
