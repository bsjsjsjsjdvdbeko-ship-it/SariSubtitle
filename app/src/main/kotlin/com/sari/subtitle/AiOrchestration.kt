package com.sari.subtitle

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Sari's real multi-provider decision engine.
 *
 * Important design rule: Sari never invents provider accuracy or quota. It learns
 * from observed requests, response metadata and outcome/quality signals. When a
 * provider has no history, the score is neutral and exploration is allowed.
 */
class AiOrchestrator(private val context: Context) {
    private val db by lazy { AppDatabase.get(context) }
    private val dao by lazy { db.providerMetricsDao() }
    private val runtimeDao by lazy { db.providerRuntimeDao() }
    private val settingsRepo by lazy { SettingsRepository(context) }
    private val circuitBreaker by lazy { CircuitBreakerController(dao) }

    suspend fun transcribe(
        audioFile: File,
        models: List<String>,
        language: String,
        prompt: String,
        durationMs: Long,
        quality: TranslationQuality
    ): OrchestratedTranscript = withContext(Dispatchers.IO) {
        val settings = settingsRepo.load()
        val candidates = models.cleanOrDefault("deepgram/nova-3", "assemblyai/best")
        val features = AudioFeatureAnalyzer.analyze(audioFile)
        val decision = decide(candidates, TaskType.STT, quality, features, null)
        val benchmarkPrefs = context.getSharedPreferences("sari_orchestration", Context.MODE_PRIVATE)
        val benchmarkDue = System.currentTimeMillis() - benchmarkPrefs.getLong("stt_benchmark_at", 0L) >= settings.benchmarkIntervalMs
        val compare = settings.qualityRetryEnabled && settings.compareCandidatesEnabled && (quality != TranslationQuality.FAST || benchmarkDue)
        val results = mutableListOf<ScoredTranscript>()
        if (compare && settings.hedgedFailoverEnabled && decision.ordered.size >= 2) {
            val hedged = hedgedTranscribe(audioFile, decision, language, prompt, durationMs, features, quality)
            if (hedged.isNotEmpty()) {
                benchmarkPrefs.edit().putLong("stt_benchmark_at", System.currentTimeMillis()).apply()
                val best = hedged.maxByOrNull { resultFitness(it.qualityScore, it.latencyMs, quality, it.agreement) }!!
                return@withContext OrchestratedTranscript(best.result, best.modelId, best.latencyMs,
                    best.modelId != decision.ordered.firstOrNull(), best.qualityScore, features, decision,
                    hedged.map { it.modelId }, best.meta, best.actualWps, best.agreement)
            }
        }
        var last: Throwable? = null

        for (model in decision.ordered) {
            val key = key(model, TaskType.STT)
            if (settings.circuitBreakerEnabled && !circuitBreaker.allow(key)) continue
            val started = System.nanoTime()
            try {
                val client = client(settings)
                val result = client.transcribe(audioFile, listOf(model), language, prompt)
                val elapsed = ms(started)
                val meta = client.lastResponseMeta
                val actualWps = wordsPerSecond(result.text, durationMs)
                val enriched = features.withSpeechTempo(
                    if (actualWps > 0.0) actualWps else features.speechTempoProxy
                )
                val validation = TranscriptQualityEvaluator.evaluate(result.text, durationMs, enriched)
                val agreement = results.firstOrNull()?.result?.text?.let { transcriptAgreement(it, result.text) } ?: 1.0
                val combined = combineQuality(validation.score, agreement, results.isNotEmpty())
                persistRuntime(model, TaskType.STT, meta)
                record(
                    key = key,
                    latency = elapsed,
                    success = validation.accepted,
                    error = if (validation.accepted) null else "invalid:${validation.reason}",
                    quality = combined,
                    features = enriched,
                    actualWps = actualWps,
                    agreement = agreement,
                    meta = meta
                )
                if (validation.accepted) {
                    results += ScoredTranscript(result, model, elapsed, combined, meta, actualWps, agreement)
                    // First result is enough in FAST mode. Other modes automatically
                    // test a second healthy candidate when there is one.
                    if (!compare || results.size >= min(2, decision.ordered.size)) break
                }
            } catch (t: Throwable) {
                val elapsed = ms(started)
                last = t
                val meta = clientMetaFromError(t)
                record(key, elapsed, false, t.message ?: t.javaClass.simpleName, 0.0, features, 0.0, 0.0, meta)
                persistRuntime(model, TaskType.STT, meta)
                if (settings.circuitBreakerEnabled && isRateLimited(t)) dao.openCircuit(key, cooldownUntil(meta))
            }
        }

        if (results.isEmpty()) throw IllegalStateException("تعذر تنفيذ STT عبر جميع المرشحين الصحيين في OmniRoute.", last)

        // When actual speech speed is extreme, prefer the candidate with the best
        // observed result for that speed/noise bucket. This is real adaptive routing,
        // not a hard-coded claim that one vendor is always better.
        val best = results.maxByOrNull { resultFitness(it.qualityScore, it.latencyMs, quality, it.agreement) }!!
        OrchestratedTranscript(
            result = best.result,
            modelId = best.modelId,
            latencyMs = best.latencyMs,
            usedFallback = best.modelId != decision.ordered.firstOrNull(),
            qualityScore = best.qualityScore,
            audioFeatures = features,
            decision = decision,
            comparedCandidates = results.map { it.modelId },
            responseMeta = best.meta,
            actualWordsPerSecond = best.actualWps,
            comparisonAgreement = best.agreement
        )
    }

