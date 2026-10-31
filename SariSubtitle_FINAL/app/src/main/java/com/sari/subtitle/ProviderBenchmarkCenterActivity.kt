package com.sari.subtitle

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stage 53: real benchmark dashboard for configured translation providers. */
class ProviderBenchmarkCenterActivity : Activity() {
    private lateinit var root: LinearLayout
    private val settings by lazy { SubtitleSettings(applicationContext) }
    private val benchmarkProviders = listOf(
        ProviderConnectionTester.GEMINI,
        ProviderConnectionTester.OMNIROUTE
    )
    private val cases = listOf(
        ProviderBenchmarkEngine.Case(
            "Hello, how are you today?",
            "en",
            referenceTranslation = "مرحبا كيف حالك اليوم؟"
        ),
        ProviderBenchmarkEngine.Case(
            "The network controller detected abnormal traffic and blocked the flow.",
            "en",
            referenceTranslation = "اكتشف متحكم الشبكة حركة غير طبيعية وحظر التدفق."
        ),
        ProviderBenchmarkEngine.Case(
            "Please wait a moment while the live subtitle is synchronized with the audio.",
            "en",
            referenceTranslation = "يرجى الانتظار لحظة حتى تتم مزامنة الترجمة المباشرة مع الصوت."
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 11, 22))
        }
        root.addView(topBar())
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(28))
        }
        content.addView(hero())
        content.addView(section("Benchmark الحقيقي", "كل تشغيل يرسل حالات اختبار فعلية إلى المزود المهيأ ويقيس النتيجة التي رجعت فعليًا."))
        benchmarkProviders.forEach { provider -> content.addView(providerCard(provider)) }
        content.addView(section("Reference-Based Evaluation", "Stage 54 يضيف Ground Truth مرجعيًا ثابتًا ويحسب BLEU و chrF. هذه المقاييس تقارن النصوص، ولا تعني وحدها أن الترجمة صحيحة بشريًا."))
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun topBar(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(8))
        addView(Button(this@ProviderBenchmarkCenterActivity).apply {
            text = "‹"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(this@ProviderBenchmarkCenterActivity).apply {
            text = "Provider Benchmark Center"
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@ProviderBenchmarkCenterActivity).apply {
            text = "STAGE 54"
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = rounded(Color.rgb(139, 92, 246), 18f)
        })
    }

    private fun hero(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(Color.rgb(24, 34, 58), 20f)
        addView(TextView(this@ProviderBenchmarkCenterActivity).apply {
            text = "Real Provider Benchmark"
            textSize = 21f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        addView(TextView(this@ProviderBenchmarkCenterActivity).apply {
            text = "Latency + Structural Quality + Reference BLEU/chrF + Routing Score. لا توجد قيم latency أو quality مصطنعة."
            textSize = 13f
            setTextColor(Color.rgb(190, 198, 220))
            setPadding(0, dp(6), 0, dp(10))
        })
        addView(Button(this@ProviderBenchmarkCenterActivity).apply {
            text = "تشغيل Benchmark لكل المزوّدين المهيئين"
            setOnClickListener { runAll() }
        })
        addView(TextView(this@ProviderBenchmarkCenterActivity).apply {
            text = "عدد الحالات: ${cases.size} • الهدف: العربية (ar)"
            textSize = 11f
            setTextColor(Color.rgb(140, 150, 175))
            setPadding(0, dp(7), 0, 0)
        })
    }

    private fun providerCard(provider: String): View {
        val configured = isConfigured(provider)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = rounded(Color.rgb(17, 24, 42), 16f, Color.rgb(48, 59, 90))
            setMargins(dp(0), dp(5), dp(0), dp(5))
        }
        val title = TextView(this).apply {
            text = provider
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        val status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(167, 176, 200))
            setPadding(0, dp(4), 0, dp(8))
            text = benchmarkText(provider, configured)
        }
        card.addView(title)
        card.addView(status)
        val button = Button(this).apply {
            text = "تشغيل Benchmark"
            isEnabled = configured
            setOnClickListener { runOne(provider, status, this) }
        }
        card.addView(button)
        return card
    }

    private fun runAll() {
        val configured = benchmarkProviders.filter(::isConfigured)
        if (configured.isEmpty()) {
            Toast.makeText(this, "لا يوجد مزود ترجمة مهيأ للـ Benchmark", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            configured.forEach { provider -> runBenchmark(provider) }
            runOnUiThread {
                buildUi()
                Toast.makeText(this, "اكتمل Benchmark للمزوّدين المهيئين", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun runOne(provider: String, status: TextView, button: Button) {
        button.isEnabled = false
        button.text = "جاري القياس…"
        status.text = "جاري إرسال ${cases.size} حالات فعلية إلى $provider …"
        Thread {
            val result = runCatching { runBenchmark(provider) }
                .getOrElse { ProviderBenchmarkEngine.ProviderResult(provider, cases.size, 0, cases.size, null, null, 0, listOf("benchmark_error")) }
            runOnUiThread {
                status.text = benchmarkText(provider, true)
                button.isEnabled = isConfigured(provider)
                button.text = "إعادة Benchmark"
                Toast.makeText(this, "$provider: ${result.successes}/${result.samples} نجاح", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun runBenchmark(provider: String): ProviderBenchmarkEngine.ProviderResult {
        val result = ProviderBenchmarkEngine.benchmarkTranslation(this, listOf(provider), cases).single()
        ProviderBenchmarkStore.save(this, result)
        OperationLogStore.add(
            this,
            "Provider Benchmark",
            provider,
            "translation-benchmark",
            result.averageLatencyMs ?: 0L,
            "${result.successes}/${result.samples}",
            "quality=${result.qualityScore ?: "unknown"}; reference=${result.referenceScore ?: "unknown"}; bleu=${result.referenceBleu?.let { "%.3f".format(Locale.US, it) } ?: "unknown"}; chrf=${result.referenceChrf?.let { "%.3f".format(Locale.US, it) } ?: "unknown"}",
            result.qualityReasons.joinToString(",").take(180)
        )
        return result
    }

    private fun benchmarkText(provider: String, configured: Boolean): String {
        if (!configured) return "○ غير مهيأ — لا يوجد Benchmark بدون إعداد المزود"
        val record = ProviderBenchmarkStore.get(this, provider) ?: return "● لم يُشغّل Benchmark بعد"
        val score = ProviderScoringEngine.score(this, provider, ProviderScoringEngine.Task.TRANSLATION)
        val time = if (record.runAtMs > 0) SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(record.runAtMs)) else "-"
        return buildString {
            append("success=${record.successes}/${record.samples}")
            append(" • failures=${record.failures}")
            append(" • latency=${record.averageLatencyMs?.let { "${it}ms" } ?: "unknown"}")
            append(" • quality=${record.qualityScore?.toString() ?: "unknown"}")
            append(" • reference=${record.referenceScore?.toString() ?: "unknown"}")
            append(" • BLEU=${record.referenceBleu?.let { "%.3f".format(Locale.US, it) } ?: "unknown"}")
            append(" • chrF=${record.referenceChrf?.let { "%.3f".format(Locale.US, it) } ?: "unknown"}")
            append(" • routingScore=${score.total}")
            append(" • run=$time")
            if (record.qualityReasons.isNotEmpty()) append("\nsignals=${record.qualityReasons.joinToString(", ")}")
        }
    }

    private fun isConfigured(provider: String): Boolean = when (provider) {
        ProviderConnectionTester.GEMINI -> settings.hasGeminiApiKey()
        ProviderConnectionTester.OMNIROUTE -> settings.omniRouteEnabled || settings.hasOmniRouteApiKey()
        else -> false
    }

    private fun section(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(20), dp(2), dp(8))
        addView(TextView(this@ProviderBenchmarkCenterActivity).apply {
            text = title; textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        })
        addView(TextView(this@ProviderBenchmarkCenterActivity).apply {
            text = subtitle; textSize = 11f; setTextColor(Color.rgb(140, 150, 175)); setPadding(0, dp(3), 0, 0)
        })
    }

    private fun LinearLayout.setMargins(l: Int, t: Int, r: Int, b: Int) {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(l, t, r, b) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radius: Float, stroke: Int? = null): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            stroke?.let { setStroke(dp(1), it) }
        }
}
