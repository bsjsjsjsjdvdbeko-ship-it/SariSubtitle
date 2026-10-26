package com.sari.subtitle

import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream
import org.json.JSONObject

/** Offline Vosk adapter. A compatible unpacked model directory must be supplied by the app/user. */
class VoskClient(private val modelDirectory: File, private val sampleRate: Float = 16_000f) : SttClient {
    override suspend fun transcribe(audioFile: File, language: String, prompt: String): TranscriptResult {
        require(modelDirectory.exists() && modelDirectory.isDirectory) { "نموذج Vosk غير موجود: ${modelDirectory.absolutePath}" }
        require(audioFile.exists() && audioFile.length() > 44) { "ملف WAV غير صالح." }
        val model = Model(modelDirectory.absolutePath)
        model.use { m ->
            Recognizer(m, sampleRate).use { recognizer ->
                FileInputStream(audioFile).use { input ->
                    input.skip(44)
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        recognizer.acceptWaveForm(buffer, n)
                    }
                }
                val json = JSONObject(recognizer.finalResult)
                val text = json.optString("text").trim()
                if (text.isBlank()) error("Vosk لم يتعرف على كلام في المقطع.")
                return TranscriptResult(text, emptyList())
            }
        }
    }
}
