package com.sari.subtitle

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/**
 * إعدادات عرض الترجمة + الإزاحة الزمنية.
 */
class SubtitleSettings(context: Context) {

    companion object {
        private const val PREFS = "sari_subtitle_prefs"
        private const val KEY_TEXT_SIZE = "text_size"
        private const val KEY_POSITION = "position"
        private const val KEY_TEXT_COLOR = "text_color"
        private const val KEY_BG_COLOR = "bg_color"
        private const val KEY_BOLD = "bold"
        private const val KEY_OFFSET = "offset_ms"
        private const val KEY_PROCESSING_ENGINE = "processing_engine"
        private const val KEY_STT_PROVIDER = "stt_provider"
        const val STT_AUTO = "auto-free"
        const val STT_GROQ = "groq"
        const val STT_DEEPGRAM = "deepgram"
        const val STT_OPENAI = "openai"
        const val STT_LOCAL = "local-vosk"
        const val TRANSLATION_AUTO = "auto"
        const val TRANSLATION_GEMINI = "gemini"
        const val TRANSLATION_OMNIROUTE = "omniroute"

        val TEXT_SIZES = floatArrayOf(14f, 18f, 22f, 28f, 34f)
        val TEXT_SIZE_LABELS = arrayOf("صغير", "متوسط", "كبير", "كبير جداً", "ضخم")

        const val ENGINE_INTERNAL = "internal"
        const val ENGINE_SUBWHISPER = "subwhisper"

        const val POS_BOTTOM = 0
        const val POS_CENTER = 1
        const val POS_TOP = 2
        val POSITION_LABELS = arrayOf("أسفل", "وسط", "أعلى")

        val TEXT_COLORS = intArrayOf(
            Color.WHITE,
            Color.YELLOW,
            Color.CYAN,
            Color.GREEN,
            0xFFFFA500.toInt()
        )
        val TEXT_COLOR_LABELS = arrayOf("أبيض", "أصفر", "سماوي", "أخضر", "برتقالي")

        val BG_COLORS = intArrayOf(
            Color.argb(180, 0, 0, 0),
            Color.argb(120, 0, 0, 0),
            Color.TRANSPARENT,
            Color.argb(180, 0, 0, 80)
        )
        val BG_COLOR_LABELS = arrayOf("أسود قوي", "أسود خفيف", "شفاف", "أزرق")
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ═══════════════════════════════════════════════════════════
    // مفتاح Groq (مشفر عبر Android Keystore)
    // ═══════════════════════════════════════════════════════════
    private val groqKeyStore = GroqApiKeyStore(context.applicationContext)

    var groqApiKey: String
        get() = try { groqKeyStore.read() } catch (e: Exception) { "" }
        set(value) {
            if (value.isBlank()) {
                groqKeyStore.clear()
            } else {
                groqKeyStore.save(value)
            }
        }

    fun clearGroqApiKey() {
        groqKeyStore.clear()
    }

    fun hasGroqApiKey(): Boolean {
        return try { groqKeyStore.read().isNotBlank() } catch (e: Exception) { false }
    }

    private val deepgramKeyStore = DeepgramApiKeyStore(context.applicationContext)
    var deepgramApiKey: String
        get() = try { deepgramKeyStore.read() } catch (_: Exception) { "" }
        set(value) { if (value.isBlank()) deepgramKeyStore.clear() else deepgramKeyStore.save(value) }
    fun clearDeepgramApiKey() = deepgramKeyStore.clear()
    fun hasDeepgramApiKey(): Boolean = try { deepgramKeyStore.read().isNotBlank() } catch (_: Exception) { false }

    private val openAiKeyStore = OpenAIApiKeyStore(context.applicationContext)
    var openAiApiKey: String
        get() = try { openAiKeyStore.read() } catch (_: Exception) { "" }
        set(value) { if (value.isBlank()) openAiKeyStore.clear() else openAiKeyStore.save(value) }
    fun clearOpenAiApiKey() = openAiKeyStore.clear()
    fun hasOpenAiApiKey(): Boolean = try { openAiKeyStore.read().isNotBlank() } catch (_: Exception) { false }

    private val omniRouteKeyStore = OmniRouteApiKeyStore(context.applicationContext)
    var omniRouteApiKey: String
        get() = try { omniRouteKeyStore.read() } catch (_: Exception) { "" }
        set(value) { if (value.isBlank()) omniRouteKeyStore.clear() else omniRouteKeyStore.save(value) }
    fun clearOmniRouteApiKey() = omniRouteKeyStore.clear()
    fun hasOmniRouteApiKey(): Boolean = try { omniRouteKeyStore.read().isNotBlank() } catch (_: Exception) { false }

    var omniRouteBaseUrl: String
        get() = prefs.getString("omniroute_base_url", "http://127.0.0.1:20128/v1") ?: "http://127.0.0.1:20128/v1"
        set(value) = prefs.edit().putString("omniroute_base_url", value.trim().trimEnd('/').ifBlank { "http://127.0.0.1:20128/v1" }).apply()
    var omniRouteTextModel: String
        get() = prefs.getString("omniroute_text_model", "auto") ?: "auto"
        set(value) = prefs.edit().putString("omniroute_text_model", value.trim().ifBlank { "auto" }).apply()

    var omniRouteEnabled: Boolean
        get() = prefs.getBoolean("omniroute_enabled", false)
        set(value) = prefs.edit().putBoolean("omniroute_enabled", value).apply()

    var translationProvider: String
        get() = prefs.getString("translation_provider", TRANSLATION_AUTO) ?: TRANSLATION_AUTO
        set(value) = prefs.edit().putString("translation_provider", when (value) {
            TRANSLATION_GEMINI -> TRANSLATION_GEMINI
            TRANSLATION_OMNIROUTE -> TRANSLATION_OMNIROUTE
            else -> TRANSLATION_AUTO
        }).apply()

    private val geminiKeyStore = GeminiApiKeyStore(context.applicationContext)
    var geminiApiKey: String
        get() = try { geminiKeyStore.read() } catch (_: Exception) { "" }
        set(value) { if (value.isBlank()) geminiKeyStore.clear() else geminiKeyStore.save(value) }
    fun clearGeminiApiKey() = geminiKeyStore.clear()
    fun hasGeminiApiKey(): Boolean = try { geminiKeyStore.read().isNotBlank() } catch (_: Exception) { false }

    private val deepLKeyStore = DeepLApiKeyStore(context.applicationContext)
    var deepLApiKey: String
        get() = try { deepLKeyStore.read() } catch (_: Exception) { "" }
        set(value) { if (value.isBlank()) deepLKeyStore.clear() else deepLKeyStore.save(value) }
    fun clearDeepLApiKey() = deepLKeyStore.clear()
    fun hasDeepLApiKey(): Boolean = try { deepLKeyStore.read().isNotBlank() } catch (_: Exception) { false }

    private val huggingFaceStore = ExtraApiKeyStore(context.applicationContext, "huggingface")
    var huggingFaceToken: String
        get() = try { huggingFaceStore.read() } catch (_: Exception) { "" }
        set(value) { if (value.isBlank()) huggingFaceStore.clear() else huggingFaceStore.save(value) }
    fun hasHuggingFaceToken(): Boolean = try { huggingFaceStore.read().isNotBlank() } catch (_: Exception) { false }

    private val azureTranslatorStore = ExtraApiKeyStore(context.applicationContext, "azure_translator")
    var azureTranslatorKey: String
        get() = try { azureTranslatorStore.read() } catch (_: Exception) { "" }
        set(value) { if (value.isBlank()) azureTranslatorStore.clear() else azureTranslatorStore.save(value) }
    fun hasAzureTranslatorKey(): Boolean = try { azureTranslatorStore.read().isNotBlank() } catch (_: Exception) { false }
    var azureTranslatorRegion: String
        get() = prefs.getString("azure_translator_region", "global") ?: "global"
        set(value) = prefs.edit().putString("azure_translator_region", value.trim()).apply()


    var sttProvider: String
        get() = prefs.getString(KEY_STT_PROVIDER, STT_AUTO) ?: STT_AUTO
        set(value) = prefs.edit().putString(KEY_STT_PROVIDER, when (value) {
            STT_AUTO -> STT_AUTO
            STT_GROQ -> STT_GROQ
            STT_DEEPGRAM -> STT_DEEPGRAM
            STT_OPENAI -> STT_OPENAI
            STT_LOCAL -> STT_LOCAL
            else -> STT_GROQ
        }).apply()
    val sttProviderLabel: String get() = when (sttProvider) {
        STT_AUTO -> "تلقائي — المجاني أولاً"
        STT_DEEPGRAM -> "Deepgram Nova-3"
        STT_OPENAI -> "OpenAI Whisper"
        STT_LOCAL -> "Vosk محلي دون إنترنت"
        else -> "Groq Whisper"
    }


    var textSizeIndex: Int
        get() = prefs.getInt(KEY_TEXT_SIZE, 2)
        set(v) = prefs.edit().putInt(KEY_TEXT_SIZE, v).apply()

    var position: Int
        get() = prefs.getInt(KEY_POSITION, POS_BOTTOM)
        set(v) = prefs.edit().putInt(KEY_POSITION, v).apply()

    var textColorIndex: Int
        get() = prefs.getInt(KEY_TEXT_COLOR, 0)
        set(v) = prefs.edit().putInt(KEY_TEXT_COLOR, v).apply()

    var bgColorIndex: Int
        get() = prefs.getInt(KEY_BG_COLOR, 0)
        set(v) = prefs.edit().putInt(KEY_BG_COLOR, v).apply()

    var bold: Boolean
        get() = prefs.getBoolean(KEY_BOLD, true)
        set(v) = prefs.edit().putBoolean(KEY_BOLD, v).apply()

    /** محرك المعالجة المختار: internal أو subwhisper. */
    var processingEngine: String
        get() = prefs.getString(KEY_PROCESSING_ENGINE, ENGINE_INTERNAL) ?: ENGINE_INTERNAL
        set(v) = prefs.edit().putString(
            KEY_PROCESSING_ENGINE,
            if (v == ENGINE_SUBWHISPER) ENGINE_SUBWHISPER else ENGINE_INTERNAL
        ).apply()

    /** إزاحة SRT بالمللي ثانية */
    var offsetMs: Long
        get() = prefs.getLong(KEY_OFFSET, 0L)
        set(v) = prefs.edit().putLong(KEY_OFFSET, v).apply()

    val textSize: Float get() = TEXT_SIZES[textSizeIndex.coerceIn(0, TEXT_SIZES.size - 1)]
    val textColor: Int get() = TEXT_COLORS[textColorIndex.coerceIn(0, TEXT_COLORS.size - 1)]
    val bgColor: Int get() = BG_COLORS[bgColorIndex.coerceIn(0, BG_COLORS.size - 1)]
}
