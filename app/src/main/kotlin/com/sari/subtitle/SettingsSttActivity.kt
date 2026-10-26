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
            updateVoskStatus()
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
                SettingsRepository(this@SettingsSttActivity).saveSttSettings(
                    selected, deepgramKey.text?.toString().orEmpty(), openAiKey.text?.toString().orEmpty()
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
        val validExisting = File(dir, "am").isDirectory && File(dir, "conf").isDirectory
        if (validExisting) {
            findViewById<android.widget.TextView>(R.id.voskModelStatus).text = "نموذج Vosk موجود محليًا: ${dir.absolutePath}"
            Toast.makeText(this, "النموذج موجود بالفعل؛ لن يعاد تنزيله.", Toast.LENGTH_LONG).show()
            return
        }
        val staging = File(filesDir, "vosk-models-staging")
        findViewById<android.widget.TextView>(R.id.voskModelStatus).text =
            "جارٍ تنزيل النموذج والتحقق منه؛ أداة التنزيل الحالية لا توفر نسبة تقدم."
        Toast.makeText(this, R.string.vosk_download_started, Toast.LENGTH_LONG).show()
        lifecycleScope.launch {
            try {
                if (staging.exists()) staging.deleteRecursively()
                val extracted = ModelDownloader.downloadAndExtract(
                    this@SettingsSttActivity,
                    "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip",
                    staging
                )
                val candidate = extracted.listFiles()?.firstOrNull {
                    it.isDirectory && it.name.startsWith("vosk-model")
                } ?: extracted
                val valid = File(candidate, "am").isDirectory && File(candidate, "conf").isDirectory
                if (!valid) error("اكتمل التنزيل لكن بنية النموذج غير صالحة.")
                val backup = File(filesDir, "vosk-models-backup")
                if (backup.exists()) backup.deleteRecursively()
                if (dir.exists() && !dir.renameTo(backup)) error("تعذر حفظ النموذج السابق بأمان.")
                if (!candidate.renameTo(dir)) {
                    try { candidate.copyRecursively(dir, overwrite = true) }
                    catch (e: Exception) {
                        if (backup.exists()) backup.renameTo(dir)
                        throw e
                    }
                }
                if (!(File(dir, "am").isDirectory && File(dir, "conf").isDirectory)) {
                    dir.deleteRecursively()
                    if (backup.exists()) backup.renameTo(dir)
                    error("فشل التحقق النهائي من النموذج.")
                }
                backup.deleteRecursively()
                staging.deleteRecursively()
                findViewById<android.widget.TextView>(R.id.voskModelStatus).text =
                    "اكتمل تنزيل النموذج والتحقق منه: ${dir.absolutePath}"
                Toast.makeText(this@SettingsSttActivity, getString(R.string.vosk_download_done, dir.absolutePath), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                staging.deleteRecursively()
                findViewById<android.widget.TextView>(R.id.voskModelStatus).text =
                    "فشل تنزيل النموذج: ${e.message ?: "خطأ غير معروف"}"
                Toast.makeText(this@SettingsSttActivity, getString(R.string.vosk_download_failed, e.message ?: "خطأ غير معروف"), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateVoskStatus() {
        val dir = File(filesDir, "vosk-models")
        val valid = File(dir, "am").isDirectory && File(dir, "conf").isDirectory
        findViewById<android.widget.TextView>(R.id.voskModelStatus).text =
            if (valid) "نموذج Vosk موجود محليًا: ${dir.absolutePath}" else "لا يوجد نموذج Vosk صالح مثبت حاليًا."
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { Toast.makeText(this, "تعذر فتح الرابط", Toast.LENGTH_SHORT).show() }
    }
}
