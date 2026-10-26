package com.sari.subtitle

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class ProviderDashboardActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_provider_dashboard)
        val output=findViewById<TextView>(R.id.providerDashboardOutput)
        val run=findViewById<MaterialButton>(R.id.runProviderBenchmarkButton)
        fun refresh(){ lifecycleScope.launch { try {
            val orch=AiOrchestrator(this@ProviderDashboardActivity)
            orch.refreshOmniRouteMonitoring()
            val settings=SettingsRepository(this@ProviderDashboardActivity).load()
            orch.runAutomaticTranslationBenchmark(settings.omniRouteTranslationModels.split(','), force=false)
            val s=orch.dashboardSnapshot()
            val text=buildString {
                append("SARI AI ORCHESTRATION\n")
                append("Samples: ${s.sampleCount} | Failed/Fallback events: ${s.fallbackCount} | Healthy: ${s.healthyCount}\n\n")
                if(s.scores.isEmpty()) append("لا توجد قياسات بعد. شغّل الترجمة الحية أو Benchmark.\n")
                s.scores.forEach { x ->
                    append("${x.taskType} · ${x.modelId}\n")
                    append("Sari Provider Score: ${"%.1f".format(x.score)}/100\n")
                    append("Observed Quality/Accuracy Proxy: ${"%.1f".format(x.qualityProxy*100)}%\n")
                    append("Success: ${"%.1f".format(x.successRate*100)}% | Latency: ${"%.0f".format(x.avgLatencyMs)} ms\n")
                    append("Rate remaining: ${x.rateLimitRemaining?.toString() ?: "غير متاح من upstream"} | Quota remaining: ${x.quotaRemaining?.toString() ?: "غير متاح"}\n")
                    append("Health: ${if(x.unhealthy) "COOLDOWN / LIMITED" else "HEALTHY"}\n\n")
                }
                if(s.runtime.isNotEmpty()) {
                    append("Runtime observations are from real HTTP headers / OmniRoute monitoring responses; unavailable fields are not fabricated.\n")
                    s.runtime.forEach { r ->
                        append("\n${r.taskType} · ${r.modelId}: provider=${r.providerId ?: "غير متاح"}, effectiveModel=${r.effectiveModel ?: "غير متاح"}, first-token=${r.firstTokenLatencyMs}ms, max-gap=${r.maxInterTokenGapMs}ms, fallbackAttempts=${r.fallbackAttempts}, cost=${r.responseCostUsd?.toString() ?: "غير متاح"}")
                    }
                    append("\n")
                }
                if(s.benchmarkLastRunMs > 0) append("Last automatic translation benchmark: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(s.benchmarkLastRunMs))}\n")
                s.monitoring?.let {
                    append("\nOmniRoute monitoring endpoint: connected\n")
                    append("/api/monitoring/health: ").append(it.healthJson?.take(700) ?: "unavailable").append("\n")
                    append("/api/rate-limits: ").append(it.rateLimitsJson?.take(700) ?: "unavailable").append("\n")
                    append("/api/usage/budget: ").append(it.budgetJson?.take(500) ?: "unavailable").append("\n")
                    append("/api/usage/request-logs: ").append(it.telemetryJson?.take(500) ?: "unavailable").append("\n")
                    append("/api/models/availability: ").append(it.availabilityJson?.take(500) ?: "unavailable").append("\n")
                    append("/api/resilience: ").append(it.resilienceJson?.take(500) ?: "unavailable").append("\n")
                }
            }
            output.text=text
        } catch(e:Exception){ output.text="تعذر تحميل القياسات: ${e.message.orEmpty()}" } } }
        run.setOnClickListener { lifecycleScope.launch { run.isEnabled=false; output.text="جارٍ تنفيذ benchmark حقيقي على النماذج..."; try {
            val settings=SettingsRepository(this@ProviderDashboardActivity).load()
            val results=AiOrchestrator(this@ProviderDashboardActivity).benchmarkTranslation(settings.omniRouteTranslationModels.split(','))
            output.text=results.joinToString("\n\n"){ "${it.modelId}\nSuccess=${it.success} | Latency=${it.latencyMs}ms | Quality=${"%.1f".format(it.qualityScore*100)}%${it.error?.let{e->"\\nError=$e"}.orEmpty()}" }
        } catch(e:Exception){ output.text="Benchmark failed: ${e.message}" } finally { run.isEnabled=true; refresh() } } }
        refresh()
    }
}
