package com.sari.subtitle

import com.sari.subtitle.v2.R
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stage 37: real provider health center. */
class ProviderCenterActivity : Activity() {
    private lateinit var root: LinearLayout
    private val settings by lazy { SubtitleSettings(applicationContext) }
    private val providers = listOf(
        ProviderConnectionTester.GROQ,
        ProviderConnectionTester.DEEPGRAM,
        ProviderConnectionTester.GEMINI,
        ProviderConnectionTester.OMNIROUTE,
        ProviderConnectionTester.DEEPL,
        ProviderConnectionTester.AZURE,
        ProviderConnectionTester.HUGGING_FACE,
        ProviderConnectionTester.OPENAI
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(8, 11, 22)) }
        root.addView(topBar())
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(28)) }
        content.addView(hero())
        content.addView(section("STT — Speech-to-Text", "حالة Groq وDeepgram وOpenAI قبل استخدامها"))
        content.addView(providerCard(ProviderConnectionTester.GROQ, "Whisper / API models", settings.hasGroqApiKey()))
        content.addView(providerCard(ProviderConnectionTester.DEEPGRAM, "Nova-3 / Projects API", settings.hasDeepgramApiKey()))
        content.addView(providerCard(ProviderConnectionTester.OPENAI, "Whisper / Models API", settings.hasOpenAiApiKey()))
        content.addView(section("Translation — الترجمة", "اختبار مصادقة منخفض الحمولة؛ لا يُرسل صوتًا أو SRT"))
        content.addView(providerCard(ProviderConnectionTester.GEMINI, "Gemini Models API", settings.hasGeminiApiKey()))
        content.addView(providerCard(ProviderConnectionTester.OMNIROUTE, "OpenAI-compatible /v1/models", settings.omniRouteEnabled || settings.hasOmniRouteApiKey()))
        content.addView(providerCard(ProviderConnectionTester.DEEPL, "Usage API", settings.hasDeepLApiKey()))
        content.addView(providerCard(ProviderConnectionTester.AZURE, "Translator API", settings.hasAzureTranslatorKey()))
        content.addView(providerCard(ProviderConnectionTester.HUGGING_FACE, "whoami-v2", settings.hasHuggingFaceToken()))
        content.addView(section("اختيار المسار", "Stage 38 يفعّل الاختيار الديناميكي حسب Health وLatency وCircuit Breaker؛ Vosk يبقى fallback محلياً"))
        content.addView(selectionCard())
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun topBar(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(8))
        addView(Button(this@ProviderCenterActivity).apply { text = "‹"; setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(this@ProviderCenterActivity).apply {
            text = "مركز مزوّدي الخدمات"; textSize = 19f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@ProviderCenterActivity).apply {
            text = "STAGE 53"; textSize = 10f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(dp(9), dp(6), dp(9), dp(6));
            background = rounded(Color.rgb(139,92,246), 18f)
        })
    }

    private fun hero(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)); background = rounded(Color.rgb(24,34,58), 20f)
        addView(TextView(this@ProviderCenterActivity).apply { text = "Provider Health & Routing Center"; textSize = 21f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
        addView(TextView(this@ProviderCenterActivity).apply {
            text = "الحالة لا تعني وجود مفتاح فقط. Ready = طلب حقيقي نجح. 401/403 = مصادقة، 429 = Rate Limit، 5xx = مشكلة خادم، ومشكلة الشبكة منفصلة."
            textSize = 13f; setTextColor(Color.rgb(190,198,220)); setPadding(0, dp(6), 0, dp(10))
        })
        addView(Button(this@ProviderCenterActivity).apply { text = "اختبار كل المزوّدين المهيئين وتحديث Health"; setOnClickListener { testAll() } })
        addView(Button(this@ProviderCenterActivity).apply { text = "فتح Provider Benchmark Center — Stage 53"; setOnClickListener { startActivity(android.content.Intent(this@ProviderCenterActivity, ProviderBenchmarkCenterActivity::class.java)) } })
        addView(Button(this@ProviderCenterActivity).apply { text = "فتح Provider Decision Dashboard — Stage 57"; setOnClickListener { startActivity(android.content.Intent(this@ProviderCenterActivity, ProviderDecisionDashboardActivity::class.java)) } })
    }

    private fun section(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(20), dp(2), dp(8))
        addView(TextView(this@ProviderCenterActivity).apply { text = title; textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
        addView(TextView(this@ProviderCenterActivity).apply { text = subtitle; textSize = 11f; setTextColor(Color.rgb(140,150,175)); setPadding(0, dp(3), 0, 0) })
    }

    private fun providerCard(provider: String, model: String, configured: Boolean): View {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(14)); background = rounded(Color.rgb(17,24,42), 16f, Color.rgb(48,59,90)); setMargins(dp(0), dp(5), dp(0), dp(5)) }
        val title = TextView(this).apply { text = provider; textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD }
        val state = TextView(this).apply { textSize = 12f; setTextColor(Color.rgb(167,176,200)); text = stateText(provider, configured) }
        card.addView(title); card.addView(TextView(this).apply { text = model; textSize = 11f; setTextColor(Color.rgb(125,135,160)); setPadding(0, dp(2), 0, dp(5)) }); card.addView(state)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(Button(this).apply { text = "اختبار الاتصال"; isEnabled = configured || provider == ProviderConnectionTester.OMNIROUTE; setOnClickListener { testOne(provider, state, this) } }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row)
        return card
    }

    private fun selectionCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(14)); background = rounded(Color.rgb(17,24,42), 16f)
        addView(TextView(this@ProviderCenterActivity).apply { text = "STT الأساسي"; textSize = 12f; setTextColor(Color.rgb(167,176,200)) })
        val stt = Spinner(this@ProviderCenterActivity)
        stt.adapter = android.widget.ArrayAdapter(this@ProviderCenterActivity, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("تلقائي — Groq → Deepgram → Vosk", "Groq", "Deepgram Nova-3", "OpenAI Whisper", "Vosk محلي"))
        stt.setSelection(when (settings.sttProvider) { SubtitleSettings.STT_GROQ -> 1; SubtitleSettings.STT_DEEPGRAM -> 2; SubtitleSettings.STT_OPENAI -> 3; SubtitleSettings.STT_LOCAL -> 4; else -> 0 })
        stt.setOnItemSelectedListener(simpleListener { i -> settings.sttProvider = when(i) {1->SubtitleSettings.STT_GROQ;2->SubtitleSettings.STT_DEEPGRAM;3->SubtitleSettings.STT_OPENAI;4->SubtitleSettings.STT_LOCAL;else->SubtitleSettings.STT_AUTO} })
        addView(stt)
        addView(TextView(this@ProviderCenterActivity).apply { text = "الاختيار الحالي: ${settings.sttProviderLabel}. المسار التنفيذي في SariSubtitleActivity يبقى كما هو في Stage 37."; textSize = 11f; setTextColor(Color.rgb(140,150,175)); setPadding(0, dp(5), 0, 0) })
    }

    private fun stateText(provider: String, configured: Boolean): String {
        val h = ProviderHealthStore.get(this, provider)
        if (h == null) return if (configured) "● لم يُختبر بعد — وجود المفتاح ليس نجاح اتصال" else "○ غير مهيأ"
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(h.checkedAt))
        val circuit = ProviderRoutingEngine.circuit(this, provider)
        val circuitText = when (circuit) {
            ProviderRoutingEngine.CircuitState.CLOSED -> "CLOSED"
            ProviderRoutingEngine.CircuitState.OPEN -> "OPEN"
            ProviderRoutingEngine.CircuitState.HALF_OPEN -> "HALF_OPEN"
        }
        val quota = if (h.quotaRemainingRequests != null && h.quotaLimitRequests != null) " • quota=${h.quotaRemainingRequests}/${h.quotaLimitRequests}" else " • quota=unknown"
        val scoreBreakdown = ProviderScoringEngine.score(this, provider, ProviderScoringEngine.Task.STT)
        val quality = ProviderMetricsStore.get(this, provider)?.qualityScore?.toString() ?: "unknown"
        return "${label(h.state)}  • ${h.latencyMs}ms  • score=${scoreBreakdown.total}  • quality=$quality  • CB=$circuitText$quota  • $time${if (h.detail.isNotBlank()) "\n${h.detail}" else ""}"
    }

    private fun testOne(provider: String, stateView: TextView, button: Button) {
        button.isEnabled = false; button.text = "جاري الاختبار…"
        Thread {
            val result = try { if (provider == ProviderConnectionTester.AZURE) ProviderConnectionTester.testAzure(this) else ProviderConnectionTester.test(this, provider) }
                catch (e: Exception) { ProviderConnectionTester.Result(provider, ProviderHealthStore.State.ERROR, 0, 0, e.message ?: "خطأ") }
            val health = ProviderHealthStore.Health(provider, result.state, result.latencyMs, System.currentTimeMillis(), result.httpCode, result.detail, result.retryAfterSec, 0, 0L, result.quotaRemainingRequests, result.quotaLimitRequests, result.quotaResetAtMs)
            ProviderMetricsStore.recordQuota(this, provider, result.quotaRemainingRequests, result.quotaLimitRequests, result.quotaResetAtMs)
            ProviderHealthStore.save(this, health)
            OperationLogStore.add(this, "اختبار مزوّد", provider, "health-check", result.latencyMs, result.state.name, "HTTP=${result.httpCode}", result.detail)
            runOnUiThread { stateView.text = stateText(provider, true); button.isEnabled = true; button.text = "اختبار الاتصال"; Toast.makeText(this, "$provider: ${label(result.state)}", Toast.LENGTH_SHORT).show() }
        }.start()
    }

    private fun testAll() {
        Thread {
            providers.filter { isConfigured(it) }.forEach { provider ->
                val result = runCatching { if (provider == ProviderConnectionTester.AZURE) ProviderConnectionTester.testAzure(this) else ProviderConnectionTester.test(this, provider) }
                    .getOrElse { ProviderConnectionTester.Result(provider, ProviderHealthStore.State.ERROR, 0, 0, it.message ?: "خطأ") }
                ProviderMetricsStore.recordQuota(this, provider, result.quotaRemainingRequests, result.quotaLimitRequests, result.quotaResetAtMs)
                ProviderHealthStore.save(this, ProviderHealthStore.Health(provider, result.state, result.latencyMs, System.currentTimeMillis(), result.httpCode, result.detail, result.retryAfterSec, 0, 0L, result.quotaRemainingRequests, result.quotaLimitRequests, result.quotaResetAtMs))
                OperationLogStore.add(this, "اختبار مزوّد", provider, "health-check", result.latencyMs, result.state.name, "HTTP=${result.httpCode}", result.detail)
            }
            runOnUiThread { buildUi(); Toast.makeText(this, "اكتملت اختبارات المزوّدين المهيئين", Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun isConfigured(provider: String): Boolean = when(provider) {
        ProviderConnectionTester.GROQ -> settings.hasGroqApiKey()
        ProviderConnectionTester.DEEPGRAM -> settings.hasDeepgramApiKey()
        ProviderConnectionTester.OPENAI -> settings.hasOpenAiApiKey()
        ProviderConnectionTester.GEMINI -> settings.hasGeminiApiKey()
        ProviderConnectionTester.OMNIROUTE -> settings.omniRouteEnabled || settings.hasOmniRouteApiKey()
        ProviderConnectionTester.DEEPL -> settings.hasDeepLApiKey()
        ProviderConnectionTester.AZURE -> settings.hasAzureTranslatorKey()
        ProviderConnectionTester.HUGGING_FACE -> settings.hasHuggingFaceToken()
        else -> false
    }

    private fun label(state: ProviderHealthStore.State): String = when(state) {
        ProviderHealthStore.State.READY -> "● READY — اتصال ناجح"
        ProviderHealthStore.State.NOT_CONFIGURED -> "○ NOT CONFIGURED — غير مهيأ"
        ProviderHealthStore.State.AUTH_ERROR -> "✕ AUTH ERROR — مفتاح/صلاحية"
        ProviderHealthStore.State.RATE_LIMITED -> "! RATE LIMITED — حد الطلبات"
        ProviderHealthStore.State.SERVER_ERROR -> "! SERVER ERROR — 5xx"
        ProviderHealthStore.State.NETWORK_ERROR -> "✕ NETWORK ERROR — شبكة/مهلة"
        ProviderHealthStore.State.ERROR -> "✕ ERROR — فشل الاختبار"
    }

    private fun simpleListener(block: (Int) -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = block(position)
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    private fun LinearLayout.setMargins(l: Int, t: Int, r: Int, b: Int) { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(l,t,r,b) } }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(fill: Int, radius: Float, stroke: Int? = null): android.graphics.drawable.Drawable = android.graphics.drawable.GradientDrawable().apply { setColor(fill); cornerRadius = radius; stroke?.let { setStroke(dp(1), it) } }
}