    suspend fun translate(
        text: String,
        source: String,
        target: String,
        models: List<String>,
        contextPrompt: TranslationContext,
        quality: TranslationQuality,
        speechProfile: SpeechProfile? = null
    ): OrchestratedTranslation = withContext(Dispatchers.IO) {
        val settings = settingsRepo.load()
        val candidates = models.cleanOrDefault("openai/gpt-oss-20b", "openai/gpt-oss-120b")
        // Opportunistic background maintenance: benchmark/monitoring never blocks the subtitle path.
        val maintenance = context.getSharedPreferences("sari_orchestration", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastMaintenance = maintenance.getLong("maintenance_at", 0L)
        if (settings.providerBenchmarkEnabled && now - lastMaintenance >= settings.benchmarkIntervalMs) {
            maintenance.edit().putLong("maintenance_at", now).apply()
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try { runAutomaticTranslationBenchmark(candidates, force = false) } catch (_: Throwable) {}
                try { refreshOmniRouteMonitoring() } catch (_: Throwable) {}
            }
        }
        val decision = decide(candidates, TaskType.TRANSLATION, quality, speechProfile?.audioFeatures, speechProfile)
        val compare = settings.qualityRetryEnabled && settings.compareCandidatesEnabled && quality != TranslationQuality.FAST
        val results = mutableListOf<ScoredTranslation>()
        if (compare && settings.hedgedFailoverEnabled && decision.ordered.size >= 2) {
            val hedged = hedgedTranslate(text, source, target, decision, contextPrompt, quality, speechProfile)
            if (hedged.isNotEmpty()) {
                val best = hedged.maxByOrNull { resultFitness(it.qualityScore, it.latencyMs, quality, it.agreement) }!!
                return@withContext OrchestratedTranslation(best.text, best.modelId, best.latencyMs,
                    best.modelId != decision.ordered.firstOrNull(), best.qualityScore, decision,
                    hedged.map { it.modelId }, best.meta, best.agreement)
            }
        }
        var last: Throwable? = null

        for (model in decision.ordered) {
            val key = key(model, TaskType.TRANSLATION)
            if (settings.circuitBreakerEnabled && !circuitBreaker.allow(key)) continue
            val started = System.nanoTime()
            try {
                val client = client(settings)
                val result = if (settings.streamingOrchestrationEnabled) {
                    client.translateStreaming(text, source, target, model, contextPrompt, quality)
                } else {
                    client.translate(text, source, target, model, contextPrompt, quality)
                }
                val elapsed = ms(started)
                val meta = client.lastResponseMeta
                persistRuntime(model, TaskType.TRANSLATION, meta)
                val validation = TranslationQualityEvaluator.evaluate(result, text, target)
                val agreement = results.firstOrNull()?.text?.let { translationAgreement(it, result) } ?: 1.0
                val combined = combineQuality(validation.score, agreement, results.isNotEmpty())
                val features = speechProfile?.audioFeatures
                record(key, elapsed, validation.accepted, if (validation.accepted) null else "invalid:${validation.reason}", combined,
                    features, speechProfile?.wordsPerSecond ?: 0.0, agreement, meta)
                if (validation.accepted) {
                    results += ScoredTranslation(result, model, elapsed, combined, meta, agreement)
                    if (!compare || results.size >= min(2, decision.ordered.size)) break
                }
            } catch (t: Throwable) {
                val elapsed = ms(started)
                last = t
                val meta = clientMetaFromError(t)
                record(key, elapsed, false, t.message ?: t.javaClass.simpleName, 0.0,
                    speechProfile?.audioFeatures, speechProfile?.wordsPerSecond ?: 0.0, 0.0, meta)
                persistRuntime(model, TaskType.TRANSLATION, meta)
                if (settings.circuitBreakerEnabled && isRateLimited(t)) dao.openCircuit(key, cooldownUntil(meta))
            }
        }

        if (results.isEmpty()) throw IllegalStateException("تعذر تنفيذ الترجمة عبر جميع المرشحين الصحيين في OmniRoute.", last)
        val best = results.maxByOrNull { resultFitness(it.qualityScore, it.latencyMs, quality, it.agreement) }!!
        OrchestratedTranslation(
            text = best.text,
            modelId = best.modelId,
            latencyMs = best.latencyMs,
            usedFallback = best.modelId != decision.ordered.firstOrNull(),
            qualityScore = best.qualityScore,
            decision = decision,
            comparedCandidates = results.map { it.modelId },
            responseMeta = best.meta,
            comparisonAgreement = best.agreement
        )
    }

