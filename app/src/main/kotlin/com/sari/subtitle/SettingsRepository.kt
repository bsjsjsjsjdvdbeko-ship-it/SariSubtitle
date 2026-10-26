package com.sari.subtitle

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppSettings(
    val provider: Int = SettingsRepository.PROVIDER_GROQ,
    val speechKey: String = "",
    val translationKey: String = "",
    val sourceLanguage: String = "auto",
    val targetLanguage: String = "ar",
    val fontSize: Int = 30,
    val opacity: Int = 82,
    val subtitlePosition: String = "bottom",
    val myMemoryEmail: String = "",
    val speechLanguage: String = "auto",
    val sttProvider: String = "groq",
    val deepgramKey: String = "",
    val openAiKey: String = "",
    val contextAwareTranslation: Boolean = true,
    val translationMemoryEnabled: Boolean = true,
    val glossary: String = "",
    val contextPrompt: String = "",
    val translationQuality: Int = SettingsRepository.QUALITY_HUMAN,
    val omniRouteBaseUrl: String = "",
    val omniRouteKey: String = "",
    val omniRouteSttModels: String = "deepgram/nova-3,assemblyai/best",
    val omniRouteTranslationModel: String = "openai/gpt-oss-20b",
    val omniRouteTranslationModels: String = "openai/gpt-oss-20b,openai/gpt-oss-120b",
    val smartRoutingEnabled: Boolean = true,
    val qualityRetryEnabled: Boolean = true,
    val compareCandidatesEnabled: Boolean = true,
    val streamingOrchestrationEnabled: Boolean = true,
    val quotaMonitoringEnabled: Boolean = true,
    val providerBenchmarkEnabled: Boolean = false,
    val circuitBreakerEnabled: Boolean = true,
    val adaptiveRoutingEnabled: Boolean = false,
    val benchmarkIntervalMs: Long = 6L * 60L * 60L * 1000L,
    val hedgedFailoverEnabled: Boolean = false,
    val hedgeDelayMs: Long = 0L
)

class SettingsRepository(context: Context) {
    private val prefs = try {
        val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "sari_secure_settings", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) { null }

    suspend fun save(
        provider: Int,
        speechKey: String,
        translationKey: String,
        source: String,
        target: String,
        font: Int,
        opacity: Int,
        subtitlePosition: String = "bottom",
        email: String,
        speechLanguage: String,
        contextAwareTranslation: Boolean = true,
        translationMemoryEnabled: Boolean = true,
        glossary: String = "",
        contextPrompt: String = "",
        translationQuality: Int = QUALITY_HUMAN,
        omniRouteBaseUrl: String = "",
        omniRouteKey: String = "",
        omniRouteSttModels: String = "deepgram/nova-3,assemblyai/best",
        omniRouteTranslationModel: String = "openai/gpt-oss-20b",
        omniRouteTranslationModels: String = "openai/gpt-oss-20b,openai/gpt-oss-120b",
        smartRoutingEnabled: Boolean = true,
        qualityRetryEnabled: Boolean = true,
        compareCandidatesEnabled: Boolean = true,
        streamingOrchestrationEnabled: Boolean = true,
        quotaMonitoringEnabled: Boolean = true,
        providerBenchmarkEnabled: Boolean = false,
        circuitBreakerEnabled: Boolean = true,
        adaptiveRoutingEnabled: Boolean = false,
        benchmarkIntervalMs: Long = 6L * 60L * 60L * 1000L,
        hedgedFailoverEnabled: Boolean = false,
        hedgeDelayMs: Long = 0L
    ) = withContext(Dispatchers.IO) {
        val p = prefs ?: throw IllegalStateException("تعذر تفعيل التخزين المشفر على هذا الجهاز.")
        p.edit()
            .putInt("provider", provider.coerceIn(PROVIDER_GROQ, PROVIDER_OPENAI))
            .putBoolean("providerSchemaV2", true)
            .putString("speechKey", speechKey)
            .putString("translationKey", translationKey)
            .putString("source", source)
            .putString("target", target)
            .putInt("font", font.coerceIn(12, 48))
            .putInt("opacity", opacity.coerceIn(0, 100))
            .putString("subtitlePosition", subtitlePosition)
            .putString("email", email)
            .putString("speechLanguage", speechLanguage)
            .putBoolean("contextAwareTranslation", contextAwareTranslation)
            .putBoolean("translationMemoryEnabled", translationMemoryEnabled)
            .putString("glossary", glossary)
            .putString("contextPrompt", contextPrompt)
            .putInt("translationQuality", translationQuality.coerceIn(QUALITY_FAST, QUALITY_HUMAN))
            .putString("omniRouteBaseUrl", omniRouteBaseUrl)
            .putString("omniRouteKey", omniRouteKey)
            .putString("omniRouteSttModels", omniRouteSttModels)
            .putString("omniRouteTranslationModel", omniRouteTranslationModel)
            .putString("omniRouteTranslationModels", omniRouteTranslationModels)
            .putBoolean("smartRoutingEnabled", smartRoutingEnabled)
            .putBoolean("qualityRetryEnabled", qualityRetryEnabled)
            .putBoolean("compareCandidatesEnabled", compareCandidatesEnabled)
            .putBoolean("streamingOrchestrationEnabled", streamingOrchestrationEnabled)
            .putBoolean("quotaMonitoringEnabled", quotaMonitoringEnabled)
            .putBoolean("providerBenchmarkEnabled", providerBenchmarkEnabled)
            .putBoolean("circuitBreakerEnabled", circuitBreakerEnabled)
            .putBoolean("adaptiveRoutingEnabled", adaptiveRoutingEnabled)
            .putLong("benchmarkIntervalMs", benchmarkIntervalMs.coerceAtLeast(60_000L))
            .putBoolean("hedgedFailoverEnabled", hedgedFailoverEnabled)
            .putLong("hedgeDelayMs", hedgeDelayMs.coerceIn(0L, 2_000L))
            .apply()
    }

