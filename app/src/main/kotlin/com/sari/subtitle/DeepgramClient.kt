package com.sari.subtitle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Deepgram Nova-2 file-based STT adapter. It is not advertised as realtime streaming. */
class DeepgramClient(private val apiKey: String, private val http: OkHttpClient = defaultClient()) : SttClient {
    override suspend fun transcribe(audioFile: File, language: String, prompt: String): TranscriptResult = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "أدخل مفتاح Deepgram API في الإعدادات." }
        require(audioFile.exists() && audioFile.length() > 0) { "الملف الصوتي فارغ." }
        val params = buildString {
            append("model=nova-2&smart_format=true")
            if (language.isNotBlank() && language != "auto") append("&language=").append(language)
        }
        val request = Request.Builder()
            .url("https://api.deepgram.com/v1/listen?$params")
            .header("Authorization", "Token $apiKey")
            .header("Content-Type", "audio/wav")
            .post(audioFile.asRequestBody("audio/wav".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Deepgram HTTP ${response.code}: ${raw.take(180)}")
            val root = JSONObject(raw)
            val alt = root.optJSONObject("results")?.optJSONArray("channels")?.optJSONObject(0)?.optJSONArray("alternatives")?.optJSONObject(0)
            val text = alt?.optString("transcript").orEmpty().trim()
            if (text.isBlank()) error("Deepgram أعاد نصاً فارغاً.")
            TranscriptResult(text, emptyList())
        }
    }
    companion object { private fun defaultClient() = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build() }
}