    /**
     * Automatic online benchmark. Translation candidates are benchmarked with a
     * fixed, tiny sentence at most once per configured interval. This does not
     * pretend to measure linguistic truth; it measures reachability, latency and
     * deterministic output validity and stores the result in the same history.
     */
    suspend fun runAutomaticTranslationBenchmark(models: List<String>, force: Boolean = false): List<ProviderBenchmark> = withContext(Dispatchers.IO) {
        val settings = settingsRepo.load()
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences("sari_orchestration", Context.MODE_PRIVATE)
        val last = prefs.getLong("translation_benchmark_at", 0L)
        if (!force && now - last < settings.benchmarkIntervalMs) return@withContext emptyList()
        val clean = models.cleanOrDefault("openai/gpt-oss-20b", "openai/gpt-oss-120b")
        val output = mutableListOf<ProviderBenchmark>()
        for (model in clean) {
            val started = System.nanoTime()
            try {
                val client = client(settings)
                val text = client.translate("Hello, how are you?", "en", "ar", model, TranslationContext(), TranslationQuality.FAST)
                val elapsed = ms(started)
                val v = TranslationQualityEvaluator.evaluate(text, "Hello, how are you?", "ar")
                record(key(model, TaskType.BENCHMARK), elapsed, v.accepted, if (v.accepted) null else v.reason,
                    v.score, null, 0.0, 1.0, client.lastResponseMeta)
                output += ProviderBenchmark(model, true, elapsed, v.score, null)
            } catch (t: Throwable) {
                val elapsed = ms(started)
                val meta = clientMetaFromError(t)
                record(key(model, TaskType.BENCHMARK), elapsed, false, t.message ?: t.javaClass.simpleName,
                    0.0, null, 0.0, 0.0, meta)
                persistRuntime(model, TaskType.BENCHMARK, meta)
                output += ProviderBenchmark(model, false, elapsed, 0.0, t.message)
            }
        }
        prefs.edit().putLong("translation_benchmark_at", now).apply()
        output
    }

    suspend fun benchmarkTranslation(models: List<String>): List<ProviderBenchmark> =
        runAutomaticTranslationBenchmark(models, force = true)

    suspend fun refreshOmniRouteMonitoring(): OmniMonitoringSnapshot? = withContext(Dispatchers.IO) {
        val settings = settingsRepo.load()
        if (!settings.quotaMonitoringEnabled || settings.omniRouteBaseUrl.isBlank()) return@withContext null
        try {
            val snapshot = client(settings).fetchRuntimeMonitoring()
            val observed = parseMonitoringFacts(snapshot)
            if (observed != null) {
                runtimeDao.upsert(ProviderRuntimeEntity(
                    runtimeKey = "ACCOUNT|omniroute", modelId = "omniroute", taskType = "ACCOUNT",
                    rateLimitRemaining = observed.rateRemaining, rateLimitResetMs = observed.rateResetMs,
                    quotaRemaining = observed.quotaRemaining, quotaLimit = observed.quotaLimit,
                    lastStatusCode = null, lastError = null, updatedAt = System.currentTimeMillis()
                ))
            }
            snapshot
        } catch (_: Throwable) { null }
    }

    suspend fun dashboardSnapshot(): DashboardSnapshot = withContext(Dispatchers.IO) {
        val settings = settingsRepo.load()
        val scores = healthSnapshot()
        val runtime = runtimeDao.all()
        val metrics = dao.listRecent(1000)
        val monitoring = if (settings.quotaMonitoringEnabled) try { client(settings).fetchRuntimeMonitoring() } catch (_: Throwable) { null } else null
        DashboardSnapshot(
            scores = scores,
            runtime = runtime,
            sampleCount = metrics.size,
            fallbackCount = metrics.count { it.error?.startsWith("fallback:") == true },
            healthyCount = scores.count { !it.unhealthy },
            monitoring = monitoring,
            benchmarkLastRunMs = context.getSharedPreferences("sari_orchestration", Context.MODE_PRIVATE).getLong("translation_benchmark_at", 0L)
        )
    }

