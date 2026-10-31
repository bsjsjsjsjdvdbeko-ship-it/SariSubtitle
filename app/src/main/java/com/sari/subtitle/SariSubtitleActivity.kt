package com.sari.subtitle

import com.sari.subtitle.v2.BuildConfig
import com.sari.subtitle.v2.R
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ProgressDialog
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.app.DownloadManager
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.EditText
import android.widget.ImageView
import android.graphics.drawable.Drawable
import android.widget.Toast
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.RelativeLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.media.projection.MediaProjectionManager
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.IOException
import java.io.File

/**
 * Sari Subtitle — المرحلة 3.3
 * - حفظ SRT تلقائياً بعد تحميله (مربوط بـ URL).
 * - استرجاع SRT عند فتح نفس URL.
 * - عرض عدد الملفات المحفوظة.
 */
class SariSubtitleActivity : AppCompatActivity() {

    companion object {
        private const val REQ_PICK_AUDIO = 1004
        private const val REQ_PICK_VIDEO_FOR_AUTO = 1007
        private const val REQ_SAVE_DUBBING_WAV = 1008
        private const val REQ_SAVE_WHISPER_SRT = 1005
        private const val REQ_SUBWHISPER_INTERNAL = 1006
        private const val REQ_PICK_SRT = 1001
        private const val REQ_PICK_SRT_TO_TRANSLATE = 1002
        private const val REQ_TRANSLATE_SRT_PICK = 1009
        private const val REQ_SAVE_TRANSLATED_SRT = 1003
        private const val REQ_PICK_LOCAL_VIDEO_FOR_SRT = 1010
        private const val OFFSET_STEP_MS = 500L
        private const val PREF_LAST_CACHED_VIDEO_URL = "last_cached_video_url"
        private const val RESUME_MIN_MS = 5_000L
        private const val REQ_LIVE_AUDIO_PERMISSION = 4101
        private const val REQ_LIVE_MEDIA_PROJECTION = 4102
    }

    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var operationLogText: TextView
    private lateinit var operationLogPanel: LinearLayout
    private lateinit var webView: WebView
    private lateinit var subtitleOverlay: SubtitleOverlay

    private lateinit var settings: SubtitleSettings
    private var pendingWhisperSrt: String? = null
    private lateinit var storage: SrtStorage
    private lateinit var playbackStore: PlaybackPositionStore

    private val handler = Handler(Looper.getMainLooper())
    private val hideOperationLogRunnable = Runnable {
        if (::operationLogPanel.isInitialized && !isFinishing) {
            operationLogPanel.visibility = android.view.View.GONE
            updatePlaybackAreaForLogPanel()
        }
    }
    private val repo = SubtitleRepository()
    private var polling = false
    /** Last video-like WebView URL observed by the main-thread poller (covers SPA pushState navigation). */
    private var lastObservedVideoUrl: String? = null
    /** X post identity belonging to the video currently most visible inside the WebView feed. */
    private var activeVisiblePostUrl: String? = null
    private var missingVisiblePostSamples = 0
    private var overlayOn = false
    private var subtitlesVisible = true
    private var dubbingEnabled = false
    private var ttsReady = false
    private var lastDubbingCueStartMs = -1L
    private var textToSpeech: TextToSpeech? = null
    private var activeOperationCancellation: OperationCancellation? = null
    private var fullscreenMode = false
    private var restoringOrientation = false
    private var restoreOperationLogOnFullscreenExit = false

    /** آخر SRT نصي محمّل (للحفظ عند الحاجة) */
    private var lastSrtText: String? = null

    /** URL الذي تم تحميل SRT من أجله */
    private var lastSrtUrl: String? = null
    private var pendingSourceLang = "en"
    private var pendingTargetLang = "ar"
    private var pendingTranslatedSrt: String? = null
    /** URL requested by the one-tap workflow; capture begins after WebView loads it. */
    private var pendingAutoCaptureUrl: String? = null
    private var pendingLocalVideoLibraryId: String? = null

    private var activePlaybackKey: String? = null
    private var pendingResumePositionMs = 0L
    private var resumePromptShownForKey: String? = null
    private var resumeAppliedForKey: String? = null
    private var lastPlaybackPositionMs = 0L
    private var lastPlaybackDurationMs = 0L
    private var lastPlaybackSaveAtMs = 0L

    private var liveReceiverRegistered = false
    private var lastLiveSessionId = Long.MIN_VALUE
    private var lastLiveSequence = Long.MIN_VALUE
    private var lastLiveRevision = -1
    private val liveSubtitleQueue = LiveSubtitleQueue()
    private val liveSubtitleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                LiveSubtitleBus.ACTION_CUE -> handleLiveCue(intent)
                LiveSubtitleBus.ACTION_STATUS -> handleLiveStatus(intent)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bin Rawah home: keep the exact artwork, but use real Android Button views for interaction.
        // The artwork remains the visual source of truth; buttons are transparent and positioned
        // from the actual centerCrop image rectangle, so touch coordinates stay aligned.
        val homeCanvas = findViewById<android.widget.FrameLayout>(R.id.binRawahHomeCanvas)
        val homeArtwork = findViewById<ImageView>(R.id.binRawahHomeArtwork)

        fun enterFunctionalSurface() {
            homeCanvas?.visibility = View.GONE
            findViewById<View>(R.id.webView)?.visibility = View.VISIBLE
            findViewById<View>(R.id.webContainer)?.visibility = View.VISIBLE
            findViewById<View>(R.id.topBar)?.visibility = View.VISIBLE
            findViewById<View>(R.id.bottomBar)?.visibility = View.VISIBLE
        }

        fun openSettingsCenter() {
            startActivity(Intent(this@SariSubtitleActivity, SettingsCenterActivity::class.java))
        }

        findViewById<Button>(R.id.homeStartButton).setOnClickListener {
            enterFunctionalSurface()
            findViewById<Button>(R.id.menuButton)?.performClick()
        }
        findViewById<Button>(R.id.homeHistoryButton).setOnClickListener {
            startActivity(Intent(this@SariSubtitleActivity, OperationHistoryActivity::class.java))
        }
        findViewById<Button>(R.id.homeFilesButton).setOnClickListener {
            showSavedSubtitles()
        }
        findViewById<Button>(R.id.homeSettingsButton).setOnClickListener {
            openSettingsCenter()
        }
        findViewById<Button>(R.id.homeSettingsGearButton).setOnClickListener {
            openSettingsCenter()
        }
        findViewById<Button>(R.id.homePremiumButton).setOnClickListener {
            Toast.makeText(this@SariSubtitleActivity, "النسخة المميزة — قريباً", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.homePremiumBadgeButton).setOnClickListener {
            Toast.makeText(this@SariSubtitleActivity, "النسخة المميزة — قريباً", Toast.LENGTH_SHORT).show()
        }

        fun layoutHomeButtons() {
            val drawable: Drawable = homeArtwork.drawable ?: return
            val iw = drawable.intrinsicWidth.toFloat()
            val ih = drawable.intrinsicHeight.toFloat()
            val vw = homeArtwork.width.toFloat()
            val vh = homeArtwork.height.toFloat()
            if (iw <= 0f || ih <= 0f || vw <= 0f || vh <= 0f) return

            // Exact centerCrop geometry used by ImageView.
            val scale = maxOf(vw / iw, vh / ih)
            val displayedW = iw * scale
            val displayedH = ih * scale
            val left = (vw - displayedW) / 2f
            val top = (vh - displayedH) / 2f

            fun place(id: Int, x: Double, y: Double, width: Double, height: Double) {
                val button = findViewById<Button>(id) ?: return
                val lp = android.widget.FrameLayout.LayoutParams(
                    (displayedW * width).toInt().coerceAtLeast(1),
                    (displayedH * height).toInt().coerceAtLeast(1)
                )
                lp.leftMargin = (left + displayedW * x).toInt()
                lp.topMargin = (top + displayedH * y).toInt()
                button.layoutParams = lp
                button.bringToFront()
            }

            // Same visual regions as the approved artwork; only the implementation changed.
            place(R.id.homeStartButton, 0.12, 0.65, 0.76, 0.095)
            place(R.id.homeHistoryButton, 0.04, 0.755, 0.21, 0.115)
            place(R.id.homeFilesButton, 0.285, 0.755, 0.20, 0.115)
            place(R.id.homeSettingsButton, 0.525, 0.755, 0.20, 0.115)
            place(R.id.homeSettingsGearButton, 0.805, 0.055, 0.125, 0.095)
            place(R.id.homePremiumButton, 0.765, 0.755, 0.205, 0.115)
            place(R.id.homePremiumBadgeButton, 0.035, 0.055, 0.18, 0.095)
        }

        homeArtwork.post { layoutHomeButtons() }
        homeArtwork.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutHomeButtons() }