    suspend fun load(): AppSettings = withContext(Dispatchers.IO) {
        val p = prefs ?: return@withContext AppSettings()
        val storedProvider = p.getInt("provider", PROVIDER_GROQ)
        // Stage 4 stored 0=Google and 1=MyMemory. Migrate once to Stage 5's 0=Groq, 1=Google, 2=MyMemory.
        val provider = if (p.getBoolean("providerSchemaV2", false)) storedProvider.coerceIn(0, PROVIDER_OPENAI)
        else if (!p.contains("provider")) PROVIDER_GROQ
        else when (storedProvider) {
            0 -> PROVIDER_GOOGLE
            else -> PROVIDER_MYMEMORY
        }
        AppSettings(
            provider = provider,
            speechKey = p.getString("speechKey", "").orEmpty(),
            translationKey = p.getString("translationKey", "").orEmpty(),
            sourceLanguage = p.getString("source", "auto").orEmpty(),
            targetLanguage = p.getString("target", "ar").orEmpty(),
            fontSize = p.getInt("font", 30),
            opacity = p.getInt("opacity", 82),
            subtitlePosition = p.getString("subtitlePosition", "bottom").orEmpty(),
            myMemoryEmail = p.getString("email", "").orEmpty(),
            speechLanguage = p.getString("speechLanguage", "auto").orEmpty(),
            sttProvider = p.getString("sttProvider", "groq").orEmpty(),
            deepgramKey = p.getString("deepgramKey", "").orEmpty(),
            openAiKey = p.getString("openAiKey", "").orEmpty(),
            contextAwareTranslation = p.getBoolean("contextAwareTranslation", true),
            translationMemoryEnabled = p.getBoolean("translationMemoryEnabled", true),
            glossary = p.getString("glossary", "").orEmpty(),
            contextPrompt = p.getString("contextPrompt", "").orEmpty(),
            translationQuality = p.getInt("translationQuality", QUALITY_HUMAN).coerceIn(QUALITY_FAST, QUALITY_HUMAN),
            omniRouteBaseUrl = p.getString("omniRouteBaseUrl", "").orEmpty(),
            omniRouteKey = p.getString("omniRouteKey", "").orEmpty(),
            omniRouteSttModels = p.getString("omniRouteSttModels", "deepgram/nova-3,assemblyai/best").orEmpty(),
            omniRouteTranslationModel = p.getString("omniRouteTranslationModel", "openai/gpt-oss-20b").orEmpty(),
            omniRouteTranslationModels = p.getString("omniRouteTranslationModels", "openai/gpt-oss-20b,openai/gpt-oss-120b").orEmpty(),
            smartRoutingEnabled = p.getBoolean("smartRoutingEnabled", true),
            qualityRetryEnabled = p.getBoolean("qualityRetryEnabled", true),
            compareCandidatesEnabled = p.getBoolean("compareCandidatesEnabled", true),
            streamingOrchestrationEnabled = p.getBoolean("streamingOrchestrationEnabled", true),
            quotaMonitoringEnabled = p.getBoolean("quotaMonitoringEnabled", true),
            providerBenchmarkEnabled = p.getBoolean("providerBenchmarkEnabled", false),
            circuitBreakerEnabled = p.getBoolean("circuitBreakerEnabled", true),
            adaptiveRoutingEnabled = p.getBoolean("adaptiveRoutingEnabled", false),
            benchmarkIntervalMs = p.getLong("benchmarkIntervalMs", 6L * 60L * 60L * 1000L),
            hedgedFailoverEnabled = p.getBoolean("hedgedFailoverEnabled", false),
            hedgeDelayMs = p.getLong("hedgeDelayMs", 0L).coerceIn(0L, 2_000L)
        )
    }