    private fun parseMonitoringFacts(snapshot: OmniMonitoringSnapshot): MonitoringFacts? {
        fun findLong(jsonText: String?, names: Set<String>): Long? {
            if (jsonText.isNullOrBlank()) return null
            fun walk(value: Any?): Long? {
                when (value) {
                    is JSONObject -> {
                        val keys = value.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val child = value.opt(key)
                            if (key.lowercase() in names) {
                                val n = child.toString().replace(",", "").toLongOrNull()
                                if (n != null) return n
                            }
                            walk(child)?.let { return it }
                        }
                    }
                    is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))?.let { return it }
                }
                return null
            }
            return try { walk(JSONObject(jsonText)) } catch (_: Throwable) { null }
        }
        val rate = findLong(snapshot.rateLimitsJson, setOf("remaining", "requestsremaining", "ratelimitremaining"))
        val reset = findLong(snapshot.rateLimitsJson, setOf("reset", "resetat", "resetms", "ratelimitreset"))
        val quota = findLong(snapshot.budgetJson, setOf("remaining", "remainingusd", "quotaremaining", "tokensremaining"))
        val limit = findLong(snapshot.budgetJson, setOf("limit", "monthlylimitusd", "dailylimitusd", "tokenlimit", "quotalimit"))
        if (rate == null && quota == null && limit == null) return null
        return MonitoringFacts(rate, reset, quota, limit)
    }

    suspend fun healthSnapshot(): List<ProviderScore> = withContext(Dispatchers.IO) {
        val out = mutableListOf<ProviderScore>()
        for ((k, rows) in dao.listRecent(1000).groupBy { it.modelId to it.taskType }) {
            out += score(k.first, TaskType.valueOf(k.second), rows, runtimeDao.get(key(k.first, TaskType.valueOf(k.second))))
        }
        out.sortedByDescending { it.score }
    }

    private suspend fun hedgedTranscribe(
        audioFile: File, decision: RoutingDecision, language: String, prompt: String,
        durationMs: Long, features: AudioFeatures, quality: TranslationQuality
    ): List<ScoredTranscript> = coroutineScope {
        val models = decision.ordered.take(2).filter { circuitBreaker.allow(key(it, TaskType.STT)) }
        val jobs = models.mapIndexed { index, model ->
            async(Dispatchers.IO) {
                if (index > 0) delay(settingsRepo.load().hedgeDelayMs)
                val started = System.nanoTime()
                try {
                    val client = client(settingsRepo.load())
                    val result = client.transcribe(audioFile, listOf(model), language, prompt)
                    val elapsed = ms(started)
                    val meta = client.lastResponseMeta
                    val wps = wordsPerSecond(result.text, durationMs)
                    val enriched = features.withSpeechTempo(if (wps > 0.0) wps else features.speechTempoProxy)
                    val validation = TranscriptQualityEvaluator.evaluate(result.text, durationMs, enriched)
                    val q = validation.score
                    record(key(model, TaskType.STT), elapsed, validation.accepted,
                        if (validation.accepted) null else "invalid:${validation.reason}", q, enriched, wps, 1.0, meta)
                    persistRuntime(model, TaskType.STT, meta)
                    if (!validation.accepted) null else ScoredTranscript(result, model, elapsed, q, meta, wps, 1.0)
                } catch (t: Throwable) {
                    val elapsed = ms(started); val meta = clientMetaFromError(t)
                    record(key(model, TaskType.STT), elapsed, false, t.message ?: t.javaClass.simpleName, 0.0, features, 0.0, 0.0, meta)
                    persistRuntime(model, TaskType.STT, meta)
                    if (isRateLimited(t)) dao.openCircuit(key(model, TaskType.STT), cooldownUntil(meta))
                    null
                }
            }
        }
        val successful = jobs.awaitAll().filterNotNull()
        if (successful.size < 2) return@coroutineScope successful
        val reference = successful.maxByOrNull { it.qualityScore }?.result?.text.orEmpty()
        successful.map { item ->
            item.copy(agreement = transcriptAgreement(reference, item.result.text))
        }
    }

    private suspend fun hedgedTranslate(
        text: String, source: String, target: String, decision: RoutingDecision,
        contextPrompt: TranslationContext, quality: TranslationQuality, speechProfile: SpeechProfile?
    ): List<ScoredTranslation> = coroutineScope {
        val models = decision.ordered.take(2).filter { circuitBreaker.allow(key(it, TaskType.TRANSLATION)) }
        val jobs = models.mapIndexed { index, model ->
            async(Dispatchers.IO) {
                if (index > 0) delay(settingsRepo.load().hedgeDelayMs)
                val started = System.nanoTime()
                try {
                    val settings = settingsRepo.load()
                    val client = client(settings)
                    val result = if (settings.streamingOrchestrationEnabled) {
                        client.translateStreaming(text, source, target, model, contextPrompt, quality)
                    } else client.translate(text, source, target, model, contextPrompt, quality)
                    val elapsed = ms(started)
                    val meta = client.lastResponseMeta
                    val validation = TranslationQualityEvaluator.evaluate(result, text, target)
                    val q = validation.score
                    record(key(model, TaskType.TRANSLATION), elapsed, validation.accepted,
                        if (validation.accepted) null else "invalid:${validation.reason}", q,
                        speechProfile?.audioFeatures, speechProfile?.wordsPerSecond ?: 0.0, 1.0, meta)
                    persistRuntime(model, TaskType.TRANSLATION, meta)
                    if (!validation.accepted) null else ScoredTranslation(result, model, elapsed, q, meta, 1.0)
                } catch (t: Throwable) {
                    val elapsed = ms(started); val meta = clientMetaFromError(t)
                    record(key(model, TaskType.TRANSLATION), elapsed, false, t.message ?: t.javaClass.simpleName, 0.0,
                        speechProfile?.audioFeatures, speechProfile?.wordsPerSecond ?: 0.0, 0.0, meta)
                    persistRuntime(model, TaskType.TRANSLATION, meta)
                    if (isRateLimited(t)) dao.openCircuit(key(model, TaskType.TRANSLATION), cooldownUntil(meta))
                    null
                }
            }
        }
        val successful = jobs.awaitAll().filterNotNull()
        if (successful.size < 2) return@coroutineScope successful
        val reference = successful.maxByOrNull { it.qualityScore }?.text.orEmpty()
        successful.map { item ->
            item.copy(agreement = translationAgreement(reference, item.text))
        }
    }

    private suspend fun decide(
        candidates: List<String>, task: TaskType, quality: TranslationQuality,
        features: AudioFeatures?, speech: SpeechProfile?
    ): RoutingDecision {
        val scored = mutableListOf<CandidateScore>()
        for (model in candidates) {
            val rows = dao.recent(model, task.name, 50)
            val runtime = runtimeDao.get(key(model, task))
                ?: runtimeDao.get("ACCOUNT|omniroute")
            val base = score(model, task, rows, runtime)
            val environment = environmentKey(task, features, speech)
            val conditionalRows = dao.recentByEnvironment(model, task.name, environment, 50)
            val conditional = conditionalScore(conditionalRows)
            val adaptive = adaptiveScore(conditional, base, rows.isNotEmpty())
            val quotaPenalty = quotaPenalty(runtime)
            val breakerPenalty = if (base.unhealthy) 100.0 else 0.0
            val exploration = if (rows.isEmpty()) 4.0 else 0.0
            scored += CandidateScore(model, (base.score + adaptive - quotaPenalty - breakerPenalty + exploration).coerceIn(0.0, 100.0),
                base, adaptive, quotaPenalty, environment, conditional)
        }
        scored.sortByDescending { it.total }
        return RoutingDecision(
            taskType = task,
            ordered = scored.map { it.modelId },
            candidates = scored,
            reason = buildReason(task, features, speech, scored)
        )
    }

    private suspend fun score(model: String, task: TaskType, rows: List<ProviderMetricEntity>, runtime: ProviderRuntimeEntity?): ProviderScore {
        if (rows.isEmpty()) return ProviderScore(model, task, 50.0, 0.0, 1.0, 0, 0.75,
            runtime?.rateLimitRemaining, runtime?.quotaRemaining, false)
        val total = rows.size
        val weighted = rows.mapIndexed { index, r -> r to (1.0 / (1.0 + index * 0.08)) }
        val weightSum = weighted.sumOf { it.second }
        val successRate = weighted.sumOf { (if (it.first.success) 1.0 else 0.0) * it.second } / weightSum
        val latency = weighted.sumOf { it.first.latencyMs.toDouble() * it.second } / weightSum
        val latencyScore = (100.0 / (1.0 + ln(1.0 + latency / 250.0))).coerceIn(0.0, 100.0)
        val qualityRows = weighted.filter { it.first.qualityScore > 0.0 }
        val qualityWeight = qualityRows.sumOf { it.second }.coerceAtLeast(1.0)
        val qualityProxy = (qualityRows.sumOf { it.first.qualityScore * it.second } / qualityWeight).coerceIn(0.0, 1.0)
        val streamRows = rows.filter { it.firstTokenLatencyMs > 0L || it.maxInterTokenGapMs > 0L }
        val streamPenalty = if (streamRows.isEmpty()) 0.0 else rows.count { it.maxInterTokenGapMs > 3000L }.toDouble() / total * 12.0
        val reliability = successRate * 55.0
        val quality = qualityProxy * 25.0
        val speed = latencyScore * 20.0
        val health = (reliability + quality + speed - streamPenalty).coerceIn(0.0, 100.0)
        val unhealthy = circuitStateIsOpenOrLimited(key(model, task), runtime)
        return ProviderScore(model, task, health, latency, successRate, total, qualityProxy,
            runtime?.rateLimitRemaining, runtime?.quotaRemaining, unhealthy)
    }

    private fun conditionalScore(rows: List<ProviderMetricEntity>): ConditionalProviderScore {
        if (rows.isEmpty()) return ConditionalProviderScore(0, 0.5, 0.5, 0.0)
        val success = rows.count { it.success }.toDouble() / rows.size
        val quality = rows.map { it.qualityScore }.average().coerceIn(0.0, 1.0)
        val confidence = min(1.0, rows.size / 10.0)
        return ConditionalProviderScore(rows.size, success, quality, confidence)
    }

    private fun adaptiveScore(conditional: ConditionalProviderScore, base: ProviderScore, hasHistory: Boolean): Double {
        if (conditional.samples == 0) return 0.0
        val empirical = (conditional.successRate * 55.0 + conditional.qualityScore * 45.0)
        val baseQuality = (base.successRate * 55.0 + base.qualityProxy * 45.0)
        return ((empirical - baseQuality) * conditional.confidence).coerceIn(-20.0, 20.0) * if (hasHistory) 1.0 else 0.5
    }

    private fun quotaPenalty(runtime: ProviderRuntimeEntity?): Double {
        val rem = runtime?.quotaRemaining ?: return when {
            runtime?.rateLimitRemaining != null && runtime.rateLimitRemaining <= 0 -> 100.0
            else -> 0.0
        }
        val limit = runtime.quotaLimit
        if (rem <= 0) return 100.0
        if (limit == null || limit <= 0) return 0.0
        val ratio = rem.toDouble() / limit
        return when {
            ratio < 0.05 -> 18.0
            ratio < 0.15 -> 8.0
            ratio < 0.30 -> 3.0
            else -> 0.0
        }
    }

    private fun environmentKey(task: TaskType, f: AudioFeatures?, speech: SpeechProfile?): String {
        if (task != TaskType.STT && task != TaskType.TRANSLATION) return "generic"
        val noise = when {
            (f?.noiseLevel ?: speech?.noiseLevel ?: 0.0) < 0.25 -> "clean"
            (f?.noiseLevel ?: speech?.noiseLevel ?: 0.0) < 0.55 -> "medium_noise"
            else -> "high_noise"
        }
        val speed = speech?.wordsPerSecond ?: f?.speechSpeed ?: 0.0
        val speedBucket = when {
            speed <= 0.0 -> "unknown_speed"
            speed < 1.8 -> "slow"
            speed < 3.2 -> "normal"
            speed < 4.5 -> "fast"
            else -> "very_fast"
        }
        return "$noise|$speedBucket"
    }

    private fun buildReason(task: TaskType, f: AudioFeatures?, speech: SpeechProfile?, scores: List<CandidateScore>): String = buildString {
        append(task.name).append(": learned reliability + latency + observed quality + rate/quota + environment history")
        if (f != null) append("; noise=").append("%.2f".format(f.noiseLevel)).append("; acousticTempo=").append("%.2f".format(f.speechTempoProxy))
        if (speech != null) append("; wordsPerSecond=").append("%.2f".format(speech.wordsPerSecond))
        if (scores.isNotEmpty()) append("; winner=").append(scores.first().modelId).append("; bucket=").append(scores.first().environmentKey)
    }

    private suspend fun record(
        key: String, latency: Long, success: Boolean, error: String?, quality: Double,
        features: AudioFeatures?, actualWps: Double, agreement: Double, meta: OmniResponseMeta?
    ) {
        dao.insert(
            ProviderMetricEntity(
                modelId = key.substringAfter('|'), taskType = key.substringBefore('|'), latencyMs = latency,
                success = success, error = error?.take(500), qualityScore = quality,
                environmentKey = environmentKey(TaskType.valueOf(key.substringBefore('|')), features,
                    if (actualWps > 0.0) SpeechProfile(actualWps, features?.noiseLevel ?: 0.0, features) else null),
                noiseLevel = features?.noiseLevel ?: 0.0,
                speechSpeed = actualWps,
                agreementScore = agreement,
                firstTokenLatencyMs = meta?.firstTokenLatencyMs ?: 0L,
                maxInterTokenGapMs = meta?.maxInterTokenGapMs ?: 0L,
                createdAt = System.currentTimeMillis()
            )
        )
        if (success) circuitBreaker.close(key) else {
            circuitBreaker.releaseProbe(key)
            if (dao.recentFailures(key, 3) >= 3) circuitBreaker.open(key, System.currentTimeMillis() + 30_000L)
        }
        dao.trim(1000)
    }

    private suspend fun persistRuntime(model: String, task: TaskType, meta: OmniResponseMeta?) {
        if (meta == null) return
        runtimeDao.upsert(ProviderRuntimeEntity(
            runtimeKey = key(model, task), modelId = model, taskType = task.name,
            rateLimitRemaining = meta.rateLimitRemaining, rateLimitResetMs = meta.rateLimitResetMs,
            quotaRemaining = meta.quotaRemaining, quotaLimit = meta.quotaLimit,
            lastStatusCode = meta.statusCode, lastError = meta.error,
            providerId = meta.providerId, effectiveModel = meta.effectiveModel,
            responseCostUsd = meta.responseCostUsd, fallbackAttempts = meta.fallbackAttempts,
            requestId = meta.requestId, decisionTrace = meta.decisionTrace, streamCompleted = meta.streamCompleted,
            firstTokenLatencyMs = meta.firstTokenLatencyMs, maxInterTokenGapMs = meta.maxInterTokenGapMs,
            updatedAt = System.currentTimeMillis()
        ))
    }

    private fun client(s: AppSettings) = OmniRouteClient(s.omniRouteBaseUrl, s.omniRouteKey)
    private fun key(model: String, task: TaskType) = "${task.name}|$model"
    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000L
    private fun wordsPerSecond(text: String, durationMs: Long): Double = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }.toDouble() /
        (durationMs.coerceAtLeast(1L) / 1000.0)
    private fun resultFitness(qualityScore: Double, latencyMs: Long, quality: TranslationQuality, agreement: Double): Double {
        val latencyWeight = if (quality == TranslationQuality.FAST) 0.20 else 0.07
        return qualityScore * (1.0 - latencyWeight) + agreement * 0.08 - min(0.20, latencyMs / 30_000.0 * latencyWeight)
    }
    private fun combineQuality(base: Double, agreement: Double, compared: Boolean): Double =
        if (!compared) base else (base * 0.72 + agreement * 0.28).coerceIn(0.0, 1.0)
    private fun transcriptAgreement(a: String, b: String): Double = similarity(normalize(a), normalize(b))
    private fun translationAgreement(a: String, b: String): Double = similarity(normalize(a), normalize(b))
    private fun normalize(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun similarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        val aa = a.split(Regex("\\s+")).toSet(); val bb = b.split(Regex("\\s+")).toSet()
        val union = (aa + bb).size.coerceAtLeast(1)
        return (aa.intersect(bb).size.toDouble() / union).coerceIn(0.0, 1.0)
    }
    private fun cooldownUntil(meta: OmniResponseMeta): Long = meta.rateLimitResetMs?.takeIf { it > System.currentTimeMillis() } ?: (System.currentTimeMillis() + 60_000L)
    private fun isRateLimited(t: Throwable) = (t.message ?: "").contains("429") || (t.message ?: "").contains("rate limit", true)
    private fun clientMetaFromError(t: Throwable) = OmniResponseMeta(null, null, null, null, extractStatus(t), t.message, 0L, 0L)
    private fun extractStatus(t: Throwable): Int? = Regex("(?:OmniRoute \\()?([45]\\d\\d)").find(t.message ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()

    private suspend fun circuitStateIsOpenOrLimited(key: String, runtime: ProviderRuntimeEntity?): Boolean {
        return circuitBreaker.state(key) == CircuitState.OPEN || (runtime?.rateLimitRemaining != null && runtime.rateLimitRemaining <= 0)
    }

    companion object {
        private fun List<String>.cleanOrDefault(vararg defaults: String) = map(String::trim).filter(String::isNotBlank).distinct().ifEmpty { defaults.toList() }
    }
}