        settings = SubtitleSettings(this)
        applyPreferredOrientation()
        storage = SrtStorage(this)
        playbackStore = PlaybackPositionStore(this)
        repo.offsetMs = settings.offsetMs
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = textToSpeech
                val languageResult = engine?.setLanguage(Locale("ar")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                val offlineArabicVoice = engine?.voices?.firstOrNull {
                    it.locale.language.equals("ar", ignoreCase = true) && !it.isNetworkConnectionRequired
                }
                if (offlineArabicVoice != null) engine?.voice = offlineArabicVoice
                ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                    languageResult != TextToSpeech.LANG_NOT_SUPPORTED && offlineArabicVoice != null
                if (!ttsReady) runOnUiThread {
                    Toast.makeText(this, "لا يوجد صوت عربي محلي جاهز. نزّل بيانات صوت عربي غير متصل من إعدادات تحويل النص إلى كلام في الهاتف.", Toast.LENGTH_LONG).show()
                }
            }
        }

        urlInput = findViewById(R.id.urlInput)
        statusText = findViewById(R.id.statusText)
        operationLogText = findViewById(R.id.operationLogText)
        operationLogPanel = findViewById(R.id.operationLogPanel)
        OperationLogStore.onChanged = { runOnUiThread { if (!isFinishing) refreshOperationLogPanel() } }
        findViewById<Button>(R.id.operationLogButton).setOnClickListener {
            startActivity(Intent(this, OperationHistoryActivity::class.java))
        }
        findViewById<Button>(R.id.operationLogToggleButton).setOnClickListener {
            startActivity(Intent(this, OperationHistoryActivity::class.java))
        }
        refreshOperationLogPanel()
        webView = findViewById(R.id.webView)
        subtitleOverlay = findViewById(R.id.subtitleOverlay)
        updatePlaybackAreaForLogPanel()

        subtitleOverlay.applySettings(settings)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
            userAgentString = "Mozilla/5.0 (Linux; Android 13; SM-G991B) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val target = url?.trim().orEmpty()
                if (target.isNotBlank() && lastSrtUrl?.let { sameVideoUrl(it, target) } != true) {
                    // A page/profile URL is not itself a video identity. Never carry the
                    // previous post's cues while a new page/feed video is being identified.
                    activeVisiblePostUrl = null
                    clearActiveSubtitleForVideoChange()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectAutoShow()
                // Only direct video pages can be restored here. Profile/feed pages are
                // resolved later from the status link of the actually visible video card.
                if (url != null && isVideoLikeUrl(url)) {
                    if (isXStatusUrl(url)) activeVisiblePostUrl = url
                    tryAutoLoadSrt(url)
                    preparePlaybackResume(url)
                }
                val pending = pendingAutoCaptureUrl
                if (!pending.isNullOrBlank() && !url.isNullOrBlank() && sameVideoUrl(pending, url)) {
                    pendingAutoCaptureUrl = null
                    handler.postDelayed({
                        if (!isFinishing && webView.url?.let { sameVideoUrl(pending, it) } == true) {
                            transcribeCurrentWebVideo()
                        }
                    }, 2200L)
                }
            }
        }
        webView.webChromeClient = WebChromeClient()
        
        // ═══════════════════════════════════════════════════════════
        // تحميل الملفات من WebView
        // ═══════════════════════════════════════════════════════════
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            try {
                // Download URLs may contain signed tokens or private query parameters. Log only the host.
                val downloadHost = runCatching { android.net.Uri.parse(url).host }
                    .getOrNull()?.take(120) ?: "local-or-inline"
                android.util.Log.d("SariDownload", "Download request: host=$downloadHost (mime=$mimeType, length=$contentLength)")
                
                // إذا كان blob: → نحتاج JS لتحويله إلى base64
                if (url.startsWith("blob:") || url.startsWith("data:")) {
                    downloadBlobViaJs(url, mimeType)
                    return@setDownloadListener
                }
                
                val request = DownloadManager.Request(android.net.Uri.parse(url))
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                request.setMimeType(mimeType)
                request.addRequestHeader("User-Agent", userAgent)
                request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                request.setTitle(fileName)
                request.setDescription("تحميل من التطبيق")
                request.allowScanningByMediaScanner()
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                val id = dm.enqueue(request)
                Toast.makeText(this, "⬇️ بدأ التنزيل: $fileName", Toast.LENGTH_LONG).show()
                statusText.text = "⬇️ تنزيل: $fileName"
                statusText.visibility = android.view.View.VISIBLE
            } catch (e: Exception) {
                Toast.makeText(this, "❌ فشل التنزيل: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.loadButton).setOnClickListener {
            enterFunctionalSurface()
            val typed = urlInput.text.toString().trim()
            if (typed.isNotEmpty()) {
                webView.loadUrl(typed)
            } else {
                val clipboardUrl = try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = if (clipboard.hasPrimaryClip() && (clipboard.primaryClip?.itemCount ?: 0) > 0)
                        clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty() else ""
                    Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE).find(text)
                        ?.value?.trimEnd('.', ',', ')', ']', '}', '،').orEmpty()
                } catch (_: Exception) { "" }
                if (clipboardUrl.isNotBlank()) {
                    urlInput.setText(clipboardUrl)
                    webView.loadUrl(clipboardUrl)
                } else {
                    Toast.makeText(this, "ألصق رابط الفيديو أولًا، أو اضغط ابدأ تلقائيًا لاستخدام الرابط المنسوخ", Toast.LENGTH_LONG).show()
                }
            }
        }

        findViewById<Button>(R.id.generateSrtFromLinkButton).setOnClickListener {
            openSrtGenerator()
        }

        findViewById<Button>(R.id.importSrtFromUrlButton).setOnClickListener {
            promptImportSrtFromUrl()
        }

        findViewById<Button>(R.id.transcribeDirectMediaUrlButton).setOnClickListener {
            promptTranscribeDirectMediaUrl()
        }

        findViewById<Button>(R.id.analyzeLinkButton).setOnClickListener {
            analyzeCurrentLink()
        }


        findViewById<Button>(R.id.transcribeAudioButton).setOnClickListener {
            openAudioPicker()
        }

        findViewById<Button>(R.id.transcribeXAutoButton).setOnClickListener {
            transcribeCurrentWebVideo()
        }

        findViewById<Button>(R.id.pasteLinkTranscribeButton).setOnClickListener {
            openPasteLinkFlow()
        }

        // نسخ الرابط فقط داخل Sari Subtitle؛ لا يفتح SubWhisper أو أي تطبيق خارجي.
        findViewById<Button>(R.id.copyLinkOnlyButton).setOnClickListener {
            val link = urlInput.text.toString().trim()
                .ifBlank { webView.url.orEmpty().trim() }
            val uri = try { Uri.parse(link) } catch (_: Exception) { null }
            if (link.isBlank() || uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
                Toast.makeText(this, "أدخل رابط فيديو صحيحاً أولاً", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Video URL", link))
                Toast.makeText(this, "تم نسخ الرابط فقط — لم يتم فتح أي تطبيق خارجي", Toast.LENGTH_SHORT).show()
                statusText.text = "تم نسخ الرابط إلى الحافظة. لم يتم فتح خدمة خارجية."
            }
        }

        findViewById<Button>(R.id.translateSrtButton).setOnClickListener {
            // ترجمة تلقائية: يكتشف اللغة ويفتح picker مباشرة
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                setType("*/*")
            }
            startActivityForResult(
                Intent.createChooser(intent, "اختر ملف SRT للترجمة التلقائية"),
                REQ_PICK_SRT_TO_TRANSLATE
            )
        }

        findViewById<Button>(R.id.reloadXButton).setOnClickListener {
            urlInput.setText("https://x.com/")
            webView.loadUrl("https://x.com/")
        }

        findViewById<Button>(R.id.checkTimeButton).setOnClickListener {
            pollCurrentTimeOnce()
        }

        // الزر الرئيسي يحترم محرك المعالجة المختار في الإعدادات.
        // المحرك الداخلي يترجم SRT داخل التطبيق ولا يفتح SubWhisper تلقائياً.
        findViewById<Button>(R.id.menuButton).apply {
            setOnClickListener {
                enterFunctionalSurface()
                startPrimaryTranslationFlow()
            }
            setOnLongClickListener {
                toggleLiveCapture()
                true
            }
        }

        // واجهة المستخدم الرئيسية تعرض الوظائف الأساسية فقط؛ المحفوظات لا تحتاج إعادة تفريغ.
        findViewById<Button>(R.id.savedItemsButton).setOnClickListener {
            showSavedSubtitles()
        }

        findViewById<Button>(R.id.dubbingButton).apply {
            setOnClickListener { toggleArabicDubbing() }
            setOnLongClickListener {
                subtitlesVisible = !subtitlesVisible
                if (!subtitlesVisible) subtitleOverlay.hide()
                else pollCurrentTimeOnce()
                Toast.makeText(this@SariSubtitleActivity, if (subtitlesVisible) "تم إظهار الترجمة المكتوبة" else "تم إخفاء الترجمة المكتوبة", Toast.LENGTH_SHORT).show()
                true
            }
        }

        findViewById<Button>(R.id.dubbingExportButton).setOnClickListener {
            requestDubbingWavExport()
        }

        findViewById<Button>(R.id.testCaptureButton).setOnClickListener {
            testCaptureStream()
        }


        // اختبار captureStream
        findViewById<Button>(R.id.checkTimeButton).setOnLongClickListener {
            testCaptureStream()
            true
        }

        findViewById<Button>(R.id.toggleOverlayButton).setOnClickListener {
            toggleOverlay()
        }

        findViewById<Button>(R.id.loadSrtButton).setOnClickListener {
            // سير ملف واحد: يكتشف اللغة؛ إن كان عربيًا يعرضه، وإلا يترجمه للعربية تلقائيًا.
            openSrtPicker()
        }

        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsCenterActivity::class.java))
        }

        findViewById<Button>(R.id.fullscreenButton).setOnClickListener {
            setFullscreenMode(!fullscreenMode)
        }

        findViewById<Button>(R.id.rotationButton).setOnClickListener {
            toggleScreenOrientation()
        }
        updateRotationButton()

        findViewById<Button>(R.id.offsetMinusButton).setOnClickListener {
            changeOffset(-OFFSET_STEP_MS)
        }
        findViewById<Button>(R.id.offsetPlusButton).setOnClickListener {
            changeOffset(+OFFSET_STEP_MS)
        }
        findViewById<Button>(R.id.offsetResetButton).setOnClickListener {
            repo.offsetMs = 0L
            settings.offsetMs = 0L
            Toast.makeText(this, "الإزاحة: 0.0s", Toast.LENGTH_SHORT).show()
            pollCurrentTimeOnce()
        }

        startPolling()
        val openSavedItems = intent.getBooleanExtra("open_saved_items", false)
        if (openSavedItems) {
            // Navigation helper from the new Settings Center. It only opens the existing
            // saved-items screen; it does not alter any translation/video state.
            window.decorView.post { showSavedSubtitles() }
        }

        val sharedUrl = extractSharedUrl(intent)
        if (sharedUrl != null) {
            urlInput.setText(sharedUrl)
            webView.loadUrl(sharedUrl)
            statusText.text = "تم استلام رابط مشاركته مع Sari Subtitle. اضغط «إنشاء SRT من رابط» لإنشاء الترجمة العربية."
        } else {
            // Always start at the home page. Cached URLs remain available through history,
            // but must never hijack a fresh app launch.
            activeVisiblePostUrl = null
            lastObservedVideoUrl = null
            urlInput.setText("https://x.com/")
            webView.loadUrl("https://x.com/")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val sharedUrl = extractSharedUrl(intent) ?: return
        urlInput.setText(sharedUrl)
        webView.loadUrl(sharedUrl)
        statusText.text = "تم استلام الرابط المشترك. اضغط «إنشاء SRT من رابط» للمتابعة."
    }

    private fun beginTrackedOperation(cancellation: OperationCancellation) {
        val previous = activeOperationCancellation
        if (previous != null && previous !== cancellation) previous.cancel()
        activeOperationCancellation = cancellation
    }

    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim() ?: return null
        val match = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE).find(shared)
        val candidate = match?.value?.trimEnd('.', ',', ')', ']', '}', '،') ?: return null
        val uri = try { Uri.parse(candidate) } catch (_: Exception) { return null }
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) return null
        return candidate
    }

    /**
     * Restore a valid saved subtitle file into the current player state.
     * Returns true only when a non-empty, parseable SRT was restored.
     */
    private fun restoreCachedSrt(url: String): Boolean {
        return try {
            val srtText = storage.load(url) ?: return false
            val cues = SrtParser.parse(srtText.byteInputStream(Charsets.UTF_8))
            if (cues.isEmpty()) return false
            repo.setCues(cues)
            lastSrtText = srtText
            lastSrtUrl = url
            rememberCachedVideoUrl(url)
            if (!overlayOn) overlayOn = true
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * عند فتح URL جديد — إن كان له SRT محفوظ، حمّله تلقائياً.
     */
    private fun tryAutoLoadSrt(url: String): Boolean {
        if (lastSrtUrl?.let { sameVideoUrl(it, url) } == true && !repo.isEmpty) {
            rememberCachedVideoUrl(url)
            return true
        }
        if (restoreCachedSrt(url)) {
            rememberCachedVideoUrl(url)
            runOnUiThread {
                if (!isFinishing) Toast.makeText(this, "✅ تمت استعادة SRT المحفوظ: ${repo.size} مقطع", Toast.LENGTH_SHORT).show()
            }
            return true
        }
        // Prevent subtitles from a previous URL-bound video from appearing over a new video.
        val previousUrl = lastSrtUrl
        if (!previousUrl.isNullOrBlank() && !sameVideoUrl(previousUrl, url)) {
            repo.clear()
            repo.offsetMs = settings.offsetMs
            lastSrtText = null
            lastSrtUrl = null
            overlayOn = false
            subtitleOverlay.hide()
        }
        return false
    }

    /**
     * تعديل الإزاحة.
     */
    private fun changeOffset(deltaMs: Long) {
        if (repo.isEmpty) {
            Toast.makeText(this, "حمّل SRT أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        val newOffset = (repo.offsetMs + deltaMs).coerceIn(-30000L, 30000L)
        repo.offsetMs = newOffset
        settings.offsetMs = newOffset

        val sign = if (newOffset >= 0) "+" else ""
        val secStr = "%.1f".format(newOffset / 1000.0)
        Toast.makeText(this, "الإزاحة: $sign${secStr}s", Toast.LENGTH_SHORT).show()
        pollCurrentTimeOnce()
    }

    /** تحليل الرابط قبل محاولة معالجته، لتوضيح ما يمكن فعله فعلياً دون افتراض أن كل منصة قابلة للاستخراج. */
    private fun analyzeCurrentLink() {
        val raw = urlInput.text.toString().trim().ifBlank { webView.url.orEmpty().trim() }
        val result = LinkSupportAnalyzer.analyze(raw)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(result.title)
            .setMessage(result.details)
            .setPositiveButton("نسخ الرابط") { _, _ ->
                if (result.normalizedUrl.isNotBlank()) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Video URL", result.normalizedUrl))
                    statusText.text = "تم نسخ الرابط. ${result.shortStatus}"
                    Toast.makeText(this, "تم نسخ الرابط", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("فتح الرابط") { _, _ ->
                if (result.normalizedUrl.isNotBlank()) {
                    urlInput.setText(result.normalizedUrl)
                    webView.loadUrl(result.normalizedUrl)
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
        statusText.text = result.shortStatus
    }

    /**
     * ينزّل ملف صوت/فيديو من رابط مباشر فقط ثم يرسله إلى مزود STT المختار لإنشاء SRT.
     * لا يحاول استخراج روابط الوسائط من صفحات X/YouTube؛ هذه تحتاج مسار استخراج منفصلاً.
     */
    private fun promptTranscribeDirectMediaUrl() {
        val input = EditText(this).apply {
            hint = "https://example.com/video.mp4 أو audio.mp3"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(urlInput.text.toString().takeIf { it.contains(".") && !it.contains("x.com") }.orEmpty())
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 8, 40, 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
            addView(TextView(this@SariSubtitleActivity).apply {
                text = "يعمل فقط مع رابط مباشر لملف صوت/فيديو يمكن تنزيله دون تسجيل دخول. لا يعمل مع رابط صفحة X أو YouTube العادي. حد التنزيل 24 MB، وبعده يُرسل الملف إلى مزود STT المختار؛ قد تُحسب تكلفة/حصة API وفق حسابك."
                setPadding(0, 12, 0, 0)
            })
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("إنشاء SRT من ملف وسائط مباشر")
            .setView(wrap)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("تنزيل وتفريغ") { _, _ ->
                val link = input.text.toString().trim()
                val uri = try { Uri.parse(link) } catch (_: Exception) { null }
                if (uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
                    Toast.makeText(this, "أدخل رابط HTTP/HTTPS صالحاً", Toast.LENGTH_LONG).show()
                } else if (!hasSelectedSttKey()) {
                    Toast.makeText(this, "جهّز مزود التفريغ من الإعدادات (مفتاح API أو نموذج Vosk المحلي) أولاً", Toast.LENGTH_LONG).show()
                } else {
                    chooseDirectMediaLanguage(link)
                }
            }
            .show()
    }

    private fun chooseDirectMediaLanguage(link: String) {
        val labels = arrayOf("اكتشاف اللغة تلقائياً", "العربية", "الإنجليزية")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("لغة الصوت")
            .setItems(labels) { _, selected ->
                val lang = when (selected) { 1 -> "ar"; 2 -> "en"; else -> "auto" }
                downloadAndTranscribeDirectMedia(link, lang)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun downloadAndTranscribeDirectMedia(link: String, language: String) {
        val cancellation = OperationCancellation()
        beginTrackedOperation(cancellation)
        val progress = ProgressDialog(this).apply {
            setTitle("إنشاء ملف SRT")
            setMessage("جارٍ تنزيل ملف الوسائط…")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            setButton(ProgressDialog.BUTTON_NEGATIVE, "إلغاء") { _, _ -> cancellation.cancel() }
            show()
            getButton(ProgressDialog.BUTTON_NEGATIVE)?.setOnClickListener { cancellation.cancel(); setMessage("جارٍ إيقاف التنزيل والتفريغ…") }
        }
        statusText.text = "جارٍ تنزيل ملف الوسائط من الرابط المباشر…"
        val worker = Thread {
            var temp: java.io.File? = null
            var connection: java.net.HttpURLConnection? = null
            try {
                cancellation.check()
                connection = NetworkRequestRegistry.register(java.net.URL(link).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                }
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("فشل تنزيل الملف (HTTP $code). قد يكون الرابط صفحة ويب وليس ملف وسائط مباشراً.")
                val type = connection.contentType.orEmpty().lowercase(java.util.Locale.ROOT)
                if (type.contains("text/html") || type.contains("application/json")) {
                    throw IOException("الرابط أعاد صفحة ويب/JSON وليس ملف وسائط مباشراً. روابط صفحات X وYouTube لا تعمل بهذا المسار.")
                }
                val declared = connection.contentLengthLong
                if (declared > 24L * 1024L * 1024L) throw IOException("حجم الملف يتجاوز 24 MB. اختر ملفاً أصغر لتجنب استهلاك البيانات.")
                val path = Uri.parse(link).lastPathSegment.orEmpty()
                val ext = path.substringAfterLast('.', "bin").lowercase(java.util.Locale.ROOT).takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
                temp = java.io.File.createTempFile("direct_media_", ".$ext", cacheDir)
                var total = 0L
                connection.inputStream.use { inputStream -> temp!!.outputStream().buffered().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        cancellation.check()
                        val count = inputStream.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 24L * 1024L * 1024L) throw IOException("توقف التنزيل لأن الملف تجاوز 24 MB.")
                        output.write(buffer, 0, count)
                    }
                } }
                if (total == 0L) throw IOException("الملف الذي تم تنزيله فارغ.")
                runOnUiThread { progress.setMessage("تم تنزيل ${(total / 1024)} KB — جارٍ التفريغ عبر ${settings.sttProviderLabel}…") }
                cancellation.check()
                val result = transcribeWithSelectedProvider(Uri.fromFile(temp!!), language)
                cancellation.check()
                runOnUiThread {
                    if (isFinishing || cancellation.isCancelled()) { progress.dismiss(); Toast.makeText(this, "تم إلغاء العملية", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                    progress.dismiss()
                    pendingWhisperSrt = result.srt
                    statusText.text = "اكتمل التفريغ: ${result.segmentCount} مقطع"
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("تم إنشاء SRT")
                        .setMessage("تم استخراج ${result.segmentCount} مقطعاً. احفظ الملف لاستخدامه لاحقاً.")
                        .setNegativeButton("إغلاق", null)
                        .setPositiveButton("حفظ SRT") { _, _ ->
                            val saveIntent1 = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                setType("application/x-subrip")
                                putExtra(Intent.EXTRA_TITLE, "transcription.srt")
                            }
                            startActivityForResult(saveIntent1, REQ_SAVE_WHISPER_SRT)
                        }
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        progress.dismiss()
                        if (cancellation.isCancelled() || e is java.util.concurrent.CancellationException) { pendingWhisperSrt = null; statusText.text = "تم إلغاء العملية"; Toast.makeText(this, "تم إلغاء العملية", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                        statusText.text = "تعذر إنشاء SRT من الرابط المباشر"
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("تعذر إنشاء SRT")
                            .setMessage(e.message ?: "خطأ غير معروف")
                            .setPositiveButton("حسناً", null)
                            .show()
                    }
                }
            } finally {
                connection?.disconnect()
                connection?.let { NetworkRequestRegistry.unregister(it) }
                temp?.delete()
            }
        }.also { cancellation.worker = it; it.start() }
    }

    /** يتيح جلب ملف ترجمة SRT منشور برابط مباشر؛ لا يستخرج الصوت من رابط الفيديو. */
    private fun promptImportSrtFromUrl() {
        val input = EditText(this).apply {
            hint = "https://example.com/subtitles.srt"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(urlInput.text.toString().takeIf { it.lowercase().endsWith(".srt") }.orEmpty())
            setSelection(text.length)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("استيراد SRT من رابط مباشر")
            .setMessage("أدخل رابط ملف ترجمة SRT مباشر. هذا يستورد ملف ترجمة موجوداً ولا ينشئ ترجمة من رابط الفيديو.")
            .setView(input)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("تحميل الملف") { _, _ ->
                val subtitleUrl = input.text.toString().trim()
                val uri = try { Uri.parse(subtitleUrl) } catch (_: Exception) { null }
                if (uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
                    Toast.makeText(this, "أدخل رابط SRT صالحاً يبدأ بـ https:// أو http://", Toast.LENGTH_LONG).show()
                } else {
                    downloadSrtFromUrl(subtitleUrl)
                }
            }
            .show()
    }

    private fun downloadSrtFromUrl(subtitleUrl: String) {
        val progress = ProgressDialog(this).apply {
            setTitle("تحميل ملف SRT")
            setMessage("جارٍ تنزيل ملف الترجمة…")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(true)
            show()
        }
        val worker = Thread {
            var connection: java.net.HttpURLConnection? = null
            try {
                connection = (java.net.URL(subtitleUrl).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 25000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "text/plain, application/x-subrip, */*")
                    setRequestProperty("User-Agent", "SariSubtitle/0.4 Android")
                }
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("تعذر تحميل الملف (HTTP $code)")
                val length = connection.contentLengthLong
                if (length > 5L * 1024L * 1024L) throw IOException("حجم ملف SRT أكبر من الحد المسموح (5 MB)")
                val bytes = connection.inputStream.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("تم إلغاء التحميل")
                        val n = stream.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > 5L * 1024L * 1024L) throw IOException("حجم ملف SRT أكبر من الحد المسموح (5 MB)")
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
                val text = SrtParser.decode(bytes).removePrefix("\uFEFF")
                val cues = SrtParser.parse(bytes.inputStream())
                if (cues.isEmpty()) throw IOException("الرابط لا يحتوي على مقاطع SRT صالحة. تأكد أنه رابط ملف مباشر وليس صفحة ويب.")
                runOnUiThread {
                    if (!isFinishing) {
                        repo.setCues(cues)
                        lastSrtText = text
                        val videoUrl = currentVideoUrlForCache().orEmpty()
                        val cacheSaved = videoUrl.isNotBlank() && saveCachedSrt(videoUrl, text, cues.size)
                        lastSrtUrl = videoUrl.takeIf { it.isNotBlank() }
                        overlayOn = true
                        statusText.text = if (cacheSaved) {
                            "تم تحميل SRT من رابط مباشر: ${cues.size} مقطع — حُفظ للفيديو الحالي"
                        } else {
                            "تم تحميل SRT من رابط مباشر: ${cues.size} مقطع، لكن تعذّر تأكيد حفظه تلقائيًا"
                        }
                        Toast.makeText(this, "تم استيراد ${cues.size} مقطع SRT", Toast.LENGTH_LONG).show()
                    }
                    progress.dismiss()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        progress.dismiss()
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("تعذر تحميل SRT")
                            .setMessage(e.message ?: "خطأ غير معروف")
                            .setPositiveButton("حسناً", null)
                            .show()
                    }
                }
            } finally {
                connection?.disconnect()
            }
        }
        progress.setOnCancelListener { worker.interrupt() }
        worker.start()
    }

    /**
     * Generates SRT using a real provider-specific path where possible.
     * Public YouTube captions are fetched directly (no video/audio download); other platforms
     * continue through the explicit supported paths instead of pretending universal extraction.
     */
    private fun openSrtGenerator() {
        val videoUrl = (urlInput.text.toString().trim().ifBlank { webView.url ?: "" }).trim()
        val uri = try { Uri.parse(videoUrl) } catch (_: Exception) { null }
        if (videoUrl.isBlank() || uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
            Toast.makeText(this, "ألصق رابط فيديو صحيحاً أولاً", Toast.LENGTH_LONG).show()
            urlInput.requestFocus()
            return
        }

        // Cache-first: reuse a previously saved SRT before contacting any provider or site.
        // This avoids spending transcription quota when the same URL is opened again.
        if (restoreCachedSrt(videoUrl)) {
            Toast.makeText(this, "تم تحميل الترجمة المحفوظة دون إعادة المعالجة", Toast.LENGTH_LONG).show()
            statusText.text = "تمت استعادة الترجمة المحفوظة لهذا الفيديو. لم يتم استدعاء أي مزود ذكاء اصطناعي."
            return
        }

        val youtubeId = YouTubeCaptionExtractor.getVideoId(videoUrl)
        if (youtubeId != null) {
            promptYouTubeCaptionExtraction(videoUrl)
            return
        }

        if (settings.processingEngine == SubtitleSettings.ENGINE_INTERNAL) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("محرك Sari Subtitle الداخلي")
                .setMessage("تم اختيار المحرك الداخلي. إنشاء SRT من رابط الفيديو لم يُنفّذ بعد: لا يوجد حالياً خادم استخراج صوت وتفريغ كلام مهيأ لهذا المسار. لن نفتح SubWhisper تلقائياً ولن ندّعي نجاح العملية. يمكنك حالياً استخدام زر «ترجمة ملف SRT» لترجمة ملف موجود.")
                .setPositiveButton("حسنًا", null)
                .show()
            return
        }

        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Video URL", videoUrl))
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("إنشاء SRT عبر SubWhisper")
                .setMessage("نُسخ رابط الفيديو. سيفتح الموقع الخارجي؛ الصق الرابط هناك، واختر اللغة المستهدفة، ثم نزّل SRT وارجع إلى التطبيق لاستيراده. قد لا تدعم الخدمة بعض الروابط أو قد تفرض حدوداً مجانية. هذا المسار خارجي وليس محركاً مدمجاً.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("متابعة داخل التطبيق") { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://sub.kuandre.com/")))
                    } catch (e: Exception) {
                        Toast.makeText(this, "تعذّر فتح الموقع: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "تعذّر تجهيز الرابط: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun currentVideoTitle(): String? {
        val title = webView.title?.trim().orEmpty()
        if (title.isBlank()) return null
        val generic = setOf("X", "Twitter", "YouTube", "YouTube · Home", "X / Twitter")
        return title.takeIf { it !in generic && it.length >= 2 }?.take(180)
    }

    /** Translate a fetched/generated SRT to Arabic, preserve cue timestamps, show it, and cache it. */
    private fun translateAndShowSrtAutomatically(
        videoUrl: String,
        sourceSrt: String,
        fileName: String,
        localVideoUri: String? = null,
        sourceTitle: String? = null
    ) {
        val translationStarted = android.os.SystemClock.elapsedRealtime()
        val cancellation = OperationCancellation()
        beginTrackedOperation(cancellation)
        val progress = ProgressDialog(this).apply {
            setTitle("الترجمة العربية")
            setMessage("جارٍ اكتشاف اللغة — ترتيب الترجمة: ${translationRouteLabel()}…")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            setButton(ProgressDialog.BUTTON_NEGATIVE, "إلغاء") { _, _ -> cancellation.cancel() }
            show()
            getButton(ProgressDialog.BUTTON_NEGATIVE)?.setOnClickListener { cancellation.cancel(); setMessage("جارٍ إلغاء العملية…") }
        }
        val worker = Thread {
            try {
                cancellation.check()
                val translated = SrtTranslationService.translateAuto(
                    source = sourceSrt,
                    targetLang = "ar",
                    context = applicationContext,
                    shouldCancel = { cancellation.isCancelled() },
                    onDetected = { lang -> runOnUiThread { if (!isFinishing) progress.setMessage("لغة المصدر: $lang — جارٍ الترجمة إلى العربية…") } },
                    onProgress = { done, total -> runOnUiThread { if (!isFinishing) progress.setMessage("جارٍ ترجمة المقاطع: $done / $total") } }
                )
                cancellation.check()
                val cues = SrtParser.parse(translated.byteInputStream(Charsets.UTF_8))
                if (cues.isEmpty()) throw IllegalStateException("لم ينتج ملف SRT صالح")
                recordOperation("ترجمة SRT تلقائية", "SrtTranslationService", translationRouteLabel(), translationStarted,
                    "نجاح", "${cues.size} مقطع", "هذا هو المسار المختار وليس ادعاءً بنجاح مزود بعينه؛ راجع أحداث «محاولة مزود ترجمة SRT» لمعرفة المزود الفعلي وأسباب الفشل.")
                runOnUiThread {
                    if (isFinishing || cancellation.isCancelled()) { progress.dismiss(); pendingWhisperSrt = null; Toast.makeText(this, "تم إلغاء العملية", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                    progress.dismiss()
                    repo.setCues(cues)
                    lastSrtText = translated
                    lastSrtUrl = videoUrl
                    pendingWhisperSrt = translated
                    val cacheSaved = saveCachedSrt(videoUrl, translated, cues.size)
                    val librarySaved = storage.saveLibraryItem(
                        "ar_${fileName.removeSuffix(".srt")}.srt", translated, cues.size, videoUrl,
                        sourceTitle = sourceTitle ?: currentVideoTitle(),
                        localVideoUri = localVideoUri,
                        localVideoTitle = localVideoUri?.let { queryDisplayName(Uri.parse(it)) },
                        preserveVersion = true
                    ) != null
                    overlayOn = true
                    statusText.visibility = android.view.View.VISIBLE
                    statusText.text = when {
                        cacheSaved -> "اكتملت الترجمة العربية: ${cues.size} مقطع — حُفظت للاستعادة التلقائية."
                        librarySaved -> "اكتملت الترجمة العربية: ${cues.size} مقطع — حُفظت في «ملفاتي»، لكن تعذّر تأكيد ربطها للاستعادة التلقائية."
                        else -> "اكتملت الترجمة العربية: ${cues.size} مقطع، لكن تعذّر حفظها. صدّر نسخة SRT للاحتفاظ بها."
                    }
                    pollCurrentTimeOnce()
                    Toast.makeText(this, if (cacheSaved || librarySaved) "اكتملت الترجمة وحُفظت داخل التطبيق" else "اكتملت الترجمة لكن تعذّر حفظ الملف", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                recordOperation("ترجمة SRT تلقائية", "SrtTranslationService", translationRouteLabel(), translationStarted,
                    if (cancellation.isCancelled() || e is java.util.concurrent.CancellationException) "أُلغيت" else "فشل",
                    "", (e.message ?: "خطأ غير معروف").take(400))
                runOnUiThread {
                    if (!isFinishing) {
                        progress.dismiss()
                        if (cancellation.isCancelled() || e is java.util.concurrent.CancellationException) { statusText.text = "تم إلغاء العملية"; Toast.makeText(this, "تم إلغاء العملية", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                        statusText.text = "عُثر على SRT لكن تعذرت ترجمته تلقائياً: ${e.message ?: "خطأ غير معروف"}"
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("تعذرت الترجمة العربية")
                            .setMessage("تعذرت الترجمة عبر مسار المزود المحدد. حُفظت التوقيتات الأصلية؛ تحقق من المزود والاتصال ثم أعد المحاولة.\n\n${e.message ?: "خطأ غير معروف"}")
                            .setPositiveButton("حسنًا", null)
                            .show()
                    }
                }
            }
        }.also { cancellation.worker = it; it.start() }
    }

    /** Extracts an existing public YouTube caption track without downloading the video. */
    private fun promptYouTubeCaptionExtraction(videoUrl: String) {
        val labels = arrayOf(
            "أفضل مسار متاح تلقائياً",
            "العربية",
            "الإنجليزية"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("إنشاء SRT من ترجمة YouTube")
            .setItems(labels) { _, selected ->
                val language = when (selected) { 1 -> "ar"; 2 -> "en"; else -> "auto" }
                fetchYouTubeCaptions(videoUrl, language)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun fetchYouTubeCaptions(videoUrl: String, language: String) {
        val progress = ProgressDialog(this).apply {
            setTitle("جلب ترجمة YouTube")
            setMessage("جارٍ فحص مسارات الترجمة العامة…")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }
        statusText.text = "جارٍ محاولة استخراج مسار ترجمة عام من YouTube دون تنزيل الفيديو…"
        Thread {
            try {
                val result = YouTubeCaptionExtractor.extract(videoUrl, language)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    progress.dismiss()
                    statusText.text = "عُثر على ${result.cueCount} مقطعاً (${result.languageCode}). جارٍ تجهيز الترجمة العربية…"
                    translateAndShowSrtAutomatically(videoUrl, result.srt, "youtube-captions-${YouTubeCaptionExtractor.getVideoId(videoUrl)}.srt")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        progress.dismiss()
                        statusText.text = "لم توجد ترجمة جاهزة قابلة للجلب. جارٍ تجربة استخراج الصوت…"
                        // Fallback: load the video in Sari's WebView and attempt captureStream.
                        if (!sameVideoUrl(videoUrl, webView.url.orEmpty())) {
                            pendingAutoCaptureUrl = videoUrl
                            webView.loadUrl(videoUrl)
                        } else {
                            pendingAutoCaptureUrl = null
                            handler.postDelayed({ if (!isFinishing) transcribeCurrentWebVideo() }, 700L)
                        }
                        android.util.Log.i("SariAutoFlow", "YouTube caption lookup failed; trying audio capture: ${e.message}")
                    }
                }
            }
        }.start()
    }

    /**
     * مسار سريع للرابط: يقبل لصق الرابط في حوار مستقل، ثم ينسخه ويفتح SubWhisper.
     * لا ندّعي استخراج الصوت تلقائياً داخل التطبيق؛ الروابط الاجتماعية تحتاج عادةً
     * إلى خدمة استخراج خارجية، وقد تتطلب تسجيل الدخول أو لا تكون مدعومة.
     */
    private fun openPasteLinkFlow() {
        // Hard guard: the internal processing engine must never route to SubWhisper,
        // even if this legacy/paste-link button is tapped directly.
        if (settings.processingEngine == SubtitleSettings.ENGINE_INTERNAL) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("محرك Sari Subtitle الداخلي")
                .setMessage("المحرك الداخلي لا يفتح SubWhisper. اختر ملف صوت/فيديو للتفريغ داخل التطبيق، أو اختر ملف SRT لترجمته.")
                .setPositiveButton("اختيار صوت أو فيديو") { _, _ -> openVideoPickerForAutoTranscription() }
                .setNeutralButton("ترجمة ملف SRT") { _, _ -> findViewById<Button>(R.id.translateSrtButton)?.performClick() }
                .setNegativeButton("إلغاء", null)
                .show()
            return
        }
        val clipboardText = try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip() && (clipboard.primaryClip?.itemCount ?: 0) > 0) {
                clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
            } else ""
        } catch (_: Exception) { "" }
        val clipboardUrl = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
            .find(clipboardText)?.value?.trimEnd('.', ',', ')', ']', '}', '،').orEmpty()

        val linkInput = EditText(this).apply {
            hint = "الصق رابط الفيديو هنا"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            val existingUrl = urlInput.text.toString().trim().takeUnless { it == "https://x.com/" }.orEmpty()
            setText(clipboardUrl.ifBlank { existingUrl })
            setSelection(text.length)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 12, 40, 0)
            addView(linkInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(TextView(this@SariSubtitleActivity).apply {
                text = "سيُفتح موقع SubWhisper داخل Sari Subtitle دون الانتقال إلى تطبيق آخر. راجع الرابط واضغط إنشاء الترجمة في الصفحة؛ عند تنزيل SRT من الصفحة سيحاول التطبيق استيراده تلقائياً. قد لا تعمل الروابط المحمية أو غير المدعومة."
                setPadding(0, 12, 0, 0)
            })
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("الصق الرابط فقط")
            .setView(container)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("متابعة داخل التطبيق") { _, _ ->
                val link = linkInput.text.toString().trim()
                val uri = try { Uri.parse(link) } catch (_: Exception) { null }
                if (link.isBlank() || uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
                    Toast.makeText(this, "ألصق رابط فيديو صحيحاً يبدأ بـ http أو https", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                try {
                    urlInput.setText(link)
                    statusText.text = "تم فتح SubWhisper داخل Sari Subtitle. ألصق/راجع الرابط واضغط إنشاء الترجمة داخل الصفحة."
                    val internalIntent = Intent(this, SubWhisperInternalActivity::class.java)
                        .putExtra(SubWhisperInternalActivity.EXTRA_INITIAL_URL, link)
                    startActivityForResult(internalIntent, REQ_SUBWHISPER_INTERNAL)
                } catch (e: Exception) {
                    Toast.makeText(this, "تعذّر فتح نافذة التفريغ الداخلية: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private data class LanguageOption(val code: String, val label: String) {
        override fun toString(): String = label
    }

    private fun showSrtTranslationDialog() {
        val languages = listOf(
            LanguageOption("ar", "العربية"), LanguageOption("en", "الإنجليزية"),
            LanguageOption("es", "الإسبانية"), LanguageOption("fr", "الفرنسية"),
            LanguageOption("de", "الألمانية"), LanguageOption("tr", "التركية"),
            LanguageOption("ru", "الروسية"), LanguageOption("it", "الإيطالية"),
            LanguageOption("pt", "البرتغالية"), LanguageOption("zh-CN", "الصينية"),
            LanguageOption("ja", "اليابانية"), LanguageOption("ko", "الكورية"),
            LanguageOption("fa", "الفارسية"), LanguageOption("ur", "الأوردية"),
            LanguageOption("hi", "الهندية")
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }
        root.addView(TextView(this).apply { text = "لغة الملف الأصلية" })
        val sourceSpinner = Spinner(this)
        sourceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages)
        sourceSpinner.setSelection(languages.indexOfFirst { it.code == "en" })
        root.addView(sourceSpinner)
        root.addView(TextView(this).apply { text = "ترجمة الملف إلى"; setPadding(0, 18, 0, 0) })
        val targetSpinner = Spinner(this)
        targetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages)
        targetSpinner.setSelection(languages.indexOfFirst { it.code == "ar" })
        root.addView(targetSpinner)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("ترجمة ملف SRT")
            .setMessage("سيتم إرسال نص المقاطع إلى خدمة ترجمة عبر الإنترنت. أرقام المقاطع وتوقيتاتها تبقى كما هي. هذه الخدمة ليست SubWhisper، لكنها تتطلب الإنترنت وقد تفرض حدود استخدام.")
            .setView(root)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("اختيار ملف SRT") { _, _ ->
                pendingSourceLang = (sourceSpinner.selectedItem as LanguageOption).code
                pendingTargetLang = (targetSpinner.selectedItem as LanguageOption).code
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    setType("*/*")
                }
                startActivityForResult(Intent.createChooser(intent, "اختر ملف SRT لترجمته"), REQ_TRANSLATE_SRT_PICK)
            }
            .show()
    }

    private fun translateSelectedSrt(uri: Uri) {
        val translationStarted = android.os.SystemClock.elapsedRealtime()
        val raw = try {
            contentResolver.openInputStream(uri)?.use { SrtParser.decode(it) }
        } catch (e: Exception) { null }
        if (raw.isNullOrBlank()) {
            Toast.makeText(this, "تعذّر قراءة ملف SRT", Toast.LENGTH_LONG).show()
            return
        }
        val cancellation = OperationCancellation()
        beginTrackedOperation(cancellation)
        val progress = ProgressDialog(this).apply {
            setTitle("ترجمة ملف SRT")
            setMessage("جارٍ بدء الترجمة…")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            setButton(ProgressDialog.BUTTON_NEGATIVE, "إلغاء") { _, _ -> cancellation.cancel() }
            show()
            getButton(ProgressDialog.BUTTON_NEGATIVE)?.setOnClickListener { cancellation.cancel(); setMessage("جارٍ إلغاء الترجمة…") }
        }
        val worker = Thread {
            try {
                cancellation.check()
                val translated = SrtTranslationService.translateSrt(raw, pendingSourceLang, pendingTargetLang, applicationContext,
                    shouldCancel = { cancellation.isCancelled() }, onProgress = { done, total ->
                    runOnUiThread { if (!cancellation.isCancelled()) progress.setMessage("جارٍ ترجمة المقاطع: $done / $total") }
                })
                cancellation.check()
                pendingTranslatedSrt = translated
                recordOperation("ترجمة ملف SRT", "SrtTranslationService", translationRouteLabel(), translationStarted,
                    "نجاح", "${SrtParser.parse(translated.byteInputStream(Charsets.UTF_8)).size} مقطع",
                    "هذا هو المسار المختار وليس ادعاءً بنجاح مزود بعينه؛ راجع أحداث «محاولة مزود ترجمة SRT» لمعرفة المزود الفعلي وأسباب الفشل.")
                runOnUiThread {
                    progress.dismiss()
                    if (cancellation.isCancelled()) { pendingTranslatedSrt = null; Toast.makeText(this, "تم إلغاء العملية", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                    val create = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        setType("application/x-subrip")
                        putExtra(Intent.EXTRA_TITLE, "translated_${pendingTargetLang}.srt")
                    }
                    startActivityForResult(create, REQ_SAVE_TRANSLATED_SRT)
                }
            } catch (e: Exception) {
                recordOperation("ترجمة ملف SRT", "SrtTranslationService", translationRouteLabel(), translationStarted,
                    if (cancellation.isCancelled() || e is java.util.concurrent.CancellationException) "أُلغيت" else "فشل",
                    "", (e.message ?: "خطأ غير معروف").take(400))
                runOnUiThread {
                    progress.dismiss()
                    pendingTranslatedSrt = null
                    if (cancellation.isCancelled() || e is java.util.concurrent.CancellationException) { Toast.makeText(this, "تم إلغاء العملية", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("تعذرت الترجمة")
                        .setMessage("${e.message ?: "خطأ غير معروف"}\n\nتحقق من الإنترنت ورمز اللغة وحدود الخدمة المجانية، ثم حاول مرة أخرى.")
                        .setPositiveButton("حسنًا", null)
                        .show()
                }
            }
        }.also { cancellation.worker = it; it.start() }
    }

    private fun saveTranslatedSrt(uri: Uri) {
        val translated = pendingTranslatedSrt ?: run {
            Toast.makeText(this, "لا يوجد ملف مترجم للحفظ", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(translated.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException("تعذّر فتح الملف للحفظ")
            // This legacy export flow previously saved only the user-selected destination.
            // Keep an internal copy too, so the translated SRT remains available in My Files.
            val cues = try { SrtParser.parse(translated.byteInputStream(Charsets.UTF_8)) } catch (_: Exception) { emptyList() }
            val librarySaved = cues.isNotEmpty() && storage.saveLibraryItem(
                "translated_${pendingTargetLang}_${System.currentTimeMillis()}.srt",
                translated,
                cues.size
            ) != null
            pendingTranslatedSrt = null
            val message = if (librarySaved) {
                "تم حفظ ملف SRT المترجم في المكان المحدد وداخل «ملفاتي» مع الحفاظ على التوقيتات."
            } else {
                "تم تصدير ملف SRT المترجم، لكن تعذّر حفظ نسخة داخل «ملفاتي»."
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "فشل حفظ الملف: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** A single entry point for subtitle files: translate or display, with no provider settings. */
    private fun showSubtitleFileActions() {
        val actions = arrayOf("ترجمة الملف إلى العربية تلقائيًا", "عرض ملف الترجمة على الفيديو")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("ماذا تريد أن تفعل؟")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            setType("*/*")
                        }
                        startActivityForResult(
                            Intent.createChooser(intent, "اختر ملف SRT للترجمة"),
                            REQ_PICK_SRT_TO_TRANSLATE
                        )
                    }
                    1 -> openSrtPicker()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun openSrtPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            setType("*/*")
        }
        try {
            startActivityForResult(
                Intent.createChooser(intent, "اختر ملف الترجمة (.srt)"),
                REQ_PICK_SRT
            )
        } catch (e: Exception) {
            Toast.makeText(this, "تعذّر فتح مدير الملفات", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * سير تلقائي لملف SRT:
     * - يتحقق من صحة الملف.
     * - إن كان عربيًا، يحمّله مباشرة للعرض.
     * - إن كانت لغته مختلفة، يترجمه للعربية، ثم يعرضه ويطلب مكان حفظ نسخة قابلة للمشاركة.
     */
    private fun processSmartSrt(uri: Uri) {
        val raw = try {
            contentResolver.openInputStream(uri)?.use { SrtParser.decode(it) }
        } catch (_: Exception) { null }
        if (raw.isNullOrBlank()) {
            Toast.makeText(this, "تعذّر قراءة الملف. اختر ملف SRT صالحًا.", Toast.LENGTH_LONG).show()
            return
        }
        val cues = try { SrtParser.parse(raw.byteInputStream(Charsets.UTF_8)) }
        catch (_: Exception) { emptyList() }
        if (cues.isEmpty()) {
            Toast.makeText(this, "هذا الملف لا يحتوي على مقاطع SRT صالحة.", Toast.LENGTH_LONG).show()
            return
        }
        val firstText = cues.firstOrNull { it.text.isNotBlank() }?.text.orEmpty()
        // Arabic script can be detected locally without network access.
        val hasArabicScript = Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF]").containsMatchIn(firstText)
        if (hasArabicScript) {
            repo.setCues(cues)
            lastSrtText = raw
            val videoUrl = currentVideoUrlForCache()
            val cacheSaved = videoUrl != null && saveCachedSrt(videoUrl, raw, cues.size)
            lastSrtUrl = videoUrl
            overlayOn = true
            val sourceName = getSrtDisplayName(uri).ifBlank { "ترجمة مستوردة.srt" }
            val libraryId = storage.saveLibraryItem(
                sourceName, raw, cues.size, videoUrl,
                sourceTitle = currentVideoTitle()
            )
            val librarySaved = libraryId != null
            if (libraryId != null) offerLocalVideoBinding(libraryId, "هل تريد ربط هذا SRT بفيديو محلي الآن؟")
            statusText.visibility = android.view.View.VISIBLE
            statusText.text = when {
                cacheSaved && librarySaved -> "تم تحميل الترجمة العربية: ${cues.size} مقطع، وحُفظت للاستعادة التلقائية وفي «ملفاتي»."
                librarySaved -> "تم تحميل الترجمة العربية: ${cues.size} مقطع، وحُفظت في «ملفاتي» فقط."
                cacheSaved -> "تم تحميل الترجمة العربية: ${cues.size} مقطع، وحُفظت للاستعادة التلقائية."
                else -> "تم تحميل الترجمة العربية: ${cues.size} مقطع، لكن تعذّر تأكيد حفظها."
            }
            Toast.makeText(this, if (cacheSaved || librarySaved) "تم تحميل الترجمة وحفظها" else "تم تحميل الترجمة لكن تعذّر حفظها", Toast.LENGTH_SHORT).show()
            pollCurrentTimeOnce()
        } else {
            statusText.visibility = android.view.View.VISIBLE
            statusText.text = "تم اختيار ملف الترجمة. جارٍ تحديد اللغة وترجمته للعربية…"
            processTranslateSrt(uri)
        }
    }

    private fun getSrtDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }.orEmpty().ifBlank { uri.lastPathSegment.orEmpty().substringAfterLast('/') }
        } catch (_: Exception) { uri.lastPathSegment.orEmpty().substringAfterLast('/') }
    }

    /**
     * ترجمة SRT تلقائية: يكتشف اللغة، يحفظ الناتج داخل مكتبة التطبيق، ويعرضه فورًا.
     * تصدير نسخة إلى مجلد الهاتف اختياري بعد اكتمال العملية، وليس خطوة إلزامية.
     */
    private fun processTranslateSrt(uri: Uri) {
        val raw = try {
            contentResolver.openInputStream(uri)?.use { SrtParser.decode(it) }
        } catch (e: Exception) { null }

        if (raw.isNullOrBlank()) {
            Toast.makeText(this, "تعذّر قراءة ملف SRT", Toast.LENGTH_LONG).show()
            return
        }

        val cancellation = OperationCancellation()
        beginTrackedOperation(cancellation)
        val cancelRequested = java.util.concurrent.atomic.AtomicBoolean(false)
        val progress = ProgressDialog(this).apply {
            setTitle("ترجمة ملف الترجمة")
            setMessage("جارٍ اكتشاف اللغة…")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            setButton(ProgressDialog.BUTTON_NEGATIVE, "إلغاء", android.content.DialogInterface.OnClickListener { _, _ -> })
            show()
        }
        // Override the default button dismissal so the user can see that the current
        // network request may need to finish before cancellation takes effect.
        progress.getButton(ProgressDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            cancelRequested.set(true)
            cancellation.cancel()
            progress.getButton(ProgressDialog.BUTTON_NEGATIVE)?.isEnabled = false
            progress.setMessage("جارٍ الإيقاف بعد انتهاء طلب الترجمة الجاري…")
        }

        val worker = Thread {
            try {
                cancellation.check()
                val translated = SrtTranslationService.translateAuto(
                    source = raw,
                    targetLang = "ar",
                    onDetected = { lang ->
                        runOnUiThread { if (!cancelRequested.get()) progress.setMessage("اللغة المكتشفة: $lang\nجارٍ الترجمة إلى العربية…") }
                    },
                    context = applicationContext,
                    onProgress = { done, total ->
                        runOnUiThread { if (!cancelRequested.get()) progress.setMessage("جارٍ ترجمة المقاطع: $done / $total") }
                    },
                    shouldCancel = { cancelRequested.get() || cancellation.isCancelled() }
                )
                cancellation.check()
                if (cancelRequested.get()) throw java.util.concurrent.CancellationException("ألغى المستخدم العملية")

                pendingTranslatedSrt = translated
                pendingTargetLang = "ar"
                val translatedCues = SrtParser.parse(translated.byteInputStream(Charsets.UTF_8))
                if (translatedCues.isEmpty()) throw IllegalStateException("لم ينتج ملف SRT صالح بعد الترجمة")

                runOnUiThread {
                    progress.dismiss()
                    if (cancelRequested.get() || cancellation.isCancelled()) { pendingTranslatedSrt = null; statusText.text = "تم إلغاء العملية"; Toast.makeText(this, "تم إلغاء العملية", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                    repo.setCues(translatedCues)
                    lastSrtText = translated
                    overlayOn = true
                    val currentUrl = currentVideoUrlForCache()
                    val cacheSaved = currentUrl != null && saveCachedSrt(currentUrl, translated, translatedCues.size)
                    lastSrtUrl = currentUrl
                    val originalName = getSrtDisplayName(uri).ifBlank { "subtitle.srt" }
                    val translatedName = "ar_${originalName.substringAfterLast('/').removeSuffix(".srt").removeSuffix(".SRT")}.srt"
                    val libraryId = storage.saveLibraryItem(
                        translatedName, translated, translatedCues.size, currentUrl,
                        sourceTitle = currentVideoTitle(),
                        preserveVersion = true
                    )
                    val librarySaved = libraryId != null
                    if (libraryId != null) offerLocalVideoBinding(libraryId, "هل تريد ربط الترجمة بفيديو محلي الآن؟")
                    if (!librarySaved) android.util.Log.w("SariSrt", "Could not persist translated SRT in local library")
                    statusText.visibility = android.view.View.VISIBLE
                    statusText.text = when {
                        cacheSaved && librarySaved -> "اكتملت الترجمة العربية: ${translatedCues.size} مقطع؛ حُفظت للاستعادة التلقائية وفي «ملفاتي»."
                        cacheSaved -> "اكتملت الترجمة العربية: ${translatedCues.size} مقطع؛ حُفظت للاستعادة التلقائية."
                        librarySaved -> "اكتملت الترجمة العربية: ${translatedCues.size} مقطع؛ حُفظت في «ملفاتي» فقط."
                        else -> "اكتملت الترجمة العربية: ${translatedCues.size} مقطع، لكن تعذّر حفظها. صدّر نسخة للاحتفاظ بها."
                    }
                    pollCurrentTimeOnce()
                    Toast.makeText(this, if (cacheSaved || librarySaved) "اكتملت الترجمة وحُفظت داخل التطبيق" else "اكتملت الترجمة لكن تعذّر حفظها", Toast.LENGTH_LONG).show()
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("اكتملت الترجمة")
                        .setMessage("تمت ترجمة ${translatedCues.size} مقطعًا، وحُفظ الملف داخل التطبيق. يمكنك فتحه لاحقًا من «ملفاتي». هل تريد أيضًا تصدير نسخة إلى مجلد الهاتف؟")
                        .setNegativeButton("ليس الآن", null)
                        .setNeutralButton("ترجمة مرة أخرى") { _, _ -> processTranslateSrt(uri) }
                        .setPositiveButton("تصدير نسخة") { _, _ ->
                            val create = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                setType("application/x-subrip")
                                putExtra(Intent.EXTRA_TITLE, translatedName)
                            }
                            startActivityForResult(create, REQ_SAVE_TRANSLATED_SRT)
                        }
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.dismiss()
                    if (cancelRequested.get() || e is java.util.concurrent.CancellationException) {
                        statusText.visibility = android.view.View.VISIBLE
                        statusText.text = "أُلغيت ترجمة الملف. الملف الأصلي لم يتغير."
                        Toast.makeText(this, "تم إلغاء الترجمة", Toast.LENGTH_SHORT).show()
                    } else {
                        val detail = when {
                            e.message.orEmpty().contains("429") || e.message.orEmpty().contains("quota", true) || e.message.orEmpty().contains("rate limit", true) -> "يبدو أن حصة خدمة الترجمة انتهت أو أن الطلبات كثيرة. انتظر قليلًا ثم أعد المحاولة."
                            e.message.orEmpty().contains("401") || e.message.orEmpty().contains("403") || e.message.orEmpty().contains("API key", true) || e.message.orEmpty().contains("مفتاح", true) -> "تعذّر التحقق من بيانات خدمة الترجمة. راجع إعدادات المفاتيح أو جرّب مزودًا متاحًا."
                            e is java.net.SocketTimeoutException || e is java.net.UnknownHostException || e.message.orEmpty().contains("connect", true) || e.message.orEmpty().contains("network", true) -> "تعذّر الاتصال بخدمة الترجمة. افحص الإنترنت ثم أعد المحاولة."
                            e.message.orEmpty().contains("SRT", true) || e.message.orEmpty().contains("مقاطع", true) -> "تعذّر قراءة بنية ملف SRT أو لم ينتج نص صالح. تأكد أن الملف يحتوي على التوقيتات والنصوص."
                            else -> "تعذّرت الترجمة: ${e.message ?: "خطأ غير معروف"}. جرّب مرة أخرى أو تحقق من اتصال الإنترنت وإعدادات المزود."
                        }
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("تعذرت الترجمة")
                            .setMessage(detail)
                            .setPositiveButton("حسنًا", null)
                            .show()
                    }
                }
            }
        }.also { cancellation.worker = it; it.start() }
    }

    // ═══════════════════════════════════════════════════════════
    // تفريغ صوت عبر مزود STT المختار (Groq أو Deepgram)
    // ═══════════════════════════════════════════════════════════

        private fun testCaptureStream() {
        // فحص دعم WebViewFeature
        val supportsArrayBuffer = WebViewFeature.isFeatureSupported(
            WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER
        )
        val supportsWebMessageListener = WebViewFeature.isFeatureSupported(
            WebViewFeature.WEB_MESSAGE_LISTENER
        )
        android.util.Log.d("SariTest", "ArrayBuffer: $supportsArrayBuffer, Listener: $supportsWebMessageListener")
        
        val js = """
            (function() {
              try {
                var v = document.querySelector('video');
                if (!v) return JSON.stringify({error: "no_video_found"});
                var r = {
                  captureStream: typeof v.captureStream,
                  MediaRecorder: typeof MediaRecorder,
                  currentTime: v.currentTime,
                  duration: v.duration,
                  paused: v.paused,
                  src: v.currentSrc ? v.currentSrc.substring(0, 50) : "none",
                  hasAudioTracks: false,
                  audioTrackCount: 0
                };
                try {
                  var s = v.captureStream();
                  if (s) {
                    var tracks = s.getAudioTracks();
                    r.audioTrackCount = tracks.length;
                    r.hasAudioTracks = tracks.length > 0;
                  }
                } catch(e) {
                  r.captureError = String(e);
                }
                return JSON.stringify(r);
              } catch(e) {
                return JSON.stringify({error: String(e)});
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { raw ->
            val decoded = try {
                org.json.JSONTokener(raw ?: "null").nextValue().toString()
            } catch (_: Exception) { raw ?: "null" }
            runOnUiThread {
                android.app.AlertDialog.Builder(this)
                    .setTitle("فحص captureStream + WebView")
                    .setMessage(
                        "دعم WebView:\n" +
                        "• ArrayBuffer: $supportsArrayBuffer\n" +
                        "• WebMessageListener: $supportsWebMessageListener\n\n" +
                        "captureStream:\n" +
                        decoded
                    )
                    .setPositiveButton("حسناً", null)
                    .show()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // تفريغ فيديو الصفحة الحالية: captureStream + MediaRecorder + مزود STT المختار
    // يعمل فقط عندما تسمح المنصة وWebView بالتقاط مسار الصوت.
    // ═══════════════════════════════════════════════════════════
    
    private fun transcribeCurrentWebVideo() {
        val currentVideoUrl = (webView.url ?: urlInput.text.toString()).trim()
        if (currentVideoUrl.startsWith("http") && restoreCachedSrt(currentVideoUrl)) {
            statusText.visibility = android.view.View.VISIBLE
            statusText.text = "تمت استعادة الترجمة المحفوظة. لم تتم إعادة استخراج الصوت أو استدعاء مزود STT."
            Toast.makeText(this, "تم تحميل الترجمة المحفوظة دون إعادة المعالجة", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "🔍 فحص الفيديو…", Toast.LENGTH_SHORT).show()
        statusText.text = "🔍 فحص الفيديو…"
        statusText.visibility = android.view.View.VISIBLE
        
        val js = """
            (function() {
              try {
                // اختيار الفيديو المرئي (وليس المخفي)
                var all = Array.from(document.querySelectorAll('video'));
                if (!all.length) return JSON.stringify({error: "no_video"});
                
                // الأكبر مساحة (المرئي)
                var best = all[0];
                var maxArea = 0;
                for (var i = 0; i < all.length; i++) {
                  var r = all[i].getBoundingClientRect();
                  var area = r.width * r.height;
                  if (area > maxArea) {
                    maxArea = area;
                    best = all[i];
                  }
                }
                
                var v = best;
                window.__sariSelectedVideo = v;
                
                return JSON.stringify({
                  hasVideo: true,
                  videoCount: all.length,
                  paused: v.paused,
                  muted: v.muted,
                  volume: v.volume,
                  duration: v.duration || 0,
                  videoWidth: v.videoWidth,
                  videoHeight: v.videoHeight,
                  canCapture: typeof v.captureStream === 'function',
                  canRecord: typeof MediaRecorder !== 'undefined'
                });
              } catch(e) {
                return JSON.stringify({error: String(e)});
              }
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(js) { raw ->
            val decoded = try {
                org.json.JSONTokener(raw ?: "null").nextValue().toString()
            } catch (_: Exception) { "null" }
            runOnUiThread {
                try {
                    val obj = org.json.JSONObject(decoded)
                    if (obj.has("error")) {
                        showManualVideoFallback()
                        return@runOnUiThread
                    }
                    val canCapture = obj.optBoolean("canCapture", false)
                    val canRecord = obj.optBoolean("canRecord", false)
                    val duration = obj.optDouble("duration", 0.0)
                    
                    if (!canCapture || !canRecord) {
                        showManualVideoFallback()
                        return@runOnUiThread
                    }
                    
                    if (duration > 120.0) {
                        Toast.makeText(this, "سيتم التقاط أول دقيقتين فقط", Toast.LENGTH_LONG).show()
                    }
                    
                    startCurrentWebVideoRecording(duration)
                } catch (e: Exception) {
                    Toast.makeText(this, "خطأ: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun startCurrentWebVideoRecording(duration: Double) {
        // Unknown/infinite duration is common for live or segmented web media. Keep a bounded
        // capture window instead of passing 0 to setTimeout (which would stop immediately).
        val safeDurationSeconds = if (duration.isFinite() && duration > 0.0) duration else 120.0
        val recordDurationMs = minOf(safeDurationSeconds * 1000.0, 120000.0).toInt().coerceAtLeast(1000)
        val progress = android.app.ProgressDialog(this).apply {
            setTitle("🎙️ تسجيل الصوت")
            setMessage("جارٍ التسجيل…")
            setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }
        
        val startTime = System.currentTimeMillis()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val timer = object : Runnable {
            override fun run() {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                progress.setMessage("🎙️ جارٍ التسجيل…\nمضى: ${elapsed}s")
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(timer)
        
        val js = """
            (async function() {
              try {
                // 🔴 استخدم window.__sariSelectedVideo أولاً
                var v = window.__sariSelectedVideo;
                if (!v) {
                  var all = document.querySelectorAll('video');
                  if (!all.length) return JSON.stringify({error: "no_video"});
                  v = all[0];
                }
                
                // Never override the user's mute/volume preferences. captureStream reads the
                // media element's audio track; changing playback volume is intrusive and can
                // cause audible jumps during transcription.
                
                // 🔴 شغّل الفيديو
                if (v.paused) {
                  try { await v.play(); } catch(e) {}
                }
                
                // انتظر بسيط
                await new Promise(r => setTimeout(r, 500));
                
                // 🔴 captureStream
                var stream = v.captureStream();
                var audioTracks = stream.getAudioTracks();
                if (audioTracks.length === 0) {
                  return JSON.stringify({error: "no_audio_tracks"});
                }
                
                var audioStream = new MediaStream(audioTracks);
                var recorder = new MediaRecorder(audioStream, {
                  mimeType: 'audio/webm;codecs=opus',
                  audioBitsPerSecond: 128000
                });
                
                var chunks = [];
                recorder.ondataavailable = e => { if (e.data.size > 0) chunks.push(e.data); };
                
                return new Promise((resolve) => {
                  recorder.onstop = async () => {
                    var blob = new Blob(chunks, { type: 'audio/webm' });
                    if (blob.size === 0) {
                      resolve(JSON.stringify({
                        error: "empty_recording",
                        paused: v.paused,
                        muted: v.muted,
                        audioTracks: audioStream.getAudioTracks().length,
                        chunksCount: chunks.length
                      }));
                      return;
                    }
                    var reader = new FileReader();
                    reader.onload = () => resolve(JSON.stringify({
                      audio: reader.result.split(',')[1],
                      size: blob.size
                    }));
                    reader.readAsDataURL(blob);
                  };
                  recorder.start();
                  setTimeout(() => {
                    if (recorder.state !== 'inactive') recorder.stop();
                  }, ${recordDurationMs});
                });
              } catch(e) {
                return JSON.stringify({error: String(e)});
              }
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(js) { raw ->
            handler.removeCallbacks(timer)
            progress.dismiss()
            
            val decoded = try {
                org.json.JSONTokener(raw ?: "null").nextValue().toString()
            } catch (_: Exception) { raw ?: "null" }
            
            runOnUiThread {
                try {
                    val obj = org.json.JSONObject(decoded)
                    if (obj.has("error")) {
                        showManualVideoFallback()
                        return@runOnUiThread
                    }
                    if (!obj.has("audio")) {
                        showManualVideoFallback()
                        return@runOnUiThread
                    }
                    val b64 = obj.getString("audio")
                    val sizeKB = obj.optInt("size", 0) / 1024
                    Toast.makeText(this, "✅ تسجيل $sizeKB KB", Toast.LENGTH_LONG).show()
                    statusText.text = "✅ $sizeKB KB — إرسال إلى Groq…"
                    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    sendToGroq(bytes)
                } catch (e: Exception) {
                    Toast.makeText(this, "خطأ: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /** When a platform blocks WebView audio capture, guide the user to download then select the media. */
    private fun showManualVideoFallback() {
        if (isFinishing) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("تعذّر استخراج الصوت")
            .setMessage("لا يمكن استخراج الصوت من هذا الرابط. يرجى تحميل الفيديو أولاً باستخدام أداة التنزيل، ثم اختياره من هاتفك. بعد اختياره سيحاول التطبيق تفريغ الصوت ثم ترجمته إلى العربية تلقائياً.")
            .setNegativeButton("إلغاء", null)
            .setNeutralButton("اختيار ملف صوت أو فيديو") { _, _ -> openVideoPickerForAutoTranscription() }
            .setPositiveButton("فتح أداة التنزيل") { _, _ -> openChromeForDownload() }
            .show()
    }

    private fun openVideoPickerForAutoTranscription() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "اختر ملف صوت أو فيديو"), REQ_PICK_VIDEO_FOR_AUTO)
        } catch (_: Exception) {
            Toast.makeText(this, "تعذّر فتح مدير الملفات", Toast.LENGTH_LONG).show()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) { null }
    }

    /** Transcribes a selected local audio or video file, then translates the generated SRT to Arabic. */
    private fun transcribeDownloadedVideo(uri: Uri) {
        if (!hasSelectedSttKey()) {
            Toast.makeText(this, "أضف مفتاح Groq أو ثبّت نموذج Vosk المحلي من الإعدادات أولاً", Toast.LENGTH_LONG).show()
            return
        }
        // Capture source identity before background transcription so navigation during processing cannot rebind the SRT.
        val sourceForTranscription = resolvePickedMediaSource(uri)
        val sourceDisplayName = queryDisplayName(uri)?.takeIf { it.isNotBlank() }
        val cancellation = OperationCancellation()
        beginTrackedOperation(cancellation)
        val progress = ProgressDialog(this).apply {
            setTitle("إنشاء الترجمة العربية")
            setMessage("جارٍ تجهيز الملف الصوتي…")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            setButton(ProgressDialog.BUTTON_NEGATIVE, "إلغاء") { _, _ -> cancellation.cancel() }
            show()
            getButton(ProgressDialog.BUTTON_NEGATIVE)?.setOnClickListener { cancellation.cancel(); setMessage("جارٍ إيقاف استخراج الصوت والتفريغ…") }
        }
        statusText.visibility = android.view.View.VISIBLE
        statusText.text = "جارٍ تفريغ الملف الصوتي أو الفيديو ثم ترجمته إلى العربية…"
        val worker = Thread {
            var extractedAudio: java.io.File? = null
            try {
                cancellation.check()
                val mimeType = contentResolver.getType(uri).orEmpty().lowercase(java.util.Locale.ROOT)
                val displayName = queryDisplayName(uri).orEmpty().lowercase(java.util.Locale.ROOT)
                val isAudioFile = mimeType.startsWith("audio/") ||
                    displayName.substringAfterLast('.', "") in setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "amr", "3gp")
                val selectedIsLocal = settings.sttProvider == SubtitleSettings.STT_LOCAL
                val cloudKeyAvailable = settings.groqApiKey.isNotBlank() || settings.deepgramApiKey.isNotBlank() || settings.openAiApiKey.isNotBlank()
                // Audio files (MP3/M4A/WAV, etc.) go directly to the selected transcriber;
                // only video files need their audio track extracted for cloud STT.
                val result = if (isAudioFile || selectedIsLocal || !cloudKeyAvailable) {
                    transcribeWithSelectedProvider(uri, "auto")
                } else {
                    try {
                        extractedAudio = extractAudioTrackForCloud(uri)
                        transcribeWithSelectedProvider(Uri.fromFile(extractedAudio!!), "auto")
                    } catch (cloudPrepError: Exception) {
                        cancellation.check()
                        val hasLocalModel = VoskModelManager.isInstalled(applicationContext, VoskModelManager.ARABIC) ||
                            VoskModelManager.isInstalled(applicationContext, VoskModelManager.ENGLISH)
                        if (!hasLocalModel) throw cloudPrepError
                        runOnUiThread { if (!isFinishing) progress.setMessage("تعذّر تجهيز الصوت للخدمة السحابية؛ جارٍ تجربة Vosk المحلي…") }
                        val local = OfflineVoskTranscriber(applicationContext).transcribe(uri, "auto") { message ->
                            runOnUiThread { if (!isFinishing) progress.setMessage(message) }
                        }
                        SelectedTranscriptionResult(local.language, local.text, local.srt, local.segmentCount)
                    }
                }
                cancellation.check()
                val rawSrt = result.srt
                if (SrtParser.parse(rawSrt.byteInputStream(Charsets.UTF_8)).isEmpty()) {
                    throw IllegalStateException("لم ينتج التفريغ ملف SRT صالحاً")
                }
                runOnUiThread {
                    if (isFinishing || cancellation.isCancelled()) { progress.dismiss(); Toast.makeText(this, "تم إلغاء العملية", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                    progress.setMessage("اكتمل التفريغ — جارٍ ترجمة SRT إلى العربية…")
                    val sourceName = sourceDisplayName ?: "downloaded-media-${System.currentTimeMillis()}.srt"
                    translateAndShowSrtAutomatically(
                        sourceForTranscription, rawSrt, sourceName,
                        localVideoUri = uri.toString(),
                        sourceTitle = sourceDisplayName?.removeSuffix(".mp4")?.removeSuffix(".mkv")?.removeSuffix(".mov")
                    )
                    progress.dismiss()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        progress.dismiss()
                        if (cancellation.isCancelled() || e is java.util.concurrent.CancellationException) { statusText.text = "تم إلغاء العملية"; Toast.makeText(this, "تم إلغاء العملية", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("تعذّر إنشاء الترجمة")
                            .setMessage("${e.message ?: "حدث خطأ أثناء تفريغ الفيديو"}\n\nتأكد من أن الفيديو يحتوي على صوت، وأن مفتاح Groq صالح أو نموذج Vosk المناسب مثبت.")
                            .setPositiveButton("اختيار ملف آخر") { _, _ -> openVideoPickerForAutoTranscription() }
                            .setNegativeButton("إغلاق", null)
                            .show()
                    }
                }
            } finally {
                extractedAudio?.delete()
            }
        }.also { cancellation.worker = it; it.start() }
    }

    /** Remux the compressed audio track from a local video into an M4A container for cloud STT. */
    private fun extractAudioTrackForCloud(uri: Uri): java.io.File {
        val extractor = android.media.MediaExtractor()
        val output = java.io.File.createTempFile("sari_audio_", ".m4a", cacheDir)
        var muxer: android.media.MediaMuxer? = null
        try {
            extractor.setDataSource(this, uri, null)
            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) { audioTrack = i; break }
            }
            if (audioTrack < 0) throw IllegalArgumentException("لم يتم العثور على مسار صوت داخل الفيديو")
            extractor.selectTrack(audioTrack)
            muxer = android.media.MediaMuxer(output.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrack = muxer.addTrack(extractor.getTrackFormat(audioTrack))
            muxer.start()
            val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("تم إلغاء العملية")
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrack, buffer, info)
                extractor.advance()
            }
            muxer.stop()
            if (output.length() <= 0L) throw IllegalStateException("تعذّر استخراج الصوت من الفيديو")
            return output
        } catch (e: Exception) {
            output.delete()
            throw IllegalStateException("تعذّر تجهيز مسار الصوت لإرساله إلى مزود التفريغ: ${e.message}", e)
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun sendToGroq(audioBytes: ByteArray) {
        if (!hasSelectedSttKey()) {
            Toast.makeText(this, "جهّز مزود التفريغ من الإعدادات (مفتاح API أو نموذج Vosk المحلي) أولاً", Toast.LENGTH_LONG).show()
            return
        }

        val cancellation = OperationCancellation()
        beginTrackedOperation(cancellation)
        val progress = android.app.ProgressDialog(this).apply {
            setTitle("🌐 ${settings.sttProviderLabel}")
            setMessage("جارٍ التفريغ…")
            setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "إلغاء") { _, _ -> cancellation.cancel() }
            show()
            getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.setOnClickListener { cancellation.cancel(); setMessage("جارٍ إلغاء التفريغ…") }
        }

        val startTime = System.currentTimeMillis()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val timer = object : Runnable {
            override fun run() {
                if (cancellation.isCancelled()) return
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                progress.setMessage("🌐 جارٍ التفريغ…\nمضى: ${elapsed}s")
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(timer)

        val worker = Thread {
            try {
                cancellation.check()
                val tempFile = java.io.File(cacheDir, "x_audio_${System.currentTimeMillis()}.webm")
                tempFile.writeBytes(audioBytes)

                val result = try {
                    transcribeWithSelectedProvider(android.net.Uri.fromFile(tempFile), "auto")
                } finally {
                    tempFile.delete()
                }

                cancellation.check()
                handler.removeCallbacks(timer)
                runOnUiThread {
                    progress.dismiss()
                    if (cancellation.isCancelled()) { Toast.makeText(this, "تم إلغاء العملية", Toast.LENGTH_SHORT).show(); statusText.text = "تم إلغاء العملية"; return@runOnUiThread }
                    try {
                        val cues = SrtParser.parse(result.srt.byteInputStream())
                        if (cues.isEmpty()) {
                            Toast.makeText(this, "لم يتم استخراج مقاطع", Toast.LENGTH_LONG).show()
                            return@runOnUiThread
                        }
                        
                        // 🔵 الترجمة التلقائية للعربية
                        val detectedLang = result.language.lowercase().take(2)
                        val isArabic = detectedLang == "ar"
                        val deepLKey = settings.deepLApiKey
                        // Keep the existing Stage 20 processing/cache path; route only text translation.
                        val useTranslationRouter = settings.geminiApiKey.isNotBlank() || settings.omniRouteEnabled ||
                            settings.translationProvider == SubtitleSettings.TRANSLATION_OMNIROUTE
                        val useDeepL = !useTranslationRouter && deepLKey.isNotBlank()
                        
                        if (!isArabic && detectedLang.isNotBlank() && detectedLang != "au") {
                            // Gemini أولاً للترجمة النصية إذا كان مفتاحه محفوظاً، ثم DeepL/Google/MyMemory.
                            val provider = when {
                                useTranslationRouter -> translationRouteLabel()
                                useDeepL -> "DeepL"
                                else -> "Google/MyMemory"
                            }
                            val translationCancellation = OperationCancellation()
                            beginTrackedOperation(translationCancellation)
                            val progress2 = android.app.ProgressDialog(this).apply {
                                setTitle("🌐 ترجمة → العربية ($provider)")
                                setMessage("جارٍ الترجمة…")
                                setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
                                setCancelable(false)
                                max = 100
                                setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "إلغاء") { _, _ -> translationCancellation.cancel() }
                                show()
                                getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.setOnClickListener { translationCancellation.cancel(); setMessage("جارٍ إلغاء الترجمة…") }
                            }
                            
                            val translationWorker = Thread {
                                try {
                                    translationCancellation.check()
                                    val translatedSrt = if (useTranslationRouter) {
                                        SrtTranslationService.translateSrt(
                                            source = result.srt,
                                            sourceLang = detectedLang,
                                            targetLang = "ar",
                                            context = applicationContext,
                                            shouldCancel = { translationCancellation.isCancelled() }
                                        ) { done, total ->
                                            runOnUiThread {
                                                progress2.progress = if (total > 0) (done * 100 / total) else 100
                                                progress2.setMessage("$provider: $done / $total")
                                            }
                                        }
                                    } else if (useDeepL) {
                                        DeepLTranslationService.translateSrt(
                                            source = result.srt,
                                            sourceLang = detectedLang,
                                            targetLang = "ar",
                                            apiKey = deepLKey
                                        ) { done, total ->
                                            runOnUiThread {
                                                progress2.progress = (done * 100 / total)
                                                progress2.setMessage("$provider: $done / $total")
                                            }
                                        }
                                    } else {
                                        SrtTranslationService.translateSrt(
                                            source = result.srt,
                                            sourceLang = detectedLang,
                                            targetLang = "ar",
                                            context = applicationContext,
                                            shouldCancel = { translationCancellation.isCancelled() }
                                        ) { done, total ->
                                            runOnUiThread {
                                                progress2.progress = (done * 100 / total)
                                                progress2.setMessage("$provider: $done / $total")
                                            }
                                        }
                                    }
                                    
                                    translationCancellation.check()
                                    val translatedCues = SrtParser.parse(translatedSrt.byteInputStream())
                                    runOnUiThread {
                                        progress2.dismiss()
                                        if (translationCancellation.isCancelled()) { Toast.makeText(this, "تم إلغاء العملية", Toast.LENGTH_SHORT).show(); statusText.text = "تم إلغاء العملية"; return@runOnUiThread }
                                        repo.setCues(translatedCues)
                                        lastSrtText = translatedSrt
                                        // Bind the generated SRT to the page actually being transcribed.
                                        // The text field can contain an unsubmitted/stale URL.
                                        val videoUrlForCache = currentVideoUrlForCache().orEmpty()
                                        val cacheSaved = if (videoUrlForCache.startsWith("http://", true) || videoUrlForCache.startsWith("https://", true)) {
                                            saveCachedSrt(videoUrlForCache, translatedSrt, translatedCues.size)
                                        } else false
                                        lastSrtUrl = videoUrlForCache.takeIf { it.isNotBlank() }
                                        val librarySaved = storage.saveLibraryItem(
                                            "ar_transcription_${System.currentTimeMillis()}.srt", translatedSrt, translatedCues.size,
                                            videoUrlForCache.takeIf { it.isNotBlank() },
                                            sourceTitle = currentVideoTitle()
                                        ) != null
                                        overlayOn = true
                                        statusText.text = when {
                                            cacheSaved -> "✅ SRT عربي: ${translatedCues.size} مقطع — حُفظت الترجمة لاستعادتها تلقائيًا"
                                            librarySaved && videoUrlForCache.isNotBlank() -> "⚠️ تعذّر حفظ نسخة Smart SRT، لكن حُفظت الترجمة مع رابط الفيديو في ملفاتي ويمكن فتحها من هناك."
                                            librarySaved -> "⚠️ تمت الترجمة وحُفظ الملف في مكتبتي، لكن لم يُحدد رابط فيديو لربط الترجمة به."
                                            else -> "⚠️ تمت الترجمة، لكن تعذّر تأكيد حفظها. صدّر ملف SRT الآن للاحتفاظ بنسخة."
                                        }
                                        statusText.visibility = android.view.View.VISIBLE
                                        
                                        // إعادة تحميل الفيديو
                                        val currentUrl = webView.url ?: urlInput.text.toString().trim()
                                        if (currentUrl.startsWith("http")) {
                                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                webView.loadUrl(currentUrl)
                                            }, 500)
                                        }
                                        
                                        Toast.makeText(this, "✅ ترجمة عربية: ${translatedCues.size} مقطع", Toast.LENGTH_LONG).show()
                                    }
                                } catch (e: Exception) {
                                    runOnUiThread {
                                        progress2.dismiss()
                                        if (translationCancellation.isCancelled() || e is java.util.concurrent.CancellationException) { Toast.makeText(this, "تم إلغاء العملية", Toast.LENGTH_SHORT).show(); statusText.text = "تم إلغاء العملية"; return@runOnUiThread }
                                        // فشل الترجمة — استخدم SRT الأصلي واحتفظ بارتباطه بالصفحة الحالية.
                                        repo.setCues(cues)
                                        lastSrtText = result.srt
                                        val fallbackUrl = currentVideoUrlForCache()
                                        lastSrtUrl = fallbackUrl
                                        if (fallbackUrl != null) saveCachedSrt(fallbackUrl, result.srt, cues.size)
                                        overlayOn = true
                                        statusText.text = "⚠️ الترجمة فشلت (${e.message}) — تم الإبقاء على SRT الأصلي"
                                        
                                        Toast.makeText(this, "⚠️ الترجمة فشلت: ${e.message}", Toast.LENGTH_LONG).show()
                                        
                                        val currentUrl = webView.url ?: urlInput.text.toString().trim()
                                        if (currentUrl.startsWith("http")) {
                                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                webView.loadUrl(currentUrl)
                                            }, 500)
                                        }
                                    }
                                }
                            }.also { translationCancellation.worker = it; it.start() }
                        } else {
                            // SRT عربي بالفعل
                            repo.setCues(cues)
                            lastSrtText = result.srt
                            // Use the loaded page first so the SRT is not indexed under a stale text-field URL.
                            val videoUrlForCache = currentVideoUrlForCache()
                            val cacheSaved = videoUrlForCache != null && saveCachedSrt(videoUrlForCache, result.srt, cues.size)
                            lastSrtUrl = videoUrlForCache
                            val librarySaved = storage.saveLibraryItem(
                                "ar_transcription_${System.currentTimeMillis()}.srt", result.srt, cues.size, videoUrlForCache,
                                sourceTitle = currentVideoTitle()
                            ) != null
                            overlayOn = true
                            statusText.text = when {
                                cacheSaved && librarySaved -> "✅ SRT عربي: ${cues.size} مقطع — حُفظ للاستعادة التلقائية وفي «ملفاتي»"
                                cacheSaved -> "✅ SRT عربي: ${cues.size} مقطع — حُفظ للاستعادة التلقائية"
                                librarySaved -> "⚠️ SRT عربي: ${cues.size} مقطع — حُفظ في «ملفاتي» فقط"
                                else -> "⚠️ SRT عربي: ${cues.size} مقطع — تعذّر تأكيد الحفظ"
                            }
                            statusText.visibility = android.view.View.VISIBLE
                            
                            val currentUrl = webView.url ?: urlInput.text.toString().trim()
                            if (currentUrl.startsWith("http")) {
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    webView.loadUrl(currentUrl)
                                }, 500)
                            }
                            
                            Toast.makeText(this, "✅ SRT عربي: ${cues.size} مقطع", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, "خطأ في SRT: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                handler.removeCallbacks(timer)
                runOnUiThread {
                    progress.dismiss()
                    Toast.makeText(this, "❌ فشل التفريغ: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.also { cancellation.worker = it; it.start() }
    }

private data class SelectedTranscriptionResult(val language: String, val text: String, val srt: String, val segmentCount: Int)

    private fun hasSelectedSttKey(): Boolean {
        val hasLocalModel = VoskModelManager.isInstalled(applicationContext, VoskModelManager.ARABIC) ||
            VoskModelManager.isInstalled(applicationContext, VoskModelManager.ENGLISH)
        return when (settings.sttProvider) {
            SubtitleSettings.STT_LOCAL -> hasLocalModel
            SubtitleSettings.STT_OPENAI -> settings.openAiApiKey.isNotBlank() || hasLocalModel
            SubtitleSettings.STT_AUTO -> settings.groqApiKey.isNotBlank() || settings.deepgramApiKey.isNotBlank() || hasLocalModel
            SubtitleSettings.STT_DEEPGRAM -> settings.deepgramApiKey.isNotBlank() || settings.groqApiKey.isNotBlank() || hasLocalModel
            else -> settings.groqApiKey.isNotBlank() || settings.deepgramApiKey.isNotBlank() || hasLocalModel
        }
    }

    /** Cloud providers are tried first; installed Vosk is the no-quota local fallback. */
    private fun transcribeWithSelectedProvider(uri: Uri, language: String): SelectedTranscriptionResult {
        fun call(provider: String): SelectedTranscriptionResult = when (provider) {
            SubtitleSettings.STT_DEEPGRAM -> {
                val result = DeepgramTranscriptionService(applicationContext) { settings.deepgramApiKey }.transcribe(uri, language)
                SelectedTranscriptionResult(result.language, result.text, result.srt, result.segmentCount)
            }
            SubtitleSettings.STT_OPENAI -> {
                val result = OpenAIWhisperService(applicationContext) { settings.openAiApiKey }.transcribe(uri, language)
                SelectedTranscriptionResult(result.language, result.text, result.srt, result.segmentCount)
            }
            SubtitleSettings.STT_LOCAL -> {
                val result = OfflineVoskTranscriber(applicationContext).transcribe(uri, language) { message ->
                    runOnUiThread { if (!isFinishing) statusText.text = message }
                }
                SelectedTranscriptionResult(result.language, result.text, result.srt, result.segmentCount)
            }
            else -> {
                val result = GroqWhisperService(applicationContext) { settings.groqApiKey }.transcribe(uri, language)
                SelectedTranscriptionResult(result.language, result.text, result.srt, result.segmentCount)
            }
        }

        val selected = settings.sttProvider
        val hasLocalModel = VoskModelManager.isInstalled(applicationContext, VoskModelManager.ARABIC) ||
            VoskModelManager.isInstalled(applicationContext, VoskModelManager.ENGLISH)
        val cloudPool = when (selected) {
            SubtitleSettings.STT_LOCAL -> emptyList()
            // Stage 38: runtime routing starts from the user's selected policy, then
            // reorders only the configured cloud providers using measured health.
            SubtitleSettings.STT_AUTO -> listOf(SubtitleSettings.STT_GROQ, SubtitleSettings.STT_DEEPGRAM)
            SubtitleSettings.STT_DEEPGRAM -> listOf(SubtitleSettings.STT_DEEPGRAM, SubtitleSettings.STT_GROQ)
            SubtitleSettings.STT_GROQ -> listOf(SubtitleSettings.STT_GROQ, SubtitleSettings.STT_DEEPGRAM)
            else -> listOf(SubtitleSettings.STT_OPENAI)
        }.filter { provider -> when (provider) {
            SubtitleSettings.STT_DEEPGRAM -> settings.deepgramApiKey.isNotBlank()
            SubtitleSettings.STT_OPENAI -> settings.openAiApiKey.isNotBlank()
            else -> settings.groqApiKey.isNotBlank()
        } }
        val adaptiveProfile = AdaptiveContextStore.get(applicationContext)
        val adaptiveSnapshot = AdaptiveRoutingEngine.snapshot(
            context = applicationContext,
            task = ProviderScoringEngine.Task.STT,
            sourceLanguage = language,
            audioDurationMs = null,
            chunkDurationMs = adaptiveProfile.typicalChunkDurationMs,
            speechSpeedWpm = adaptiveProfile.speechSpeedWpm
        )
        val routedCloud = ProviderRoutingEngine.choose(
            applicationContext,
            cloudPool.map(::sttProviderHealthId),
            configured = { healthId -> when (healthId) {
                ProviderConnectionTester.GROQ -> settings.groqApiKey.isNotBlank()
                ProviderConnectionTester.DEEPGRAM -> settings.deepgramApiKey.isNotBlank()
                ProviderConnectionTester.OPENAI -> settings.openAiApiKey.isNotBlank()
                else -> false
            } },
            forceProvider = if (selected == SubtitleSettings.STT_AUTO || selected == SubtitleSettings.STT_LOCAL) null else sttProviderHealthId(selected),
            task = ProviderScoringEngine.Task.STT,
            adaptiveContext = adaptiveSnapshot
        ).map { sttHealthIdToProvider(it.id) }.filter { it in cloudPool }
        val providers = if (selected == SubtitleSettings.STT_LOCAL) listOf(SubtitleSettings.STT_LOCAL)
            else routedCloud + cloudPool.filter { it !in routedCloud } + if (hasLocalModel) listOf(SubtitleSettings.STT_LOCAL) else emptyList()

        if (providers.isEmpty()) {
            val message = if (selected == SubtitleSettings.STT_AUTO)
                "لا يوجد مزود جاهز للوضع التلقائي. أضف مفتاح Groq أو Deepgram، أو نزّل نموذج Vosk المحلي من الإعدادات. لن يُستخدم OpenAI تلقائياً لأنه قد يتطلب دفعاً."
            else "لا يوجد مزود تفريغ جاهز. أضف مفتاح المزود المختار أو نزّل نموذج Vosk المحلي من الإعدادات."
            throw IllegalStateException(message)
        }
        val failures = mutableListOf<String>()
        val operationStarted = android.os.SystemClock.elapsedRealtime()
        recordOperation("تحويل الصوت إلى نص", "اختيار تلقائي", "Groq → Deepgram → Vosk", operationStarted,
            "قيد التنفيذ", "${providers.size} مزود متاح", "selected=$selected")
        for (provider in providers.distinct()) {
            val attemptStarted = android.os.SystemClock.elapsedRealtime()
            val providerName = when (provider) {
                SubtitleSettings.STT_DEEPGRAM -> "Deepgram"
                SubtitleSettings.STT_OPENAI -> "OpenAI"
                SubtitleSettings.STT_LOCAL -> "Vosk"
                else -> "Groq"
            }
            val modelName = when (provider) {
                SubtitleSettings.STT_DEEPGRAM -> "Nova-3"
                SubtitleSettings.STT_OPENAI -> "Whisper API"
                SubtitleSettings.STT_LOCAL -> "Vosk offline"
                else -> "Whisper"
            }
            try {
                val result = call(provider)
                if (provider != SubtitleSettings.STT_LOCAL) {
                    ProviderRoutingEngine.recordSuccess(
                        applicationContext,
                        sttProviderHealthId(provider),
                        (android.os.SystemClock.elapsedRealtime() - attemptStarted).coerceAtLeast(0L),
                        "نجح طلب STT الفعلي"
                    )
                }
                val observedDurationMs = AdaptiveRoutingEngine.srtDurationMs(result.srt)
                val observedWpm = observedDurationMs?.let { AdaptiveRoutingEngine.wordsPerMinute(result.text, it) }
                if (observedWpm != null) AdaptiveContextStore.recordSpeechSpeed(applicationContext, observedWpm)
                if (observedDurationMs != null && result.segmentCount > 0) {
                    AdaptiveContextStore.recordChunkDuration(applicationContext, observedDurationMs / result.segmentCount.coerceAtLeast(1))
                }
                recordOperation("تحويل الصوت إلى نص", providerName, modelName, attemptStarted, "نجاح",
                    "${result.segmentCount} مقطع", "اللغة=${result.language}; selected=${selected}; adaptiveWpm=${observedWpm ?: "unknown"}")
                if (provider != selected) {
                    val label = when (provider) {
                        SubtitleSettings.STT_DEEPGRAM -> "Deepgram"
                        SubtitleSettings.STT_OPENAI -> "OpenAI"
                        SubtitleSettings.STT_LOCAL -> "Vosk المحلي دون إنترنت"
                        else -> "Groq"
                    }
                    val notice = when {
                        selected == SubtitleSettings.STT_AUTO -> "الوضع التلقائي اختار $label لإنشاء الترجمة."
                        provider == SubtitleSettings.STT_LOCAL -> "تعذرت الخدمات السحابية؛ تم التحويل إلى التفريغ المحلي المجاني."
                        else -> "تعذر المزود المختار؛ نجح البديل $label."
                    }
                    runOnUiThread {
                        if (!isFinishing) Toast.makeText(this, notice, Toast.LENGTH_LONG).show()
                    }
                }
                return result
            } catch (e: Exception) {
                if (provider != SubtitleSettings.STT_LOCAL) {
                    ProviderRoutingEngine.recordFailure(
                        applicationContext,
                        sttProviderHealthId(provider),
                        e,
                        (android.os.SystemClock.elapsedRealtime() - attemptStarted).coerceAtLeast(0L)
                    )
                }
                val label = when (provider) {
                    SubtitleSettings.STT_DEEPGRAM -> "Deepgram"
                    SubtitleSettings.STT_OPENAI -> "OpenAI"
                    SubtitleSettings.STT_LOCAL -> "Vosk المحلي"
                    else -> "Groq"
                }
                val error = (e.message ?: "فشل غير معروف").take(240)
                failures += "$label: $error"
                recordOperation("محاولة تحويل الصوت إلى نص", providerName, modelName, attemptStarted, "فشل", "", error)
            }
        }
        recordOperation("تحويل الصوت إلى نص", "لا يوجد", "fallback exhausted", operationStarted, "فشل",
            "${failures.size} محاولات", failures.joinToString(" | ").take(450))
        throw IllegalStateException("فشلت محاولات التفريغ المتاحة: ${failures.joinToString(" | ")}")
    }

    private fun sttProviderHealthId(provider: String): String = when (provider) {
        SubtitleSettings.STT_DEEPGRAM -> ProviderConnectionTester.DEEPGRAM
        SubtitleSettings.STT_OPENAI -> ProviderConnectionTester.OPENAI
        else -> ProviderConnectionTester.GROQ
    }

    private fun sttHealthIdToProvider(provider: String): String = when (provider) {
        ProviderConnectionTester.DEEPGRAM -> SubtitleSettings.STT_DEEPGRAM
        ProviderConnectionTester.OPENAI -> SubtitleSettings.STT_OPENAI
        else -> SubtitleSettings.STT_GROQ
    }

    /**
     * My Files library: each saved SRT exposes both the original URL and a local-video action.
     * When offline, opening the normal item prefers the previously bound local video.
     */
    private fun showSavedSubtitles() {
        val items = storage.listLibraryItems()
        if (items.isEmpty()) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("ملفاتي")
                .setMessage("لا توجد ملفات محفوظة بعد. عند استيراد SRT أو ترجمته، سيحفظه التطبيق هنا تلقائيًا.")
                .setPositiveButton("حسنًا", null).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }
        val scroll = android.widget.ScrollView(this).apply {
            addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        items.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(android.graphics.Color.rgb(17, 24, 42))
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            }
            val title = TextView(this).apply {
                text = "${index + 1}. ${item.sourceTitle ?: item.title}"
                textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
                maxLines = 2
            }
            val details = TextView(this).apply {
                val source = item.sourceUrl?.let { displaySourceLabel(it) }
                val local = item.localVideoTitle?.let { "فيديو محلي: $it" }
                text = listOfNotNull(
                    if (item.sourceTitle != null && item.title != item.sourceTitle) "SRT: ${item.title}" else null,
                    "${item.cueCount} مقطع",
                    source,
                    local
                ).joinToString("  •  ")
                textSize = 12f
                setTextColor(android.graphics.Color.rgb(167, 176, 200))
                maxLines = 3
                setPadding(0, dp(4), 0, dp(8))
            }
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            }
            val openLink = Button(this).apply {
                text = when {
                    item.sourceUrl?.startsWith("http", true) == true -> "فتح الرابط"
                    item.sourceUrl?.startsWith("content:", true) == true || item.sourceUrl?.startsWith("file:", true) == true -> "فتح الفيديو"
                    else -> "فتح المصدر"
                }
                isAllCaps = false
                isEnabled = !item.sourceUrl.isNullOrBlank()
                setOnClickListener {
                    val srt = storage.loadLibraryItem(item.id)
                    if (srt.isNullOrBlank()) {
                        Toast.makeText(this@SariSubtitleActivity, "تعذّر فتح ملف الترجمة", Toast.LENGTH_LONG).show()
                    } else if (item.sourceUrl.isNullOrBlank()) {
                        Toast.makeText(this@SariSubtitleActivity, "لا يوجد رابط أصلي محفوظ لهذه الترجمة", Toast.LENGTH_LONG).show()
                    } else {
                        openSavedLibraryItem(item, srt, forceLocal = false)
                    }
                }
            }
            actions.addView(openLink, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
            val localButton = Button(this).apply {
                text = if (item.localVideoUri.isNullOrBlank()) "فتح بالفيديو المحلي" else "▶ الفيديو المحلي"
                isAllCaps = false
                setOnClickListener {
                    val srt = storage.loadLibraryItem(item.id)
                    if (srt.isNullOrBlank()) {
                        Toast.makeText(this@SariSubtitleActivity, "تعذّر فتح ملف الترجمة", Toast.LENGTH_LONG).show()
                    } else {
                        openSavedLibraryItem(item, srt, forceLocal = true)
                    }
                }
            }
            actions.addView(localButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
            row.addView(title)
            row.addView(details)
            row.addView(actions)
            row.setOnLongClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("حذف من ملفاتي؟")
                    .setMessage("سيُحذف ملف SRT المحلي، وسيُفك ارتباط الفيديو المحلي فقط.\n\n${item.title}")
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("حذف") { _, _ ->
                        val deleted = storage.deleteLibraryItem(item.id)
                        Toast.makeText(this, if (deleted) "تم الحذف" else "تعذر الحذف", Toast.LENGTH_SHORT).show()
                        showSavedSubtitles()
                    }.show()
                true
            }
            container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("ملفاتي (${items.size})")
            .setView(scroll)
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun offerLocalVideoBinding(libraryId: String, message: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("ربط فيديو محلي")
            .setMessage(message)
            .setNegativeButton("لاحقًا", null)
            .setPositiveButton("اختيار الفيديو") { _, _ -> pickLocalVideoForLibraryItem(libraryId) }
            .show()
    }

    private fun openSavedLibraryItem(item: SavedSrtItem, srt: String, forceLocal: Boolean) {
        val source = item.sourceUrl
        val localUri = item.localVideoUri

        if (forceLocal) {
            if (!localUri.isNullOrBlank()) {
                launchLocalVideoWithSrt(Uri.parse(localUri), srt, item.title)
            } else {
                pickLocalVideoForLibraryItem(item.id)
            }
            return
        }

        if ((source.isNullOrBlank() || !source.startsWith("http", true)) && !localUri.isNullOrBlank()) {
            launchLocalVideoWithSrt(Uri.parse(localUri), srt, item.title)
            return
        }

        if (source?.startsWith("http", true) == true) {
            if (isInternetAvailable()) {
                lastSrtUrl = source
                saveCachedSrt(source, srt, item.cueCount)
                urlInput.setText(source)
                repo.setCues(runCatching { SrtParser.parse(srt.byteInputStream(Charsets.UTF_8)) }.getOrDefault(emptyList()))
                lastSrtText = srt
                overlayOn = true
                statusText.visibility = View.VISIBLE
                statusText.text = "تم استعادة ${item.cueCount} مقطعًا محفوظًا؛ جارٍ فتح الفيديو المرتبط."
                webView.post { runCatching { webView.loadUrl(source) }.onFailure { showStatus("تعذّر فتح الرابط؛ الترجمة المحفوظة ما زالت متاحة.") } }
                Toast.makeText(this, "تم فتح الفيديو واستعادة الترجمة المحفوظة", Toast.LENGTH_SHORT).show()
            } else if (!localUri.isNullOrBlank()) {
                Toast.makeText(this, "لا يوجد اتصال؛ سيتم فتح النسخة المحلية المرتبطة.", Toast.LENGTH_SHORT).show()
                launchLocalVideoWithSrt(Uri.parse(localUri), srt, item.title)
            } else {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("لا يوجد اتصال بالإنترنت")
                    .setMessage("لا يمكن فتح الرابط الآن. اختر ملف الفيديو المحلي نفسه لربطه بهذه الترجمة والعمل دون إنترنت.")
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("اختيار فيديو محلي") { _, _ -> pickLocalVideoForLibraryItem(item.id) }
                    .show()
            }
            return
        }

        if (!localUri.isNullOrBlank()) {
            launchLocalVideoWithSrt(Uri.parse(localUri), srt, item.title)
        } else if (source?.startsWith("content:", true) == true || source?.startsWith("file:", true) == true) {
            launchLocalVideoWithSrt(Uri.parse(source), srt, item.title)
        } else {
            Toast.makeText(this, "تم تحميل الترجمة، لكن لا يوجد فيديو مرتبط بها.", Toast.LENGTH_LONG).show()
        }
    }

    private fun pickLocalVideoForLibraryItem(libraryId: String) {
        pendingLocalVideoLibraryId = libraryId
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*"))
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "اختر الفيديو المحلي المرتبط"), REQ_PICK_LOCAL_VIDEO_FOR_SRT)
        } catch (_: Exception) {
            pendingLocalVideoLibraryId = null
            Toast.makeText(this, "تعذّر فتح مدير الملفات", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchLocalVideoWithSrt(uri: Uri, srt: String, title: String) {
        try {
            startActivity(Intent(this, LocalMediaPlayerActivity::class.java).apply {
                data = uri
                putExtra(LocalMediaPlayerActivity.EXTRA_SRT, srt)
                putExtra(LocalMediaPlayerActivity.EXTRA_TITLE, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "تعذّر فتح الفيديو المحلي: ${e.message ?: "ملف غير مدعوم"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun isInternetAvailable(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) { false }
    }

    private fun displaySourceLabel(source: String): String = try {
        val uri = Uri.parse(source)
        when {
            source.startsWith("http", true) -> when {
                (uri.host.orEmpty().contains("x.com", true) || uri.host.orEmpty().contains("twitter.com", true)) -> "X · ${Regex("/status/(\\d+)").find(uri.path.orEmpty())?.groupValues?.getOrNull(1) ?: uri.host}"
                uri.host.orEmpty().contains("youtube", true) || uri.host.orEmpty() == "youtu.be" -> "YouTube · ${uri.getQueryParameter("v") ?: uri.lastPathSegment.orEmpty()}"
                else -> uri.host ?: "رابط فيديو"
            }
            source.startsWith("content://", true) || source.startsWith("file://", true) -> {
                val name = if (source.startsWith("content://", true)) queryDisplayName(uri) else uri.lastPathSegment?.let { Uri.decode(it) }
                name?.takeIf { it.isNotBlank() }?.let { "فيديو محلي · $it" } ?: "فيديو محلي"
            }
            source.startsWith("local-media://", true) -> "مصدر محلي قديم — الرابط الأصلي غير محفوظ"
            else -> "مصدر فيديو"
        }
    } catch (_: Exception) { "مصدر فيديو" }

    private fun sameVideoUrl(first: String, second: String): Boolean =
        VideoUrlIdentity.sameVideo(first, second)

    /** Only bind imported SRT to an actual-looking video page, never a default homepage. */
    private fun isVideoLikeUrl(raw: String): Boolean {
        return try {
            val uri = Uri.parse(raw.trim())
            val scheme = uri.scheme.orEmpty().lowercase(Locale.ROOT)
            val host = uri.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
            if (scheme !in listOf("http", "https") || host.isBlank()) return false
            val path = uri.path.orEmpty()
            val xHost = host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" || host.endsWith(".twitter.com")
            if (xHost) return Regex("(?:^|/)status/\\d+(?:/|$)").containsMatchIn(path)
            val youtubeHost = host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
            if (youtubeHost) return YouTubeCaptionExtractor.getVideoId(raw) != null
            val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val directMedia = extension in setOf("mp4", "m4v", "mov", "webm", "mkv", "mp3", "m4a", "aac", "wav", "ogg", "opus", "flac")
            directMedia || path.trim('/').isNotBlank()
        } catch (_: Exception) { false }
    }

    private fun currentVideoUrlForCache(): String? {
        val pageUrl = webView.url?.trim().orEmpty()
        val visiblePost = activeVisiblePostUrl?.trim().orEmpty()
        // On an X profile/feed, key SRT to the visible post, not to the profile URL.
        // On a direct status page, prefer the page URL if the feed detector is stale.
        if (isXStatusUrl(visiblePost) &&
            (!isXStatusUrl(pageUrl) || sameVideoUrl(pageUrl, visiblePost))) {
            return visiblePost
        }
        return pageUrl.takeIf { isVideoLikeUrl(it) }
    }

    /** Use the current X post URL when the selected-media transcription was launched from an X post;
     * otherwise keep the original local content/file URI. Never create synthetic local-media:// IDs. */
    private fun resolvePickedMediaSource(uri: Uri): String {
        val currentPage = webView.url.orEmpty().trim()
        return if (isXStatusUrl(currentPage)) currentPage else uri.toString()
    }

    private fun isXHostUrl(raw: String): Boolean = try {
        val uri = Uri.parse(raw.trim())
        val host = uri.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" || host.endsWith(".twitter.com")
    } catch (_: Exception) { false }

    private fun isXStatusUrl(raw: String): Boolean = try {
        val uri = Uri.parse(raw.trim())
        val host = uri.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        (host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" || host.endsWith(".twitter.com")) &&
            Regex("(?:^|/)status/(\\d+)(?:/|$)").containsMatchIn(uri.path.orEmpty())
    } catch (_: Exception) { false }

    private fun showBottomSheet() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_menu, null)
        sheet.setContentView(view)

        // واجهة مبسطة: لا نعرض مزودات الترجمة أو أدوات التشخيص للمستخدم العادي.
        view.findViewById<Button>(R.id.bsOneTapArabicButton)?.setOnClickListener {
            sheet.dismiss()
            startPrimaryTranslationFlow()
        }
        view.findViewById<Button>(R.id.bsTranslateSrtButton)?.setOnClickListener {
            sheet.dismiss()
            findViewById<Button>(R.id.translateSrtButton)?.performClick()
        }
        view.findViewById<Button>(R.id.bsImportSrtButton)?.setOnClickListener {
            sheet.dismiss()
            openSrtPicker()
        }
        sheet.show()
    }

    /**
     * Routes the main action according to the engine selected in Settings.
     * Internal mode translates a user-selected SRT through the app's configured translator
     * (for example Gemini). It must not silently open SubWhisper because URL-to-SRT
     * transcription is not implemented by the internal engine yet.
     */
    private fun startPrimaryTranslationFlow() {
        // زر واحد: استعادة محفوظ -> ترجمة YouTube إن كانت متاحة -> التقاط صوت الفيديو الحالي.
        // لا يفتح SubWhisper تلقائياً في الوضع الداخلي.
        startAutomaticLinkFlow()
    }

    /**
     * مسار مبسط للرابط الحالي:
     * 1) يسترجع SRT المحفوظ إن وجد.
     * 2) يحاول جلب ترجمة YouTube العامة تلقائياً عند توفرها.
     * 3) يفتح مسار SubWhisper الداخلي للروابط الأخرى بدلاً من إجبار المستخدم على اختيار مزود.
     * لا ندّعي أن كل منصة تسمح بالاستخراج التلقائي الكامل.
     */
    private fun startAutomaticLinkFlow() {
        val typedLink = urlInput.text.toString().trim()
        val pageLink = webView.url.orEmpty().trim()
        val homeUrl = typedLink.equals("https://x.com/", ignoreCase = true) ||
            typedLink.equals("https://x.com", ignoreCase = true) ||
            typedLink.equals("https://www.x.com/", ignoreCase = true)
        val pageIsHome = pageLink.equals("https://x.com/", ignoreCase = true) ||
            pageLink.equals("https://x.com", ignoreCase = true) ||
            pageLink.equals("https://www.x.com/", ignoreCase = true)
        val clipboardUrl = try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = if (clipboard.hasPrimaryClip() && (clipboard.primaryClip?.itemCount ?: 0) > 0)
                clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty() else ""
            Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE).find(clipText)
                ?.value?.trimEnd('.', ',', ')', ']', '}', '،').orEmpty()
        } catch (_: Exception) { "" }
        val link = when {
            typedLink.isNotBlank() && !homeUrl -> typedLink
            pageLink.isNotBlank() && !pageIsHome -> pageLink
            clipboardUrl.isNotBlank() -> clipboardUrl
            else -> ""
        }
        val uri = try { Uri.parse(link) } catch (_: Exception) { null }
        if (link.isBlank() || uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
            if (settings.processingEngine == SubtitleSettings.ENGINE_INTERNAL) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("محرك Sari Subtitle الداخلي")
                    .setMessage("لم يُعثر على رابط فيديو صالح. اختر ملف صوت/فيديو لتفريغه داخل التطبيق، أو اختر ملف SRT لترجمته. لن يتم فتح SubWhisper في المحرك الداخلي.")
                    .setPositiveButton("اختيار صوت أو فيديو") { _, _ -> openVideoPickerForAutoTranscription() }
                    .setNeutralButton("ترجمة ملف SRT") { _, _ -> findViewById<Button>(R.id.translateSrtButton)?.performClick() }
                    .setNegativeButton("إلغاء", null)
                    .show()
            } else {
                openPasteLinkFlow()
            }
            return
        }

        urlInput.setText(link)
        // If a cached SRT exists but WebView is still showing the home page or another
        // video, navigate first. Returning immediately after restoreCachedSrt() used to
        // load the cues into memory while leaving the old page on screen, so the user saw
        // no matching video/subtitles after pasting a previously translated URL.
        if (storage.has(link) && !sameVideoUrl(link, webView.url.orEmpty())) {
            urlInput.setText(link)
            statusText.visibility = android.view.View.VISIBLE
            statusText.text = "تم العثور على ترجمة محفوظة لهذا الفيديو. جارٍ فتح الفيديو واستعادة الترجمة…"
            webView.loadUrl(link)
            return
        }

        if (restoreCachedSrt(link)) {
            statusText.visibility = android.view.View.VISIBLE
            statusText.text = "تم تحميل الترجمة المحفوظة لهذا الرابط تلقائياً."
            Toast.makeText(this, "تم استرجاع الترجمة المحفوظة", Toast.LENGTH_SHORT).show()
            return
        }

        val youtubeId = YouTubeCaptionExtractor.getVideoId(link)
        if (youtubeId != null) {
            statusText.visibility = android.view.View.VISIBLE
            statusText.text = "جارٍ البحث تلقائياً عن ترجمة جاهزة للفيديو…"
            if (!sameVideoUrl(link, webView.url.orEmpty())) webView.loadUrl(link)
            fetchYouTubeCaptions(link, "auto")
            return
        }

        statusText.visibility = android.view.View.VISIBLE
        if (sameVideoUrl(link, webView.url.orEmpty())) {
            statusText.text = "لم توجد ترجمة جاهزة. جارٍ محاولة التقاط صوت الفيديو وتفريغه…"
            transcribeCurrentWebVideo()
        } else {
            // نحمّل الرابط داخل مشغّل Sari ثم نبدأ الالتقاط بعد اكتمال التحميل.
            pendingAutoCaptureUrl = link
            statusText.text = "لم توجد ترجمة جاهزة. جارٍ فتح الفيديو داخل Sari Subtitle ثم استخراج الصوت…"
            webView.loadUrl(link)
        }
    }

    private fun openChromeForDownload() {
        // 1) انسخ رابط X الحالي
        val link = urlInput.text.toString().trim().ifBlank { webView.url.orEmpty().trim() }
        if (link.isNotEmpty() && link.startsWith("http")) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Video URL", link))
            Toast.makeText(this, "✅ تم نسخ الرابط — سيُفتح Chrome", Toast.LENGTH_LONG).show()
        }
        
        // 2) افتح Chrome مع ssstwitter
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ssstwitter.com/"))
            // حدد Chrome تحديداً
            intent.setPackage("com.android.chrome")
            startActivity(intent)
            Toast.makeText(this, "الصق الرابط في Chrome → حمّل الفيديو MP4 → عُد للتطبيق واختره", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // إذا Chrome غير مثبت — استخدم أي متصفح
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ssstwitter.com/")))
                Toast.makeText(this, "الصق الرابط → حمّل الفيديو MP4 → عُد واختره", Toast.LENGTH_LONG).show()
            } catch (e2: Exception) {
                Toast.makeText(this, "❌ لا يوجد متصفح: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun oneTapArabic() {
        val link = urlInput.text.toString().trim().ifBlank { webView.url.orEmpty().trim() }
        if (link.isNotEmpty() && link.startsWith("http")) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Video URL", link))
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🎬 ترجمة فيديو X للعربية")
            .setMessage(
                "الخطوة 1: سيفتح Chrome لتنزيل الصوت.\n" +
                "الخطوة 2: الصق الرابط → Download MP3.\n" +
                "الخطوة 3: ارجع للتطبيق واختر الملف.\n\n" +
                "التطبيق سيكمل تلقائياً:\n" +
                "• تفريغ الصوت (Groq/Deepgram).\n" +
                "• ترجمة للعربية (DeepL + Google).\n" +
                "• عرض الترجمة على الفيديو."
            )
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("فتح Chrome") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ssstwitter.com/"))
                    intent.setPackage("com.android.chrome")
                    startActivity(intent)
                    Toast.makeText(this, "⬇️ حمّل الفيديو MP4، ثم عُد للتطبيق واختره", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ssstwitter.com/")))
                    } catch (e2: Exception) {
                        Toast.makeText(this, "❌ لا يوجد متصفح", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

private fun openAudioPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            setType("audio/*")
        }
        try {
            startActivityForResult(intent, REQ_PICK_AUDIO)
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر فتح مدير الملفات", Toast.LENGTH_LONG).show()
        }
    }

    private fun transcribeAudioFile(uri: Uri) {
        val labels = arrayOf(
            "اكتشاف تلقائي (موصى به)",
            "العربية (ar)",
            "الإنجليزية (en)"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("لغة الصوت")
            .setItems(labels) { _, selected ->
                val lang = when (selected) {
                    1 -> "ar"
                    2 -> "en"
                    else -> "auto"
                }
                runGroqTranscription(uri, lang)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun runGroqTranscription(uri: Uri, language: String) {
        if (!hasSelectedSttKey()) {
            Toast.makeText(this, "جهّز مزود التفريغ من الإعدادات (مفتاح API أو نموذج Vosk المحلي) أولاً", Toast.LENGTH_LONG).show()
            return
        }

        val cancellation = OperationCancellation()
        beginTrackedOperation(cancellation)
        // إنشاء ProgressDialog مع مؤقت
        val progress = android.app.ProgressDialog(this).apply {
            setTitle("تفريغ الصوت (${settings.sttProviderLabel})")
            setMessage("جارٍ بدء الطلب…")
            setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "إلغاء") { _, _ -> cancellation.cancel() }
            show()
            getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.setOnClickListener {
                cancellation.cancel(); setMessage("جارٍ إلغاء التفريغ…")
            }
        }

        val startTime = System.currentTimeMillis()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var cancelled = false

        // مؤقت يحدّث كل ثانية
        val timer = object : Runnable {
            override fun run() {
                if (cancelled) return
                val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                val mins = elapsed / 60
                val secs = elapsed % 60
                progress.setMessage("جارٍ إرسال الملف إلى ${settings.sttProviderLabel}…\nمضى: ${mins}:${String.format("%02d", secs)}")
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(timer)

        statusText.text = "⏳ جارٍ التفريغ… (${language})"

        val worker = Thread {
            try {
                cancellation.check()
                val result = transcribeWithSelectedProvider(uri, language)
                cancellation.check()
                val totalTime = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                cancelled = true
                runOnUiThread {
                    handler.removeCallbacks(timer)
                    progress.dismiss()
                    if (cancellation.isCancelled()) { Toast.makeText(this, "تم إلغاء العملية", Toast.LENGTH_SHORT).show(); statusText.text = "تم إلغاء العملية"; return@runOnUiThread }
                    pendingWhisperSrt = result.srt

                    // حمّل نتيجة التفريغ مباشرة إلى عارض الترجمة؛ لا تجعلها ملفاً للحفظ فقط.
                    val parsedCues = try {
                        SrtParser.parse(result.srt.byteInputStream(Charsets.UTF_8))
                    } catch (parseError: Exception) {
                        android.util.Log.e("SariSrt", "Failed to parse generated transcription SRT", parseError)
                        emptyList()
                    }
                    if (parsedCues.isNotEmpty()) {
                        repo.setCues(parsedCues)
                        lastSrtText = result.srt
                        val currentUrl = currentVideoUrlForCache()
                        if (currentUrl != null && saveCachedSrt(currentUrl, result.srt, parsedCues.size)) {
                            lastSrtUrl = currentUrl
                        }
                        overlayOn = true
                        statusText.text = "✅ اكتمل التفريغ وعُرضت الترجمة: ${parsedCues.size} مقطع (${totalTime}s)"
                    } else {
                        statusText.text = "⚠️ اكتمل التفريغ، لكن تعذّر تحليل توقيت SRT لعرضه. يمكنك حفظ الملف وفحصه."
                    }
                    Toast.makeText(this, "✅ اكتمل التفريغ بعد ${totalTime} ثانية", Toast.LENGTH_LONG).show()
                    val saveIntent3 = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        setType("application/x-subrip")
                        putExtra(Intent.EXTRA_TITLE, "transcription.srt")
                    }
                    startActivityForResult(saveIntent3, REQ_SAVE_WHISPER_SRT)
                }
            } catch (e: Exception) {
                cancelled = true
                val totalTime = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                runOnUiThread {
                    handler.removeCallbacks(timer)
                    progress.dismiss()
                    if (cancellation.isCancelled() || e is java.util.concurrent.CancellationException) { statusText.text = "تم إلغاء العملية"; Toast.makeText(this, "تم إلغاء العملية", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                    val msg = e.message ?: "خطأ غير معروف"
                    statusText.text = "❌ فشل التفريغ بعد ${totalTime}s: $msg"
                    android.app.AlertDialog.Builder(this)
                        .setTitle("فشل التفريغ")
                        .setMessage("$msg\n\n(بعد ${totalTime} ثانية)")
                        .setPositiveButton("حسناً", null)
                        .show()
                }
            }
        }.also { cancellation.worker = it; it.start() }
    }

    private fun saveWhisperSrt(uri: Uri) {
        val srt = pendingWhisperSrt
        if (srt.isNullOrBlank()) {
            Toast.makeText(this, "لا يوجد SRT لحفظه", Toast.LENGTH_LONG).show()
            return
        }
        try {
            contentResolver.openOutputStream(uri)?.use {
                it.write(srt.toByteArray(Charsets.UTF_8))
            } ?: throw java.io.IOException("تعذر فتح ملف الحفظ")
            pendingWhisperSrt = null
            statusText.text = "تم حفظ SRT"
            Toast.makeText(this, "تم حفظ transcription.srt", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر الحفظ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LIVE_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                startLiveCaptureService(resultCode, data)
            } else {
                statusText.text = "تم إلغاء إذن التقاط صوت الوسائط؛ لم تبدأ الترجمة المباشرة."
                Toast.makeText(this, "تم إلغاء التقاط صوت الوسائط", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (resultCode != Activity.RESULT_OK) return
        if (requestCode == REQ_SUBWHISPER_INTERNAL) {
            var srtText = data?.getStringExtra(SubWhisperInternalActivity.EXTRA_SRT_TEXT)
            // إذا لم يصل، اقرأ من SrtStorage (احتياطي)
            if (srtText.isNullOrBlank()) {
                srtText = try {
                    storage.load("subwhisper-latest")
                } catch (e: Exception) { null }
                android.util.Log.d("SariSrt", "read from storage: ${srtText?.length ?: 0} chars")
            }
            if (srtText.isNullOrBlank()) {
                Toast.makeText(this, "لم يصل ملف SRT من صفحة SubWhisper", Toast.LENGTH_LONG).show()
                return
            }
            try {
                val cues = SrtParser.parse(srtText.byteInputStream())
                if (cues.isEmpty()) {
                    Toast.makeText(this, "الملف المستلم لا يحتوي على مقاطع SRT صالحة", Toast.LENGTH_LONG).show()
                    return
                }
                repo.setCues(cues)
                lastSrtText = srtText
                val currentUrl = currentVideoUrlForCache()
                if (currentUrl != null && saveCachedSrt(currentUrl, srtText, cues.size)) {
                    lastSrtUrl = currentUrl
                }
                overlayOn = true
                statusText.text = "تم استيراد SRT من SubWhisper داخل التطبيق: ${cues.size} مقطع"
                Toast.makeText(this, "تم استيراد ${cues.size} مقطع SRT", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "تعذّر قراءة SRT: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return
        }
        val uri = data?.data ?: return
        if (requestCode == REQ_PICK_VIDEO_FOR_AUTO) {
            // Keep the document-provider permission so My Files can reopen this local video later.
            try {
                val grantedFlags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if ((data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                    contentResolver.takePersistableUriPermission(uri, grantedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (e: Exception) {
                android.util.Log.w("SariMyFiles", "Provider did not allow persistable URI permission for $uri", e)
            }
        }
        if (requestCode == REQ_PICK_LOCAL_VIDEO_FOR_SRT) {
            val libraryId = pendingLocalVideoLibraryId
            pendingLocalVideoLibraryId = null
            if (libraryId == null) return
            try {
                val readFlags = data?.flags?.and(Intent.FLAG_GRANT_READ_URI_PERMISSION) ?: Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (data != null && (data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                    contentResolver.takePersistableUriPermission(uri, readFlags)
                }
                val displayName = queryDisplayName(uri)
                if (!storage.bindLocalVideo(libraryId, uri.toString(), displayName)) {
                    Toast.makeText(this, "تعذّر حفظ ربط الفيديو المحلي", Toast.LENGTH_LONG).show()
                    return
                }
                val srt = storage.loadLibraryItem(libraryId)
                val item = storage.libraryItem(libraryId)
                if (srt.isNullOrBlank() || item == null) {
                    Toast.makeText(this, "تم حفظ الربط، لكن تعذّر إعادة تحميل الترجمة", Toast.LENGTH_LONG).show()
                    return
                }
                Toast.makeText(this, "تم ربط الفيديو المحلي بهذه الترجمة. سيُفتح تلقائيًا عند عدم وجود إنترنت.", Toast.LENGTH_LONG).show()
                launchLocalVideoWithSrt(uri, srt, item.title)
            } catch (e: Exception) {
                Toast.makeText(this, "تعذّر ربط الفيديو المحلي: ${e.message ?: "خطأ غير معروف"}", Toast.LENGTH_LONG).show()
            }
            return
        }
        when (requestCode) {
            REQ_PICK_SRT -> processSmartSrt(uri)
            REQ_PICK_SRT_TO_TRANSLATE -> processTranslateSrt(uri)
            REQ_TRANSLATE_SRT_PICK -> translateSelectedSrt(uri)
            REQ_SAVE_TRANSLATED_SRT -> saveTranslatedSrt(uri)
            REQ_PICK_AUDIO -> transcribeAudioFile(uri)
            REQ_PICK_VIDEO_FOR_AUTO -> transcribeDownloadedVideo(uri)
            REQ_SAVE_WHISPER_SRT -> saveWhisperSrt(uri)
            REQ_SAVE_DUBBING_WAV -> exportDubbingWav(uri)
        }
    }

    private fun loadSrtFromUri(uri: Uri) {
        try {
            val input = contentResolver.openInputStream(uri)
            if (input == null) {
                Toast.makeText(this, "تعذّر فتح الملف", Toast.LENGTH_SHORT).show()
                return
            }
            val srtText = input.use { SrtParser.decode(it) }
            val cues = SrtParser.parse(srtText.byteInputStream())
            if (cues.isEmpty()) {
                Toast.makeText(this, "لا ترجمات في الملف", Toast.LENGTH_LONG).show()
                return
            }
            repo.setCues(cues)
            lastSrtText = srtText
            if (!overlayOn) overlayOn = true

            // ⚡ احفظ مرتبطاً بـ URL الحالي
            val currentUrl = currentVideoUrlForCache()
            val cacheSaved = currentUrl != null && saveCachedSrt(currentUrl, srtText, cues.size)
            lastSrtUrl = currentUrl
            Toast.makeText(this, if (cacheSaved) "✅ تم تحميل ${cues.size} سطر وحفظه" else "✅ تم تحميل ${cues.size} سطر؛ لم يتم تأكيد الحفظ التلقائي", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "خطأ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleOverlay() {
        if (repo.isEmpty) {
            Toast.makeText(this, "حمّل SRT أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        overlayOn = !overlayOn
        Toast.makeText(
            this,
            if (overlayOn) "✅ الترجمة مفعّلة" else "❌ معطّلة",
            Toast.LENGTH_SHORT
        ).show()
        if (!overlayOn) subtitleOverlay.hide()
    }

    private fun injectAutoShow() {
        val js = """
            (function() {
              if (window.__sariAutoShowInterval) {
                clearInterval(window.__sariAutoShowInterval);
              }
              var keywords = ['Show','عرض','إظهار','اظهار','Mostrar','Afficher'];
              function isShow(el) {
                if (!el) return false;
                var t = (el.innerText || el.textContent || '').trim();
                if (!t || t.length > 30) return false;
                var l = t.toLowerCase();
                for (var i = 0; i < keywords.length; i++) {
                  if (l === keywords[i].toLowerCase()) return true;
                }
                return false;
              }
              function scan() {
                try {
                  var els = document.querySelectorAll('div[role="button"], button, span[role="button"]');
                  for (var i = 0; i < els.length; i++) {
                    if (isShow(els[i])) { try { els[i].click(); } catch(e){} }
                  }
                } catch(e){}
              }
              window.__sariAutoShowInterval = setInterval(scan, 1500);
              scan();
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /** Clear only the active display state; saved SRT files remain untouched. */
    private fun clearActiveSubtitleForVideoChange() {
        repo.clear()
        repo.offsetMs = settings.offsetMs
        lastSrtText = null
        lastSrtUrl = null
        overlayOn = false
        lastDubbingCueStartMs = -1L
        activePlaybackKey = null
        pendingResumePositionMs = 0L
        resumePromptShownForKey = null
        resumeAppliedForKey = null
        lastPlaybackPositionMs = 0L
        lastPlaybackDurationMs = 0L
        lastPlaybackSaveAtMs = 0L
        runCatching { textToSpeech?.stop() }
        if (::subtitleOverlay.isInitialized) subtitleOverlay.hide()
    }

    /** Switch subtitle state only when the visible X post identity actually changes. */
    private fun activateVisiblePost(rawPostUrl: String) {
        val postUrl = rawPostUrl.trim()
        if (!isXStatusUrl(postUrl)) return
        if (activeVisiblePostUrl?.let { sameVideoUrl(it, postUrl) } == true) return
        activeVisiblePostUrl = postUrl
        val restored = tryAutoLoadSrt(postUrl)
        if (!restored) {
            // A new post with no cached SRT must never inherit the previous post's cues.
            clearActiveSubtitleForVideoChange()
        }
        preparePlaybackResume(postUrl)
        pollPlaybackStateAfterPostSwitch()
    }

    private fun pollPlaybackStateAfterPostSwitch() {
        lastPlaybackPositionMs = 0L
        lastPlaybackDurationMs = 0L
        lastPlaybackSaveAtMs = 0L
        lastDubbingCueStartMs = -1L
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.post(object : Runnable {
            override fun run() {
                if (!polling) return
                observeCurrentVideoUrl()
                pollCurrentTimeOnce()
                handler.postDelayed(this, 500L)
            }
        })
    }

    /**
     * WebViewClient.onPageFinished is not guaranteed for single-page-app route changes.
     * Observe the URL alongside the existing playback poll so an X/YouTube SPA navigation
     * can restore that video's cached SRT and cannot leave the previous video's cues visible.
     */
    private fun observeCurrentVideoUrl() {
        val current = webView.url?.trim().orEmpty()
        if (!isVideoLikeUrl(current)) return
        val previous = lastObservedVideoUrl
        if (previous != null && sameVideoUrl(previous, current)) return
        lastObservedVideoUrl = current
        tryAutoLoadSrt(current)
        preparePlaybackResume(current)
    }

    private fun preparePlaybackResume(source: String) {
        if (!::playbackStore.isInitialized || !isVideoLikeUrl(source)) return
        val key = playbackStore.keyFor(source) ?: return
        if (activePlaybackKey == key) return
        activePlaybackKey = key
        pendingResumePositionMs = playbackStore.getByKey(key)?.positionMs ?: 0L
        resumePromptShownForKey = null
        resumeAppliedForKey = null
        lastPlaybackPositionMs = 0L
        lastPlaybackDurationMs = 0L
        lastPlaybackSaveAtMs = 0L
    }

    private fun maybeOfferWebResume(durationMs: Long) {
        val key = activePlaybackKey ?: return
        if (resumeAppliedForKey == key || resumePromptShownForKey == key) return
        val saved = pendingResumePositionMs
        if (saved < RESUME_MIN_MS) {
            resumeAppliedForKey = key
            return
        }
        if (durationMs > 0L && saved >= (durationMs - RESUME_MIN_MS).coerceAtLeast(0L)) {
            playbackStore.clearByKey(key)
            pendingResumePositionMs = 0L
            resumeAppliedForKey = key
            return
        }
        resumePromptShownForKey = key
        val label = formatSec(saved / 1000.0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("استئناف التشغيل")
            .setMessage("تم حفظ هذا الفيديو عند $label. هل تريد المتابعة من الموضع المحفوظ؟")
            .setNegativeButton("بدء من البداية") { _, _ ->
                playbackStore.clearByKey(key)
                pendingResumePositionMs = 0L
                resumeAppliedForKey = key
                seekWebVideoTo(0L)
            }
            .setPositiveButton("استئناف") { _, _ ->
                resumeAppliedForKey = key
                seekWebVideoTo(saved)
            }
            .setOnCancelListener {
                resumeAppliedForKey = key
            }
            .show()
    }

    private fun seekWebVideoTo(positionMs: Long) {
        val targetSeconds = (positionMs.coerceAtLeast(0L) / 1000.0).toString()
        val js = """
            (function() {
              try {
                var vids = Array.from(document.querySelectorAll('video'));
                var best = vids.find(function(v) { return v && v.readyState >= 1 && v.duration > 0; }) || vids[0];
                if (best) { best.currentTime = $targetSeconds; return true; }
              } catch(e) {}
              return false;
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        pollCurrentTimeOnce()
    }

    private fun saveCurrentPlaybackPosition() {
        if (!::playbackStore.isInitialized) return
        val key = activePlaybackKey ?: return
        if (lastPlaybackPositionMs < RESUME_MIN_MS || lastPlaybackDurationMs <= 0L) return
        playbackStore.saveByKey(key, lastPlaybackPositionMs, lastPlaybackDurationMs)
        lastPlaybackSaveAtMs = System.currentTimeMillis()
    }

    private fun rememberCachedVideoUrl(url: String) {
        if (!isVideoLikeUrl(url) || !storage.has(url)) return
        getPreferences(MODE_PRIVATE).edit().putString(PREF_LAST_CACHED_VIDEO_URL, url).apply()
    }

    /** Save the SRT and remember its URL only after the storage index confirms success. */
    private fun saveCachedSrt(url: String, srtText: String, cueCount: Int): Boolean {
        if (url.trim().startsWith("local-media://", true)) {
            android.util.Log.w("SariSrtStorage", "Refusing synthetic local-media cache key; original source URI/URL is required")
            return false
        }
        val saved = storage.save(url, srtText, cueCount) != null
        if (saved) getPreferences(MODE_PRIVATE).edit().putString(PREF_LAST_CACHED_VIDEO_URL, url).apply()
        return saved
    }

    private fun pollCurrentTimeOnce() {
        val js = """
            (function() {
              try {
                window.__sariDubEnabled = ${if (dubbingEnabled) "true" else "false"};
                function collect(root, acc) {
                  try {
                    var v = root.querySelectorAll('video');
                    for (var i = 0; i < v.length; i++) acc.push(v[i]);
                    var all = root.querySelectorAll('*');
                    for (var j = 0; j < all.length; j++) {
                      if (all[j].shadowRoot) collect(all[j].shadowRoot, acc);
                    }
                  } catch(e){}
                  return acc;
                }
                var vids = collect(document, []);
                if (vids.length === 0) return JSON.stringify({totalVideos: 0});
                var best = null, maxArea = 0;
                for (var i = 0; i < vids.length; i++) {
                  try {
                    var v = vids[i];
                    var r = v.getBoundingClientRect();
                    if (r.width < 100 || r.height < 100) continue;
                    if (r.bottom < 0 || r.top > window.innerHeight) continue;
                    var vTop = Math.max(0, r.top);
                    var vBot = Math.min(window.innerHeight, r.bottom);
                    var vH = Math.max(0, vBot - vTop);
                    var area = r.width * vH;
                    if (area > maxArea) { maxArea = area; best = v; }
                  } catch(e){}
                }
                if (!best) best = vids[0];
                if (window.__sariDubEnabled) {
                  if (!best.hasAttribute('data-sari-dub-original')) {
                    best.setAttribute('data-sari-dub-original', '1');
                    best.dataset.sariDubMuted = best.muted ? '1' : '0';
                    best.dataset.sariDubVolume = String(best.volume);
                  }
                  best.muted = true; best.volume = 0;
                } else if (best.hasAttribute('data-sari-dub-original')) {
                  best.muted = best.dataset.sariDubMuted === '1';
                  best.volume = Number(best.dataset.sariDubVolume || '1');
                  best.removeAttribute('data-sari-dub-original');
                }
                var t = Number(best.currentTime);
                var d = Number(best.duration);
                // Find the X status URL attached to this exact video card. The page URL
                // often remains a profile URL while the user swipes through feed videos.
                var postUrl = '';
                try {
                  var article = best.closest ? best.closest('article') : null;
                  if (article) {
                    var links = article.querySelectorAll('a[href*="/status/"]');
                    for (var li = 0; li < links.length; li++) {
                      var href = links[li].href || '';
                      var parsed = new URL(href, location.href);
                      var host = (parsed.hostname || '').toLowerCase().replace(/^www\./, '');
                      var statusMatch = parsed.pathname.match(/^\/([^/]+)\/status\/(\d+)(?:\/|$)/);
                      if ((host === 'x.com' || host === 'twitter.com') && statusMatch) {
                        postUrl = 'https://x.com/' + statusMatch[1] + '/status/' + statusMatch[2];
                        break;
                      }
                    }
                  }
                } catch(e) {}
                return JSON.stringify({
                  totalVideos: vids.length,
                  currentTime: Number.isFinite(t) ? t : 0,
                  duration: Number.isFinite(d) ? d : 0,
                  paused: !!best.paused,
                  readyState: best.readyState,
                  postUrl: postUrl
                });
              } catch(e) { return JSON.stringify({error: String(e)}); }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { raw ->
            val decoded = try {
                org.json.JSONTokener(raw ?: "null").nextValue().toString()
            } catch (_: Exception) { "null" }
            runOnUiThread { onTimeUpdate(decoded) }
        }
    }

    private fun onTimeUpdate(json: String) {
        try {
            val o = JSONObject(json)
            val detectedPostUrl = o.optString("postUrl").trim()
            if (detectedPostUrl.isNotBlank() && isXStatusUrl(detectedPostUrl)) {
                missingVisiblePostSamples = 0
                activateVisiblePost(detectedPostUrl)
            } else {
                val pageUrl = webView.url?.trim().orEmpty()
                val isXFeedPage = isXHostUrl(pageUrl) && !isXStatusUrl(pageUrl)
                if (isXFeedPage && activeVisiblePostUrl != null) {
                    missingVisiblePostSamples++
                    // If the feed has moved but its card identity is not yet available,
                    // fail closed after a short grace period rather than showing stale cues.
                    if (missingVisiblePostSamples >= 3) {
                        activeVisiblePostUrl = null
                        clearActiveSubtitleForVideoChange()
                    }
                } else {
                    missingVisiblePostSamples = 0
                }
            }
            if (o.has("error")) {
                statusText.text = "خطأ: ${o.getString("error")}"
                return
            }
            val total = o.optInt("totalVideos", 0)
            if (total == 0) {
                statusText.text = "لا يوجد فيديو — total=0"
                return
            }
            val t = o.optDouble("currentTime", 0.0)
            val d = o.optDouble("duration", 0.0)
            val p = o.optBoolean("paused", false)
            val r = o.optInt("readyState", 0)
            val currentMs = (t.coerceAtLeast(0.0) * 1000.0).toLong()
            val durationMs = (d.coerceAtLeast(0.0) * 1000.0).toLong()
            lastPlaybackPositionMs = currentMs
            lastPlaybackDurationMs = durationMs
            if (durationMs > 0L) maybeOfferWebResume(durationMs)
            if (activePlaybackKey != null && durationMs > 0L &&
                (System.currentTimeMillis() - lastPlaybackSaveAtMs >= 3_000L || p)) {
                saveCurrentPlaybackPosition()
            }
            if (durationMs > 0L && currentMs >= (durationMs - RESUME_MIN_MS).coerceAtLeast(0L)) {
                activePlaybackKey?.let(playbackStore::clearByKey)
            }

            val cue = repo.findCueAt(currentMs)

            if (subtitlesVisible && overlayOn && !repo.isEmpty) {
                if (cue != null) subtitleOverlay.show(cue.text) else subtitleOverlay.hide()
            } else {
                subtitleOverlay.hide()
            }
            if (dubbingEnabled) {
                if (p) {
                    textToSpeech?.stop()
                    lastDubbingCueStartMs = -1L
                } else if (cue != null && cue.startMs != lastDubbingCueStartMs) {
                    lastDubbingCueStartMs = cue.startMs
                    if (ttsReady) {
                        val durationSeconds = ((cue.endMs - cue.startMs).coerceAtLeast(500L)) / 1000.0
                        val estimatedRate = (cue.text.length / (durationSeconds * 14.0)).toFloat().coerceIn(0.75f, 1.8f)
                        android.util.Log.i("SariDubbing", "live cue=${cue.index} startMs=${cue.startMs} endMs=${cue.endMs} slotMs=${cue.endMs - cue.startMs} speechRate=$estimatedRate provider=Android-TTS")
                        textToSpeech?.setSpeechRate(estimatedRate)
                        val params = android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
                        textToSpeech?.speak(cue.text, TextToSpeech.QUEUE_FLUSH, params, "sari-dub-${cue.startMs}")
                    }
                } else if (cue == null && lastDubbingCueStartMs != -1L) {
                    textToSpeech?.stop()
                    lastDubbingCueStartMs = -1L
                }
            }

            val pos = formatSec(t)
            val dur = formatSec(d)
            val state = if (p) "⏸" else "▶"
            val srtInfo = if (repo.isEmpty) "SRT: —"
            else "SRT: ${repo.size} → #${cue?.index ?: "—"}"

            val offSign = if (repo.offsetMs >= 0) "+" else ""
            val offStr = "$offSign${"%.1f".format(repo.offsetMs / 1000.0)}s"

            val savedInfo = "💾 ${storage.count()}"

            statusText.text = "video: $pos / $dur  $state  rs=$r\n" +
                "$srtInfo  ov=${if (overlayOn) "ON" else "OFF"}  ⏱ $offStr\n" +
                "t=${"%.2f".format(t)}s  $savedInfo  tick=${System.currentTimeMillis() % 100000}"
        } catch (e: Exception) {
            statusText.text = "تعذر التحليل: ${e.message}"
        }
    }

    private fun translationRouteLabel(): String = when {
        settings.translationProvider == SubtitleSettings.TRANSLATION_OMNIROUTE -> "OmniRoute أولاً ثم Gemini ثم البدائل النصية"
        settings.translationProvider == SubtitleSettings.TRANSLATION_GEMINI && settings.omniRouteEnabled -> "Gemini أولاً ثم OmniRoute ثم البدائل النصية"
        settings.translationProvider == SubtitleSettings.TRANSLATION_GEMINI -> "Gemini ثم البدائل النصية"
        settings.omniRouteEnabled -> "Gemini أولاً ثم OmniRoute ثم البدائل النصية"
        else -> "Gemini ثم البدائل النصية المتاحة"
    }

    private fun requestDubbingWavExport() {
        if (repo.isEmpty) {
            Toast.makeText(this, "حمّل أو أنشئ SRT أولاً قبل تصدير الدبلجة", Toast.LENGTH_LONG).show()
            return
        }
        if (!ttsReady || textToSpeech == null) {
            Toast.makeText(this, "الصوت العربي غير جاهز. نزّل صوتًا عربيًا من إعدادات تحويل النص إلى كلام في الهاتف.", Toast.LENGTH_LONG).show()
            return
        }
        val create = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/wav"
            putExtra(Intent.EXTRA_TITLE, "SariSubtitle_Arabic_Dubbing.wav")
        }
        startActivityForResult(create, REQ_SAVE_DUBBING_WAV)
    }

    private fun exportDubbingWav(uri: Uri) {
        val operationStarted = android.os.SystemClock.elapsedRealtime()
        val exportCues = repo.allCues()
        if (exportCues.isEmpty()) {
            Toast.makeText(this, "تعذر استعادة مقاطع SRT للتصدير؛ أعد تحميل ملف الترجمة", Toast.LENGTH_LONG).show()
            return
        }
        if (dubbingEnabled) {
            dubbingEnabled = false
            lastDubbingCueStartMs = -1L
            textToSpeech?.stop()
            findViewById<Button>(R.id.dubbingButton).text = getString(R.string.btn_dubbing)
            pollCurrentTimeOnce()
        }
        recordOperation("تصدير دبلجة WAV", "Android TextToSpeech", textToSpeech?.defaultEngine ?: "unknown",
            operationStarted, "قيد التنفيذ", "${exportCues.size} مقطع", "voice=${textToSpeech?.voice?.name ?: "unknown"}")
        val dialog = ProgressDialog(this).apply {
            setTitle("تصدير الدبلجة العربية")
            setMessage("جارٍ توليد مقاطع WAV مع توقيتات SRT…")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }
        Thread {
            try {
                val output = contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("تعذر فتح الملف المحدد للحفظ")
                output.use { stream ->
                    WavDubbingExporter.export(
                        tts = textToSpeech ?: throw IllegalStateException("محرك TTS غير جاهز"),
                        cues = exportCues,
                        cacheDir = cacheDir,
                        output = stream,
                        onProgress = { done, total, detail -> runOnUiThread {
                            if (!isFinishing) dialog.setMessage("توليد الدبلجة: $done / $total\n$detail")
                        } }
                    )
                }
                // Re-open the exact SAF document after closing the writer. A successful write alone is not enough.
                val readback = File(cacheDir, "sari_dub_readback_${System.currentTimeMillis()}.wav")
                try {
                    val input = contentResolver.openInputStream(uri) ?: throw IOException("تعذر إعادة فتح WAV المحفوظ لفحصه")
                    input.use { src -> readback.outputStream().buffered().use { dst -> src.copyTo(dst) } }
                    val validation = WavDubbingExporter.validateWav(readback)
                    val engineName = textToSpeech?.defaultEngine ?: "محرك TTS غير معروف"
                    val voiceName = textToSpeech?.voice?.name ?: "صوت غير معروف"
                    recordOperation("تصدير دبلجة WAV", "Android TextToSpeech", "$engineName / $voiceName", operationStarted,
                        "نجاح — تم التحقق", "${readback.length()} بايت؛ ${exportCues.size} مقطع", validation)
                } finally { readback.delete() }
                runOnUiThread {
                    if (!isFinishing) {
                        dialog.dismiss()
                        statusText.visibility = android.view.View.VISIBLE
                        statusText.text = "تم حفظ WAV واجتاز فحص RIFF/WAVE وPCM-16 بعد إعادة فتح الملف."
                        Toast.makeText(this, "تم حفظ ملف الدبلجة WAV", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SariDubbingExport", "WAV export failed or readback validation failed", e)
                recordOperation("تصدير دبلجة WAV", "Android TextToSpeech", textToSpeech?.defaultEngine ?: "unknown",
                    operationStarted, "فشل", "", (e.message ?: "خطأ غير معروف").take(400))
                runOnUiThread {
                    if (!isFinishing) {
                        dialog.dismiss()
                        Toast.makeText(this, "فشل تصدير الدبلجة: ${e.message ?: "خطأ غير معروف"}", Toast.LENGTH_LONG).show()
                        statusText.text = "فشل تصدير الدبلجة: ${e.message ?: "راجع Logcat: SariDubbingExport"}"
                    }
                }
            }
        }.start()
    }

    private fun toggleArabicDubbing() {
        if (repo.isEmpty) {
            Toast.makeText(this, "حمّل أو أنشئ ترجمة SRT أولاً، ثم شغّل الدبلجة", Toast.LENGTH_LONG).show()
            return
        }
        if (!ttsReady) {
            Toast.makeText(this, "الصوت العربي غير جاهز. نزّل بيانات صوت عربية من إعدادات تحويل النص إلى كلام في الهاتف ثم أعد المحاولة.", Toast.LENGTH_LONG).show()
            return
        }
        dubbingEnabled = !dubbingEnabled
        lastDubbingCueStartMs = -1L
        if (!dubbingEnabled) { textToSpeech?.stop(); textToSpeech?.setSpeechRate(1.0f) }
        findViewById<Button>(R.id.dubbingButton).text = getString(if (dubbingEnabled) R.string.btn_stop_dubbing else R.string.btn_dubbing)
        statusText.visibility = android.view.View.VISIBLE
        statusText.text = if (dubbingEnabled) "الدبلجة العربية تعمل. تم كتم صوت الفيديو الأصلي. اضغط مطولاً على زر الدبلجة لإظهار/إخفاء النص." else "تم إيقاف الدبلجة وإعادة صوت الفيديو إلى حالته السابقة."
        pollCurrentTimeOnce()
    }

    private fun formatSec(s: Double): String {
        if (s.isNaN() || s.isInfinite() || s < 0) return "--:--"
        val total = s.toInt()
        val m = total / 60
        val sec = total % 60
        return "%02d:%02d".format(m, sec)
    }

    private fun recordOperation(operation: String, provider: String, model: String, startedAt: Long,
                                status: String, result: String = "", detail: String = "") {
        OperationLogStore.add(this, operation, provider, model,
            (android.os.SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L), status, result, detail)
        runOnUiThread { if (!isFinishing) refreshOperationLogPanel() }
    }

    private fun refreshOperationLogPanel() {
        if (!::operationLogText.isInitialized) return
        val entries = OperationLogStore.events(this)
        val latest = entries.firstOrNull()
        // The activity-level completion entry describes the selected route, not the provider
        // that actually answered. Prefer the latest provider-attempt event for that same SRT
        // operation when it occurred after the service's most recent start/cache event.
        val latestSrtServiceEvent = entries.firstOrNull { it.operation == "ترجمة SRT" }
        val latestProviderAttempt = entries.firstOrNull { it.operation == "محاولة مزود ترجمة SRT" }
        val translationSummary = latest?.operation in setOf("ترجمة SRT تلقائية", "ترجمة ملف SRT")
        val display = if (translationSummary && latestProviderAttempt != null && latestSrtServiceEvent != null &&
            latestProviderAttempt.time >= latestSrtServiceEvent.time) latestProviderAttempt else latest
        operationLogText.text = if (display == null) getString(R.string.operation_log_ready) else
            "${display.operation} · ${display.status} · ${display.provider}/${display.model} · ${display.durationMs}ms" +
                (if (display.result.isNotBlank()) " · ${display.result}" else "") +
                (if (display.detail.isNotBlank()) " · ${display.detail}" else "")
        showCompactOperationLog()
    }

    /** Show a one-line summary for 4.5 seconds and reserve space instead of covering video. */
    private fun showCompactOperationLog() {
        if (!::operationLogPanel.isInitialized || isFinishing || fullscreenMode) return
        handler.removeCallbacks(hideOperationLogRunnable)
        handler.removeCallbacks(liveQueueTick)
        liveSubtitleQueue.clear()
        operationLogPanel.visibility = android.view.View.VISIBLE
        updatePlaybackAreaForLogPanel()
        handler.postDelayed(hideOperationLogRunnable, 4_500L)
    }

    private fun updatePlaybackAreaForLogPanel() {
        if (!::webView.isInitialized) return
        if (fullscreenMode) {
            applyFullscreenPlaybackLayout(true)
            return
        }
        val showLog = ::operationLogPanel.isInitialized && operationLogPanel.visibility == View.VISIBLE
        fun update(view: View) {
            val params = view.layoutParams as? RelativeLayout.LayoutParams ?: return
            params.removeRule(RelativeLayout.ABOVE)
            params.addRule(RelativeLayout.ABOVE, if (showLog) R.id.operationLogPanel else R.id.bottomBar)
            params.removeRule(RelativeLayout.BELOW)
            params.addRule(RelativeLayout.BELOW, R.id.topBar)
            view.layoutParams = params
        }
        update(webView)
        update(findViewById(R.id.webContainer))
    }

    /** Updates the visible status area from workflows that restore saved subtitles. */
    private fun showStatus(message: String) {
        statusText.text = message
        if (!fullscreenMode) statusText.visibility = View.VISIBLE
    }

    /** Handles blob:/data: downloads initiated by a page inside the WebView. */
    private fun downloadBlobViaJs(url: String, mimeType: String?) {
        val safeUrl = JSONObject.quote(url)
        val js = """
            (function(){
              try {
                fetch($safeUrl).then(function(r){ return r.blob(); }).then(function(b){
                  var fr = new FileReader();
                  fr.onloadend = function(){
                    var s = String(fr.result || '');
                    var p = s.indexOf(',');
                    window.__sariBlobResult = JSON.stringify({data: p >= 0 ? s.substring(p+1) : '', type: b.type || ''});
                  };
                  fr.readAsDataURL(b);
                }).catch(function(e){ window.__sariBlobResult = JSON.stringify({error: String(e)}); });
              } catch(e) { window.__sariBlobResult = JSON.stringify({error: String(e)}); }
            })();
        """.trimIndent()
        webView.evaluateJavascript("window.__sariBlobResult = null; $js", null)
        val deadline = System.currentTimeMillis() + 120_000L
        val poll = object : Runnable {
            override fun run() {
                webView.evaluateJavascript("window.__sariBlobResult || null") { result ->
                    try {
                        val decoded = org.json.JSONTokener(result ?: "null").nextValue()
                        if (decoded !is String) {
                            if (System.currentTimeMillis() < deadline) handler.postDelayed(this, 250L)
                            else Toast.makeText(this@SariSubtitleActivity, "انتهت مهلة تجهيز الملف من صفحة الويب", Toast.LENGTH_LONG).show()
                            return@evaluateJavascript
                        }
                        val payload = JSONObject(decoded)
                        if (payload.has("error")) {
                            Toast.makeText(this@SariSubtitleActivity, "فشل تجهيز الملف: ${payload.optString("error")}", Toast.LENGTH_LONG).show()
                            return@evaluateJavascript
                        }
                        val data = payload.optString("data")
                        if (data.isBlank()) {
                            Toast.makeText(this@SariSubtitleActivity, "الملف فارغ أو تعذر قراءته", Toast.LENGTH_LONG).show()
                            return@evaluateJavascript
                        }
                        val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                        val type = payload.optString("type").ifBlank { mimeType ?: "application/octet-stream" }
                        val guessed = URLUtil.guessFileName("download", null, type)
                            .replace(Regex("[^A-Za-z0-9._-]"), "_")
                            .ifBlank { "download.bin" }
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, guessed)
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, type)
                            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val target = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: throw IOException("تعذر إنشاء الملف في مجلد التنزيلات")
                        contentResolver.openOutputStream(target)?.use { it.write(bytes) }
                            ?: throw IOException("تعذر الكتابة إلى الملف")
                        showStatus("اكتمل التنزيل: $guessed")
                        Toast.makeText(this@SariSubtitleActivity, "تم حفظ الملف في التنزيلات: $guessed", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@SariSubtitleActivity, "فشل حفظ الملف: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        handler.postDelayed(poll, 250L)
        showStatus("جارٍ تجهيز الملف الذي طلبت صفحة الويب تنزيله…")
    }

    override fun onStart() {
        super.onStart()
        registerLiveReceiver()
    }

    override fun onStop() {
        handler.removeCallbacks(liveQueueTick)
        unregisterLiveReceiver()
        super.onStop()
    }

    private fun registerLiveReceiver() {
        if (liveReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(LiveSubtitleBus.ACTION_CUE)
            addAction(LiveSubtitleBus.ACTION_STATUS)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(liveSubtitleReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(liveSubtitleReceiver, filter)
        }
        liveReceiverRegistered = true
    }

    private fun unregisterLiveReceiver() {
        if (!liveReceiverRegistered) return
        try {
            unregisterReceiver(liveSubtitleReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was already removed by the framework.
        }
        liveReceiverRegistered = false
    }

    /**
     * Stage 42 live capture entry point.
     * Long-press the main translation button to request MediaProjection consent.
     */
    private fun toggleLiveCapture() {
        if (LiveCaptureService.isRunning) {
            LiveCaptureService.stop(this)
            statusText.text = "جارٍ إيقاف الترجمة المباشرة…"
            Toast.makeText(this, "تم طلب إيقاف الالتقاط المباشر", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "الالتقاط المباشر لصوت التطبيقات يحتاج Android 10 أو أحدث", Toast.LENGTH_LONG).show()
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_LIVE_AUDIO_PERMISSION)
            return
        }
        requestLiveMediaProjection()
    }

    private fun requestLiveMediaProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            Toast.makeText(this, "MediaProjection غير متاح على هذا الجهاز", Toast.LENGTH_LONG).show()
            return
        }
        statusText.visibility = android.view.View.VISIBLE
        statusText.text = "امنح Sari Subtitle إذن التقاط صوت الوسائط لبدء الترجمة المباشرة…"
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_LIVE_MEDIA_PROJECTION)
    }

    private fun startLiveCaptureService(resultCode: Int, data: Intent) {
        val language = "auto"
        val provider = settings.sttProvider
        try {
            LiveCaptureService.start(this, resultCode, data, language, provider)
            overlayOn = true
            subtitlesVisible = true
            statusText.visibility = android.view.View.VISIBLE
            statusText.text = "الترجمة المباشرة: جارٍ بدء التقاط صوت الوسائط…"
        } catch (t: Throwable) {
            statusText.text = "تعذر بدء الالتقاط المباشر: ${t.message ?: t.javaClass.simpleName}"
            Toast.makeText(this, "تعذر بدء الالتقاط المباشر", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleLiveCue(intent: Intent) {
        val text = intent.getStringExtra(LiveSubtitleBus.EXTRA_TEXT).orEmpty().trim()
        if (text.isBlank()) return
        val provider = intent.getStringExtra(LiveSubtitleBus.EXTRA_PROVIDER).orEmpty()
        val translationProvider = intent.getStringExtra(LiveSubtitleBus.EXTRA_TRANSLATION_PROVIDER).orEmpty()
        val sessionId = intent.getLongExtra(LiveSubtitleBus.EXTRA_SESSION_ID, Long.MIN_VALUE)
        val sequence = intent.getLongExtra(LiveSubtitleBus.EXTRA_SEQUENCE, Long.MIN_VALUE)
        val revision = intent.getIntExtra(LiveSubtitleBus.EXTRA_REVISION, 0)
        val isFinal = intent.getBooleanExtra(LiveSubtitleBus.EXTRA_IS_FINAL, true)
        // Stage 46: never expose an untranslated foreign-language partial as if it were
        // an Arabic subtitle. FINAL cues are already translated by the service. Arabic
        // partials may still be useful because they need no translation network request.
        if (!isFinal && !isArabicLiveText(text)) return
        if (sessionId != Long.MIN_VALUE && sessionId != lastLiveSessionId) {
            lastLiveSessionId = sessionId
            lastLiveSequence = Long.MIN_VALUE
            lastLiveRevision = -1
            liveSubtitleQueue.beginSession(sessionId)
        }
        if (sequence != Long.MIN_VALUE) {
            if (sequence < lastLiveSequence) return
            if (sequence == lastLiveSequence && revision < lastLiveRevision) return
            lastLiveSequence = maxOf(lastLiveSequence, sequence)
            if (sequence == lastLiveSequence) lastLiveRevision = maxOf(lastLiveRevision, revision)
        }
        val startMs = intent.getLongExtra(LiveSubtitleBus.EXTRA_START_MS, 0L)
        val endMs = intent.getLongExtra(LiveSubtitleBus.EXTRA_END_MS, startMs + 1_500L)
        val displayUntilMs = intent.getLongExtra(
            LiveSubtitleBus.EXTRA_DISPLAY_UNTIL_MS,
            endMs
        )
        val captureStarted = intent.getLongExtra(LiveSubtitleBus.EXTRA_CAPTURE_STARTED_ELAPSED_MS, 0L)

        overlayOn = true
        subtitlesVisible = true

        val item = LiveSubtitleQueue.Item(
            sessionId = sessionId,
            sequence = sequence,
            revision = revision,
            text = text,
            startMs = startMs,
            endMs = endMs,
            displayUntilMs = displayUntilMs
        )
        if (!liveSubtitleQueue.offer(item)) return

        val route = if (translationProvider.isBlank()) provider else "${provider.substringBefore('→')}→$translationProvider"
        statusText.visibility = android.view.View.VISIBLE
        statusText.text = "مباشر • ${if (isFinal) "FINAL" else "PARTIAL"} • ${if (route.isBlank()) "STT→AR" else route} • ${endMs - startMs}ms • queue=${liveSubtitleQueue.size()}"
        scheduleLiveSubtitleQueue(captureStarted)
    }

    private fun isArabicLiveText(text: String): Boolean {
        val arabic = text.count { it in '\u0600'..'\u06FF' }
        val latin = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        return arabic >= 2 && arabic >= latin
    }

    private var liveQueueCaptureStartedElapsedMs = 0L
    private val liveQueueTick = object : Runnable {
        override fun run() {
            if (isFinishing || !overlayOn || !subtitlesVisible) return
            val captureStarted = liveQueueCaptureStartedElapsedMs
            val now = if (captureStarted > 0L) {
                (android.os.SystemClock.elapsedRealtime() - captureStarted).coerceAtLeast(0L)
            } else {
                0L
            }
            when (val action = liveSubtitleQueue.advance(now)) {
                is LiveSubtitleQueue.Action.Show -> subtitleOverlay.show(action.item.text)
                LiveSubtitleQueue.Action.Hide -> subtitleOverlay.hide()
                LiveSubtitleQueue.Action.Keep -> Unit
            }
            if (LiveCaptureService.isRunning) {
                handler.postDelayed(this, 50L)
            }
        }
    }

    private fun scheduleLiveSubtitleQueue(captureStarted: Long) {
        if (captureStarted > 0L) liveQueueCaptureStartedElapsedMs = captureStarted
        handler.removeCallbacks(liveQueueTick)
        handler.post(liveQueueTick)
    }

    private fun handleLiveStatus(intent: Intent) {
        val status = intent.getStringExtra(LiveSubtitleBus.EXTRA_STATUS).orEmpty()
        val detail = intent.getStringExtra(LiveSubtitleBus.EXTRA_DETAIL).orEmpty()
        when (status) {
            "capture_started" -> {
                val sessionId = intent.getLongExtra(LiveSubtitleBus.EXTRA_SESSION_ID, Long.MIN_VALUE)
                if (sessionId != Long.MIN_VALUE && sessionId != lastLiveSessionId) {
                    lastLiveSessionId = sessionId
                    lastLiveSequence = Long.MIN_VALUE
                    lastLiveRevision = -1
                    liveSubtitleQueue.beginSession(sessionId)
                }
                statusText.text = "🟢 الترجمة المباشرة تعمل — يتم التقاط صوت الوسائط"
            }
            "capture_stopped" -> {
                statusText.text = "تم إيقاف الترجمة المباشرة"
                subtitleOverlay.hide()
            }
            "backpressure" -> statusText.text = "مباشر: تم إسقاط جزء قديم للحفاظ على التأخير المنخفض"
            "silence" -> { /* silence is intentionally not shown as an error */ }
            "provider_error" -> statusText.text = "مباشر: تعذر تفريغ جزء صوتي — سيستمر الالتقاط"
            "duplicate_suppressed" -> { /* overlap deduplication is expected */ }
            "translation_success" -> { /* cue itself carries the visible translation */ }
            "translation_error" -> statusText.text = "مباشر: تعذرت ترجمة جزء — سيستمر الالتقاط"
            "translation_backpressure" -> statusText.text = "مباشر: تم تجاوز ترجمة قديمة للحفاظ على التأخير المنخفض"
            "translation_stale" -> { /* stale translation is intentionally suppressed */ }
            "timeline_stale" -> { /* old timeline cue is intentionally suppressed */ }
            "timeline_trimmed" -> { /* previous cue was shortened to avoid overlap */ }
            "capture_error" -> statusText.text = "خطأ في التقاط صوت الوسائط: $detail"
            else -> if (detail.isNotBlank()) statusText.text = "مباشر: $detail"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LIVE_AUDIO_PERMISSION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            requestLiveMediaProjection()
        } else {
            Toast.makeText(this, "يجب السماح بإذن الصوت لتفعيل الالتقاط المباشر", Toast.LENGTH_LONG).show()
            statusText.text = "تم رفض إذن RECORD_AUDIO؛ لم يبدأ الالتقاط."
        }
    }

    /**
     * Fullscreen mode keeps the existing WebView + SubtitleOverlay intact and only changes
     * the surrounding chrome. No subtitle timing/state is modified.
     */
    private fun setFullscreenMode(enabled: Boolean) {
        if (fullscreenMode == enabled) return
        fullscreenMode = enabled

        val topBar = findViewById<View>(R.id.topBar)
        val bottomBar = findViewById<View>(R.id.bottomBar)
        val status = findViewById<View>(R.id.statusText)
        val logPanel = findViewById<View>(R.id.operationLogPanel)

        if (enabled) {
            restoreOperationLogOnFullscreenExit = logPanel.visibility == View.VISIBLE
            applyPreferredOrientation()
            topBar.visibility = View.GONE
            bottomBar.visibility = View.GONE
            status.visibility = View.GONE
            logPanel.visibility = View.GONE
            applyFullscreenPlaybackLayout(true)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            WindowCompat.setDecorFitsSystemWindows(window, true)
            topBar.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            if (restoreOperationLogOnFullscreenExit) {
                logPanel.visibility = View.VISIBLE
            }
            restoreOperationLogOnFullscreenExit = false
            applyFullscreenPlaybackLayout(false)
            // العودة للوضع العمودي عند مغادرة Fullscreen، مع إبقاء تفضيل المستخدم محفوظاً.
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            updateRotationButton()
        }
    }

    private fun applyPreferredOrientation() {
        if (restoringOrientation || isFinishing || !::settings.isInitialized) return
        restoringOrientation = true
        try {
            setRequestedOrientation(
                if (settings.preferredLandscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            )
        } finally {
            restoringOrientation = false
        }
        updateRotationButton()
    }

    private fun toggleScreenOrientation() {
        if (!::settings.isInitialized) return
        val nextLandscape = !settings.preferredLandscape
        settings.preferredLandscape = nextLandscape
        setRequestedOrientation(
            if (nextLandscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        )
        updateRotationButton()
    }

    private fun updateRotationButton() {
        if (!::settings.isInitialized) return
        findViewById<Button>(R.id.rotationButton)?.contentDescription =
            if (settings.preferredLandscape) {
                "تدوير الشاشة: العرض الأفقي مفعّل؛ اضغط للعودة إلى العمودي"
            } else {
                "تدوير الشاشة: الوضع العمودي مفعّل؛ اضغط للعرض الأفقي"
            }
    }

    private fun applyFullscreenPlaybackLayout(fullscreen: Boolean) {
        if (!::webView.isInitialized) return
        fun update(view: View) {
            val params = view.layoutParams as? RelativeLayout.LayoutParams ?: return
            params.removeRule(RelativeLayout.ABOVE)
            params.removeRule(RelativeLayout.BELOW)
            if (fullscreen) {
                params.addRule(RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.TRUE)
                params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE)
            } else {
                params.removeRule(RelativeLayout.ALIGN_PARENT_TOP)
                params.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                params.addRule(RelativeLayout.BELOW, R.id.topBar)
                val showLog = ::operationLogPanel.isInitialized && operationLogPanel.visibility == View.VISIBLE
                params.addRule(RelativeLayout.ABOVE, if (showLog) R.id.operationLogPanel else R.id.bottomBar)
            }
            view.layoutParams = params
        }
        update(webView)
        update(findViewById(R.id.webContainer))
        webView.requestLayout()
        findViewById<View>(R.id.webContainer).requestLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::subtitleOverlay.isInitialized) {
            subtitleOverlay.applySettings(SubtitleSettings(this))
        }
        if (::webView.isInitialized) webView.requestLayout()
        if (fullscreenMode) applyFullscreenPlaybackLayout(true) else updatePlaybackAreaForLogPanel()
        updateRotationButton()
    }

    override fun onBackPressed() {
        if (fullscreenMode) {
            setFullscreenMode(false)
            return
        }
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (::settings.isInitialized && ::subtitleOverlay.isInitialized) {
            settings = SubtitleSettings(this)
            subtitleOverlay.applySettings(settings)
            updateRotationButton()
        }
    }

    override fun onPause() {
        saveCurrentPlaybackPosition()
        super.onPause()
    }

    override fun onDestroy() {
        saveCurrentPlaybackPosition()
        handler.removeCallbacks(hideOperationLogRunnable)
        handler.removeCallbacks(liveQueueTick)
        liveSubtitleQueue.clear()
        OperationLogStore.onChanged = null
        activeOperationCancellation?.cancel()
        try { textToSpeech?.stop(); textToSpeech?.shutdown() } catch (_: Exception) {}
        textToSpeech = null
        polling = false
        handler.removeCallbacksAndMessages(null)
        webView.evaluateJavascript(
            "if (window.__sariAutoShowInterval) clearInterval(window.__sariAutoShowInterval);",
            null
        )
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