    suspend fun saveSttSettings(sttProvider: String, deepgramKey: String, openAiKey: String) = withContext(Dispatchers.IO) {
        val p = prefs ?: throw IllegalStateException("تعذر تفعيل التخزين المشفر على هذا الجهاز.")
        p.edit()
            .putString("sttProvider", sttProvider)
            .putString("deepgramKey", deepgramKey.trim())
            .putString("openAiKey", openAiKey.trim())
            .apply()
    }


    /** Saves a complete loaded snapshot so editing one settings screen cannot reset unrelated fields. */
    suspend fun saveSnapshot(s: AppSettings) {
        save(
            provider = s.provider, speechKey = s.speechKey, translationKey = s.translationKey,
            source = s.sourceLanguage, target = s.targetLanguage, font = s.fontSize, opacity = s.opacity, subtitlePosition = s.subtitlePosition,
            email = s.myMemoryEmail, speechLanguage = s.speechLanguage,
            contextAwareTranslation = s.contextAwareTranslation, translationMemoryEnabled = s.translationMemoryEnabled,
            glossary = s.glossary, contextPrompt = s.contextPrompt, translationQuality = s.translationQuality,
            omniRouteBaseUrl = s.omniRouteBaseUrl, omniRouteKey = s.omniRouteKey,
            omniRouteSttModels = s.omniRouteSttModels, omniRouteTranslationModel = s.omniRouteTranslationModel,
            omniRouteTranslationModels = s.omniRouteTranslationModels,
            smartRoutingEnabled = s.smartRoutingEnabled, qualityRetryEnabled = s.qualityRetryEnabled,
            compareCandidatesEnabled = s.compareCandidatesEnabled, streamingOrchestrationEnabled = s.streamingOrchestrationEnabled,
            quotaMonitoringEnabled = s.quotaMonitoringEnabled, providerBenchmarkEnabled = s.providerBenchmarkEnabled,
            circuitBreakerEnabled = s.circuitBreakerEnabled, adaptiveRoutingEnabled = s.adaptiveRoutingEnabled,
            benchmarkIntervalMs = s.benchmarkIntervalMs, hedgedFailoverEnabled = s.hedgedFailoverEnabled,
            hedgeDelayMs = s.hedgeDelayMs
        )
        saveSttSettings(s.sttProvider, s.deepgramKey, s.openAiKey)
    }

    companion object {
        const val PROVIDER_GROQ = 0
        const val PROVIDER_GOOGLE = 1
        const val PROVIDER_MYMEMORY = 2
        const val PROVIDER_OMNIROUTE = 3
        const val PROVIDER_OPENAI = 4
        const val QUALITY_FAST = 0
        const val QUALITY_BALANCED = 1
        const val QUALITY_HUMAN = 2
    }
}