enum class TaskType { STT, TRANSLATION, BENCHMARK }

data class CandidateScore(
    val modelId: String, val total: Double, val provider: ProviderScore,
    val adaptiveBonus: Double, val quotaPenalty: Double, val environmentKey: String,
    val conditional: ConditionalProviderScore
)
data class ConditionalProviderScore(val samples: Int, val successRate: Double, val qualityScore: Double, val confidence: Double)
data class MonitoringFacts(val rateRemaining: Long?, val rateResetMs: Long?, val quotaRemaining: Long?, val quotaLimit: Long?)
data class RoutingDecision(val taskType: TaskType, val ordered: List<String>, val candidates: List<CandidateScore>, val reason: String)
data class ProviderScore(
    val modelId: String, val taskType: TaskType, val score: Double, val avgLatencyMs: Double,
    val successRate: Double, val samples: Int, val qualityProxy: Double,
    val rateLimitRemaining: Long?, val quotaRemaining: Long?, val unhealthy: Boolean
)
data class OrchestratedTranscript(
    val result: TranscriptResult, val modelId: String, val latencyMs: Long, val usedFallback: Boolean,
    val qualityScore: Double, val audioFeatures: AudioFeatures, val decision: RoutingDecision,
    val comparedCandidates: List<String>, val responseMeta: OmniResponseMeta?,
    val actualWordsPerSecond: Double = 0.0, val comparisonAgreement: Double = 1.0
)
data class OrchestratedTranslation(
    val text: String, val modelId: String, val latencyMs: Long, val usedFallback: Boolean,
    val qualityScore: Double, val decision: RoutingDecision, val comparedCandidates: List<String>,
    val responseMeta: OmniResponseMeta?, val comparisonAgreement: Double = 1.0
)
data class SpeechProfile(val wordsPerSecond: Double, val noiseLevel: Double, val audioFeatures: AudioFeatures? = null)
data class ProviderBenchmark(val modelId: String, val success: Boolean, val latencyMs: Long, val qualityScore: Double, val error: String?)
data class DashboardSnapshot(
    val scores: List<ProviderScore>, val runtime: List<ProviderRuntimeEntity>, val sampleCount: Int,
    val fallbackCount: Int, val healthyCount: Int, val monitoring: OmniMonitoringSnapshot? = null,
    val benchmarkLastRunMs: Long = 0L
)
data class AudioFeatures(
    val rms: Double, val zeroCrossingRate: Double, val speechDensity: Double,
    val complexity: Double, val noiseLevel: Double, val speechTempoProxy: Double,
    val sampleRate: Int = 16_000
) {
    constructor(rms: Double, zeroCrossingRate: Double, speechDensity: Double, complexity: Double) :
        this(rms, zeroCrossingRate, speechDensity, complexity, 0.0, 0.0, 16_000)
    val speechSpeed: Double get() = speechTempoProxy
    fun withSpeechTempo(value: Double) = copy(speechTempoProxy = value)
}

