package com.sari.subtitle

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class SettingsSttActivity : AppCompatActivity() {
    private lateinit var provider: MaterialAutoCompleteTextView
    private lateinit var deepgramKey: TextInputEditText
    private lateinit var openAiKey: TextInputEditText
    private val providers by lazy { listOf(getString(R.string.stt_model_groq), getString(R.string.stt_model_deepgram), getString(R.string.stt_model_vosk)) }
    private val http by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_stt)
        findViewById<MaterialToolbar>(R.id.sttToolbar).setNavigationOnClickListener { finish() }
        provider = findViewById(R.id.sttProviderSpinner)
        provider.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, providers))
        provider.setOnClickListener { provider.showDropDown() }
        deepgramKey = findViewById(R.id.deepgramKeyInput)
        openAiKey = findViewById(R.id.openAiKeyInput)
        findViewById<MaterialButton>(R.id.getDeepgramKeyButton).setOnClickListener { openUrl("https://console.deepgram.com/") }
        findViewById<MaterialButton>(R.id.getOpenAiKeyButton).setOnClickListener { openUrl("https://platform.openai.com/api-keys") }
        findViewById<MaterialButton>(R.id.testDeepgramButton).setOnClickListener { testKey("Deepgram", "https://api.deepgram.com/v1/projects", deepgramKey.text?.toString().orEmpty(), "Token") }
        findViewById<MaterialButton>(R.id.testOpenAiButton).setOnClickListener { testKey("OpenAI", "https://api.openai.com/v1/models", openAiKey.text?.toString().orEmpty(), "Bearer") }
        findViewById<MaterialButton>(R.id.saveSttSettingsButton).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.downloadVoskButton).setOnClickListener { downloadVosk() }
        lifecycleScope.launch {
            val settings = SettingsRepository(this@SettingsSttActivity).load()
            provider.setText(when (settings.sttProvider) { "deepgram" -> providers[1]; "vosk" -> providers[2]; else -> providers[0] }, false)
            deepgramKey.setText(settings.deepgramKey)
            openAiKey.setText(settings.openAiKey)
        }
    }

    private fun save() {
        val selected = when (provider.text.toString()) {
            providers[1] -> "deepgram"
            providers[2] -> "vosk"
            else -> "groq"
        }
        lifecycleScope.launch {
            try {
                val repository = SettingsRepository(this@SettingsSttActivity)
                val previous = repository.load()
                repository.saveSttSettings(
                    selected,
                    deepgramKey.text?.toString().orEmpty().trim().ifBlank { previous.deepgramKey },
                    openAiKey.text?.toString().orEmpty().trim().ifBlank { previous.openAiKey }
                )
                Toast.makeText(this@SettingsSttActivity, R.string.stt_settings_saved, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsSttActivity, e.message ?: getString(R.string.settings_save_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun testKey(name: String, url: String, key: String, scheme: String) {
        if (key.isBlank()) {
            Toast.makeText(this, getString(R.string.stt_test_failed, name, "أدخل المفتاح أولاً"), Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            try {
                val code = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(url).header("Authorization", "$scheme $key").get().build()
                    http.newCall(request).execute().use { it.code }
                }
                if (code in 200..299) Toast.makeText(this@SettingsSttActivity, getString(R.string.stt_test_ok, name), Toast.LENGTH_LONG).show()
                else Toast.makeText(this@SettingsSttActivity, getString(R.string.stt_test_failed, name, "HTTP $code"), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsSttActivity, getString(R.string.stt_test_failed, name, e.message ?: "خطأ اتصال"), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadVosk() {
        val dir = File(filesDir, "vosk-models")
        val existingModel = dir.listFiles()?.firstOrNull {
            it.isDirectory && File(it, "am").isDirectory && File(it, "conf").isDirectory
        }
        if (existingModel != null) {
            Toast.makeText(this, "نموذج Vosk موجود وصالح: ${existingModel.name}", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.vosk_download_started, Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            try {
                val result = ModelDownloader.downloadAndExtract(
                    this@SettingsSttActivity,
                    "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip",
                    dir
                )
                Toast.makeText(this@SettingsSttActivity, getString(R.string.vosk_download_done, result.absolutePath), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsSttActivity, getString(R.string.vosk_download_failed, e.message ?: "خطأ غير معروف"), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { Toast.makeText(this, "تعذر فتح الرابط", Toast.LENGTH_SHORT).show() }
    }
}
