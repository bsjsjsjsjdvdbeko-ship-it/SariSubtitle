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

/**
 * Stage 57: transparent Provider Decision Dashboard.
 *
 * This screen reads the same decision/scoring stores used by runtime routing.
 * It never sends audio/text and never exposes API keys or response bodies.
 */
class ProviderDecisionDashboardActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var taskSpinner: Spinner
    private var selectedTask = ProviderScoringEngine.Task.STT

    private val providers = listOf(
        ProviderConnectionTester.GROQ,
        ProviderConnectionTester.DEEPGRAM,
        ProviderConnectionTester.OPENAI,
        ProviderConnectionTester.GEMINI,
        ProviderConnectionTester.OMNIROUTE,
        ProviderConnectionTester.DEEPL,
        ProviderConnectionTester.AZURE,
        ProviderConnectionTester.HUGGING_FACE
    )

    private val settings by lazy { SubtitleSettings(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) refresh()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 11, 22))
        }
        root.addView(topBar())

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(30))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun topBar(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(8))
        addView(Button(this@ProviderDecisionDashboardActivity).apply {
            text = "‹"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(this@ProviderDecisionDashboardActivity).apply {
            text = "Provider Decision Dashboard"
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@ProviderDecisionDashboardActivity).apply {
            text = "STAGE 57"
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = rounded(Color.rgb(139, 92, 246), 18f)
        })
    }

    private fun refresh() {
        content.removeAllViews()
        content.addView(hero())
        content.addView(taskSelector())
        content.addView(section("Decision Trace — آخر قرارات Routing", "يُعرض سبب الاختيار والبدائل فقط؛ لا يتم تخزين النص أو الصوت."))
        addTraceCards()
        content.addView(section("Current Decision — القرار الحالي", "يعاد حسابه من نفس ProviderDecisionEngine المستخدم في المسار التنفيذي."))
        addCurrentDecision()
        content.addView(section("Score Breakdown — تفصيل الدرجة", "الأرقام مأخوذة من ProviderScoringEngine وProviderQualityFusion، وليست أرقامًا ثابتة."))
        addScoreCards()
        content.addView(section("Privacy", "Decision Trace لا يحتوي API Keys أو Audio أو Transcript أو Translation Text أو Response Body."))
        content.addView(buttonRow())
    }

    private fun hero(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(Color.rgb(24, 34, 58), 20f)
        addView(TextView(this@ProviderDecisionDashboardActivity).apply {
            text = "لماذا اختار Sari Subtitle هذا Provider؟"
            textSize = 21f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        addView(TextView(this@ProviderDecisionDashboardActivity).apply {
            text = "Health + Circuit + Latency + Quality + Quota + Reliability + Reference Fusion + Adaptive Routing"
            textSize = 13f
            setTextColor(Color.rgb(190, 198, 220))
            setPadding(0, dp(7), 0, dp(4))
        })
        addView(TextView(this@ProviderDecisionDashboardActivity).apply {
            text = "هذا Dashboard للشفافية؛ لا يغيّر قرار الـ Router ولا يرسل طلبات خارجية."
            textSize = 11f
            setTextColor(Color.rgb(140, 150, 175))
        })
    }

    private fun taskSelector(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(16), dp(2), dp(4))
        addView(TextView(this@ProviderDecisionDashboardActivity).apply {
            text = "نوع المهمة — Task"
            textSize = 13f
            setTextColor(Color.rgb(167, 176, 200))
        })
        taskSpinner = Spinner(this@ProviderDecisionDashboardActivity)
        taskSpinner.adapter = android.widget.ArrayAdapter(
            this@ProviderDecisionDashboardActivity,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("STT — Speech-to-Text", "Translation — الترجمة")
        )
        taskSpinner.setSelection(if (selectedTask == ProviderScoringEngine.Task.STT) 0 else 1)
        taskSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val next = if (position == 0) ProviderScoringEngine.Task.STT else ProviderScoringEngine.Task.TRANSLATION
                if (next != selectedTask) {
                    selectedTask = next
                    refresh()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })
        addView(taskSpinner)
    }

    private fun addTraceCards() {
        val entries = ProviderDecisionTraceStore.recent(this, 8)
            .filter { it.task == selectedTask.name }
        if (entries.isEmpty()) {
            content.addView(emptyCard("لا توجد قرارات مسجلة لهذه المهمة بعد."))
            return
        }
        entries.forEach { entry ->
            val card = card()
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.atMs))
            card.addView(title("${entry.selected ?: "No Provider"}  •  $time"))
            card.addView(detail("Reason: ${entry.reason}"))
            card.addView(detail("Candidates: ${entry.candidates.joinToString("  |  ")}"))
            content.addView(card)
        }
    }

    private fun addCurrentDecision() {
        val configured = providers.filter(::isConfigured)
        if (configured.isEmpty()) {
            content.addView(emptyCard("لا يوجد Provider مهيأ حاليًا. افتح API Keys أولًا."))
            return
        }
        val result = ProviderDecisionEngine.decide(
            context = this,
            providers = configured,
            configured = ::isConfigured,
            task = selectedTask,
            recordTrace = false
        )
        val selected = result.selected
        val card = card()
        card.addView(title(selected?.provider ?: "No eligible Provider"))
        card.addView(detail("Selected: ${selected?.eligible == true}"))
        card.addView(detail("Score: ${selected?.score ?: "—"}"))
        card.addView(detail("Circuit: ${selected?.circuit ?: "—"}"))
        card.addView(detail("Health: ${selected?.healthState ?: "unknown"}"))
        card.addView(detail("Reason: ${result.selectionReason}"))
        content.addView(card)
    }

    private fun addScoreCards() {
        val configured = providers.filter(::isConfigured)
        if (configured.isEmpty()) {
            content.addView(emptyCard("لا توجد بيانات Score لأن لا يوجد Provider مهيأ."))
            return
        }
        configured.map { ProviderScoringEngine.score(this, it, selectedTask) }
            .sortedByDescending { it.total }
            .forEach { score ->
                val health = ProviderHealthStore.get(this, score.provider)
                val metrics = ProviderMetricsStore.get(this, score.provider)
                val circuit = ProviderRoutingEngine.circuit(this, score.provider)
                val card = card()
                card.addView(title("${score.provider}  •  TOTAL ${if (score.total == Int.MIN_VALUE) "BLOCKED" else score.total}"))
                card.addView(detail("Health = ${score.health}    Latency = ${score.latency}    Quality = ${score.quality}"))
                card.addView(detail("Quota = ${score.quota}    Reliability = ${score.reliability}    Reference Fusion = ${score.reference}"))
                card.addView(detail("Circuit = $circuit    Health State = ${health?.state ?: "unknown"}"))
                card.addView(detail("Quality samples = ${metrics?.qualitySamples ?: 0}    Degradations = ${metrics?.qualityDegradationCount ?: 0}"))
                card.addView(detail("${score.reason}"))
                content.addView(card)
            }
    }

    private fun buttonRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, 0)
        addView(Button(this@ProviderDecisionDashboardActivity).apply {
            text = "مسح Decision Trace"
            setOnClickListener {
                ProviderDecisionTraceStore.clear(this@ProviderDecisionDashboardActivity)
                Toast.makeText(this@ProviderDecisionDashboardActivity, "تم مسح سجل القرارات", Toast.LENGTH_SHORT).show()
                refresh()
            }
        })
        addView(Button(this@ProviderDecisionDashboardActivity).apply {
            text = "تحديث Dashboard"
            setOnClickListener { refresh() }
        })
    }

    private fun section(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(20), dp(2), dp(8))
        addView(TextView(this@ProviderDecisionDashboardActivity).apply {
            text = title
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        addView(TextView(this@ProviderDecisionDashboardActivity).apply {
            text = subtitle
            textSize = 11f
            setTextColor(Color.rgb(140, 150, 175))
            setPadding(0, dp(3), 0, 0)
        })
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(14), dp(15), dp(14))
        background = rounded(Color.rgb(17, 24, 42), 16f, Color.rgb(48, 59, 90))
        setMargins(dp(0), dp(5), dp(0), dp(5))
    }

    private fun emptyCard(message: String): View = card().apply { addView(detail(message)) }

    private fun title(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 15f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun detail(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(Color.rgb(167, 176, 200))
        setPadding(0, dp(4), 0, 0)
    }

    private fun isConfigured(provider: String): Boolean = when (provider) {
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

    private fun LinearLayout.setMargins(l: Int, t: Int, r: Int, b: Int) {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(l, t, r, b) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radius: Float, stroke: Int? = null): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            stroke?.let { setStroke(dp(1), it) }
        }
}
