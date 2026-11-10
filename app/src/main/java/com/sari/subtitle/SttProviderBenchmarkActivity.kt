package com.sari.subtitle

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.Locale

/** Explicit, user-triggered STT comparison. Uploads only after the user selects a file and presses Run. */
class SttProviderBenchmarkActivity : Activity() {
    private lateinit var settings: SubtitleSettings
    private lateinit var fileLabel: TextView
    private lateinit var language: Spinner
    private lateinit var reference: EditText
    private lateinit var results: TextView
    private var audioUri: android.net.Uri? = null
    private val providers = listOf(ProviderConnectionTester.GROQ, ProviderConnectionTester.DEEPGRAM, ProviderConnectionTester.OMNIROUTE)
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); settings = SubtitleSettings(applicationContext); render() }
    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 16, 18, 24); setBackgroundColor(Color.rgb(8, 11, 22)) }
        root.addView(TextView(this).apply { text = "STT Provider Benchmark"; textSize = 22f; setTextColor(Color.WHITE); setPadding(0, 8, 0, 16) })
        root.addView(TextView(this).apply { text = "يقارن Groq وDeepgram وOmniRoute على نفس الملف الصوتي. لن يُرسل أي ملف حتى تختاره وتضغط تشغيل. قد يستهلك كل مزود رصيد API الخاص به."; textSize = 14f; setTextColor(Color.LTGRAY); setPadding(0, 0, 0, 14) })
        root.addView(Button(this).apply { text = "اختيار ملف صوتي / فيديو"; setOnClickListener { val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }; startActivityForResult(i, PICK_AUDIO) } })
        fileLabel = TextView(this).apply { text = "لم يتم اختيار ملف"; setTextColor(Color.WHITE); setPadding(0, 10, 0, 12) }; root.addView(fileLabel)
        root.addView(TextView(this).apply { text = "لغة الصوت"; setTextColor(Color.WHITE) })
        language = Spinner(this).apply { adapter = ArrayAdapter(this@SttProviderBenchmarkActivity, android.R.layout.simple_spinner_dropdown_item, listOf("اكتشاف تلقائي", "العربية", "الإنجليزية")) }; root.addView(language)
        root.addView(TextView(this).apply { text = "النص الصحيح المرجعي (اختياري لحساب WER/CER)"; setTextColor(Color.WHITE); setPadding(0, 12, 0, 5) })
        reference = EditText(this).apply { hint = "اكتب التفريغ الصحيح كما قيل في الصوت"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); minLines = 3; gravity = Gravity.TOP; setBackgroundColor(Color.rgb(25, 32, 48)) }; root.addView(reference)
        root.addView(Button(this).apply { text = "تشغيل المقارنة على كل المزودين المهيئين"; setOnClickListener { runBenchmark(this) } })
        results = TextView(this).apply { text = "النتائج ستظهر هنا. WER/CER لا يُحسبان إلا عند إدخال نص مرجعي."; textSize = 13f; setTextColor(Color.WHITE); setPadding(0, 16, 0, 0) }; root.addView(results)
        root.addView(Button(this).apply { text = "رجوع"; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(root) })
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { super.onActivityResult(requestCode, resultCode, data); if (requestCode == PICK_AUDIO && resultCode == RESULT_OK) { audioUri = data?.data; audioUri?.let { runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }; fileLabel.text = audioUri?.lastPathSegment ?: "تم اختيار الملف" } }
    private fun runBenchmark(button: Button) {
        val uri = audioUri ?: run { Toast.makeText(this, "اختر ملفاً أولاً", Toast.LENGTH_SHORT).show(); return }
        val configured = providers.filter { when (it) { ProviderConnectionTester.GROQ -> settings.hasGroqApiKey(); ProviderConnectionTester.DEEPGRAM -> settings.hasDeepgramApiKey(); else -> settings.omniRouteEnabled && settings.hasOmniRouteApiKey() } }
        if (configured.isEmpty()) { Toast.makeText(this, "أدخل مفتاح مزود واحد على الأقل من الإعدادات", Toast.LENGTH_LONG).show(); return }
        val lang = when (language.selectedItemPosition) { 1 -> "ar"; 2 -> "en"; else -> "auto" }
        val truth = reference.text.toString().trim().takeIf { it.isNotEmpty() }
        button.isEnabled = false; results.text = "جارٍ الاختبار… سيتم إرسال الملف نفسه لكل مزود مهيأ (${configured.joinToString()})."
        Thread {
            val measured = SttProviderBenchmarkEngine.run(this, uri, lang, truth, configured)
            runOnUiThread {
                results.text = buildString {
                    measured.forEach { r ->
                        append("\n\n${r.provider}\n")
                        if (r.error != null) append("فشل: ${r.error}") else {
                            append("الزمن: ${r.latencyMs} ms • اللغة: ${r.detectedLanguage}\n")
                            append("WER: ${r.wordErrorRate?.let { "%.2f%%".format(Locale.US, it * 100) } ?: "أدخل نصاً مرجعياً"}\n")
                            append("CER: ${r.characterErrorRate?.let { "%.2f%%".format(Locale.US, it * 100) } ?: "أدخل نصاً مرجعياً"}\n")
                            append("النص المستخرج: ${r.transcript.take(1600)}")
                            OperationLogStore.add(this@SttProviderBenchmarkActivity, "STT Benchmark", r.provider, "stt-benchmark", r.latencyMs ?: 0L, "success", "WER=${r.wordErrorRate}; CER=${r.characterErrorRate}; language=${r.detectedLanguage}", "audio benchmark")
                        }
                    }
                    append("\n\nملاحظة: لا تُخزّن هذه الشاشة الصوت أو النص المرجعي في سجل النتائج؛ النص المعروض مؤقت داخل الشاشة.")
                }
                button.isEnabled = true
            }
        }.start()
    }
    companion object { private const val PICK_AUDIO = 7412 }
}