object AudioFeatureAnalyzer {
    fun analyze(file: File): AudioFeatures {
        val bytes = try { file.readBytes() } catch (_: Throwable) { return AudioFeatures(0.0, 0.0, 0.0, 0.0, 0.0, 0.0) }
        if (bytes.size < 44) return AudioFeatures(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        return try {
        val dataOffset = findDataChunk(bytes)
        val sampleRate = parseSampleRate(bytes).coerceAtLeast(1)
        val frameSamples = max(1, sampleRate / 50) // 20 ms frames
        var sumSq = 0.0; var crossings = 0; var samples = 0; var active = 0; var prev = 0
        var frameActive = 0; var frames = 0; var activeTransitions = 0; var wasActive = false
        var i = dataOffset
        while (i + 1 < bytes.size && samples < sampleRate * 30) {
            val raw = (bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)
            val signed = if (raw and 0x8000 != 0) raw - 65536 else raw
            val norm = signed / 32768.0
            sumSq += norm * norm
            if (samples > 0 && ((signed >= 0) != (prev >= 0))) crossings++
            if (abs(signed) > 700) { active++; frameActive++ }
            prev = signed; samples++; i += 2
            if (samples % frameSamples == 0) {
                val nowActive = frameActive.toDouble() / frameSamples > 0.12
                if (nowActive != wasActive) activeTransitions++
                if (nowActive) frames++
                wasActive = nowActive; frameActive = 0
            }
        }
        val seconds = samples.toDouble() / sampleRate.coerceAtLeast(1)
        val rms = kotlin.math.sqrt(sumSq / max(1, samples))
        val zcr = crossings.toDouble() / max(1, samples - 1)
        val density = active.toDouble() / max(1, samples)
        val transitionRate = activeTransitions.toDouble() / seconds.coerceAtLeast(0.02)
        val speechTempo = (transitionRate / 2.0).coerceIn(0.0, 8.0)
        val noise = (zcr * 0.65 + (1.0 - density) * 0.20 + (1.0 - rms).coerceIn(0.0, 1.0) * 0.15).coerceIn(0.0, 1.0)
        val complexity = (0.45 * zcr + 0.35 * density + 0.20 * (speechTempo / 8.0)).coerceIn(0.0, 1.0)
        AudioFeatures(rms, zcr, density, complexity, noise, speechTempo, sampleRate)
        } catch (_: Throwable) { AudioFeatures(0.0, 0.0, 0.0, 0.0, 0.0, 0.0) }
    }

    private fun parseSampleRate(b: ByteArray): Int {
        if (b.size < 28) return 16_000
        return (b[24].toInt() and 0xff) or ((b[25].toInt() and 0xff) shl 8) or
            ((b[26].toInt() and 0xff) shl 16) or ((b[27].toInt() and 0xff) shl 24)
    }
    private fun findDataChunk(b: ByteArray): Int {
        for (i in 12 until b.size - 8) {
            if (b[i] == 'd'.code.toByte() && b[i + 1] == 'a'.code.toByte() && b[i + 2] == 't'.code.toByte() && b[i + 3] == 'a'.code.toByte()) return i + 8
        }
        return 44
    }
}

object TranscriptQualityEvaluator {
    fun evaluate(text: String, durationMs: Long, features: AudioFeatures): ValidationResult {
        val t = text.trim()
        if (t.isBlank()) return ValidationResult(false, 0.0, "empty")
        if (t.length < 2) return ValidationResult(false, 0.1, "too_short")
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        val repeated = words.size > 8 && words.zipWithNext().count { it.first.equals(it.second, true) } > words.size / 3
        if (repeated) return ValidationResult(false, 0.2, "repetition")
        val charsPerSecond = t.length.toDouble() / max(1.0, durationMs / 1000.0)
        val densityScore = (charsPerSecond / 18.0).coerceIn(0.0, 1.0)
        val noisePenalty = features.noiseLevel * 0.08
        val score = (0.55 + 0.45 * densityScore - noisePenalty).coerceIn(0.0, 1.0)
        return ValidationResult(score >= 0.45, score, "ok")
    }
}

object TranslationQualityEvaluator {
    fun evaluate(result: String, source: String, target: String): ValidationResult {
        val r = result.trim()
        if (r.isBlank()) return ValidationResult(false, 0.0, "empty")
        if (r.length < 2) return ValidationResult(false, 0.1, "too_short")
        if (r == source.trim()) return ValidationResult(false, 0.2, "unchanged")
        val sw = source.trim().split(Regex("\\s+")).size.coerceAtLeast(1)
        val rw = r.split(Regex("\\s+")).size
        val ratio = rw.toDouble() / sw
        if (ratio > 8 || ratio < 0.08) return ValidationResult(false, 0.2, "length_anomaly")
        val scriptBonus = if (target.equals("ar", true) && r.any { it in '\u0600'..'\u06ff' }) 0.15 else 0.0
        return ValidationResult(true, (0.70 + scriptBonus).coerceAtMost(1.0), "ok")
    }
}

data class ValidationResult(val accepted: Boolean, val score: Double, val reason: String)
data class ScoredTranscript(
    val result: TranscriptResult, val modelId: String, val latencyMs: Long,
    val qualityScore: Double, val meta: OmniResponseMeta?, val actualWps: Double, val agreement: Double
)
data class ScoredTranslation(
    val text: String, val modelId: String, val latencyMs: Long,
    val qualityScore: Double, val meta: OmniResponseMeta?, val agreement: Double
)
