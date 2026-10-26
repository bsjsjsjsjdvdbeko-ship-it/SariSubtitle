package com.sari.subtitle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Groq Speech-to-Text client. Returns segment timestamps when the API provides them. */
class WhisperClient(private val apiKey: String, private val http: OkHttpClient = defaultClient()) : SttClient {
    override suspend fun transcribe(audioFile: File, language: String = "ar", prompt: String = ""): String =
        transcribeDetailed(audioFile, language, prompt).text

    suspend fun transcribeDetailed(audioFile: File, language: String = "ar", prompt: String = ""): TranscriptResult = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "أدخل مفتاح Groq API من الإعدادات." }
        require(audioFile.exists() && audioFile.length() > 0) { "الملف الصوتي فارغ." }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", if (prompt.startsWith("[HIGH_ACCURACY]")) "whisper-large-v3" else "whisper-large-v3-turbo")
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
            .apply { if (prompt.isNotBlank()) addFormDataPart("prompt", prompt.take(3_000)) }
            .addFormDataPart("timestamp_granularities[]", "segment")
            .apply { if (language.isNotBlank() && language != "auto") addFormDataPart("language", language) }
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        retry(request) { raw -> parseResult(raw) }
    }

    private fun parseResult(raw: String): TranscriptResult {
        val json = JSONObject(raw)
        val text = json.optString("text").trim()
        if (text.isBlank()) error("لم يُرجع Groq نصاً صوتياً.")
        val segments = mutableListOf<TranscriptSegment>()
        val array = json.optJSONArray("segments")
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val start = item.optDouble("start", Double.NaN)
                val end = item.optDouble("end", Double.NaN)
                val segmentText = item.optString("text").trim()
                if (segmentText.isNotBlank() && start.isFinite() && end.isFinite() && end >= start) {
                    segments += TranscriptSegment(start * 1000.0, end * 1000.0, segmentText)
                }
            }
        }
        return TranscriptResult(text, segments)
    }

    private suspend fun <T> retry(request: Request, parse: (String) -> T): T {
        var last: Exception? = null
        repeat(3) { i ->
            try {
                http.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("فشل Groq (${response.code}): ${raw.take(180)}")
                    return parse(raw)
                }
            } catch (e: Exception) {
                last = e
                if (i < 2) delay(500L * (i + 1))
            }
        }
        throw IllegalStateException("تعذّر الاتصال بخدمة تحويل الصوت بعد 3 محاولات. تحقق من الإنترنت والمفتاح.", last)
    }

    companion object {
        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}

data class TranscriptResult(val text: String, val segments: List<TranscriptSegment>)
data class TranscriptSegment(val startMs: Double, val endMs: Double, val text: String)
