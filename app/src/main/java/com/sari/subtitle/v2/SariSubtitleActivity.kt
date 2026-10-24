package com.sari.subtitle.v2

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.IOException

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
        private const val REQ_SAVE_WHISPER_SRT = 1005
        private const val REQ_SUBWHISPER_INTERNAL = 1006
        private const val REQ_PICK_SRT = 1001
        private const val REQ_PICK_SRT_TO_TRANSLATE = 1002
        private const val REQ_TRANSLATE_SRT_PICK = 1002
        private const val REQ_SAVE_TRANSLATED_SRT = 1003
        private const val OFFSET_STEP_MS = 500L
    }

    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var webView: WebView
    private lateinit var subtitleOverlay: SubtitleOverlay

    private lateinit var settings: SubtitleSettings
    private var pendingWhisperSrt: String? = null
    private lateinit var storage: SrtStorage

    private val handler = Handler(Looper.getMainLooper())
    private val repo = SubtitleRepository()
    private var polling = false
    private var overlayOn = false

    /** آخر SRT نصي محمّل (للحفظ عند الحاجة) */
    private var lastSrtText: String? = null

    /** URL الذي تم تحميل SRT من أجله */
    private var lastSrtUrl: String? = null
    private var pendingSourceLang = "en"
    private var pendingTargetLang = "ar"
    private var pendingTranslatedSrt: String? = null
    /** URL requested by the one-tap workflow; capture begins after WebView loads it. */
    private var pendingAutoCaptureUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = SubtitleSettings(this)
        storage = SrtStorage(this)
        repo.offsetMs = settings.offsetMs

        urlInput = findViewById(R.id.urlInput)
        statusText = findViewById(R.id.statusText)
        webView = findViewById(R.id.webView)
        subtitleOverlay = findViewById(R.id.subtitleOverlay)

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
        WebView.setWebContentsDebuggingEnabled(true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectAutoShow()
                // ⚡ محاولة استرجاع SRT لهذا URL
                if (url != null) tryAutoLoadSrt(url)
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
                android.util.Log.d("SariDownload", "Download request: $url (mime=$mimeType, length=$contentLength)")
                
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
        findViewById<Button>(R.id.menuButton).setOnClickListener {
            startPrimaryTranslationFlow()
        }

        // واجهة المستخدم الرئيسية تعرض الوظائف الأساسية فقط؛ المحفوظات لا تحتاج إعادة تفريغ.
        findViewById<Button>(R.id.savedItemsButton).setOnClickListener {
            showSavedSubtitles()
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
            SettingsDialog.show(this, settings, subtitleOverlay) {
                Toast.makeText(this, "✅ تم حفظ الإعدادات", Toast.LENGTH_SHORT).show()
            }
        }

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
        val sharedUrl = extractSharedUrl(intent)
        if (sharedUrl != null) {
            urlInput.setText(sharedUrl)
            webView.loadUrl(sharedUrl)
            statusText.text = "تم استلام رابط مشاركته مع Sari Subtitle. اضغط «إنشاء SRT من رابط» لإنشاء الترجمة العربية."
        } else {
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
            if (!overlayOn) overlayOn = true
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * عند فتح URL جديد — إن كان له SRT محفوظ، حمّله تلقائياً.
     */
    private fun tryAutoLoadSrt(url: String) {
        if (lastSrtUrl == url) return
        if (!storage.has(url)) return
        if (restoreCachedSrt(url)) {
            runOnUiThread {
                Toast.makeText(this, "✅ تمت استعادة SRT المحفوظ: ${repo.size} مقطع", Toast.LENGTH_SHORT).show()
            }
        }
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
        val progress = ProgressDialog(this).apply {
            setTitle("إنشاء ملف SRT")
            setMessage("جارٍ تنزيل ملف الوسائط…")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }
        statusText.text = "جارٍ تنزيل ملف الوسائط من الرابط المباشر…"
        Thread {
            var temp: java.io.File? = null
            var connection: java.net.HttpURLConnection? = null
            try {
                connection = (java.net.URL(link).openConnection() as java.net.HttpURLConnection).apply {
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
                        val count = inputStream.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 24L * 1024L * 1024L) throw IOException("توقف التنزيل لأن الملف تجاوز 24 MB.")
                        output.write(buffer, 0, count)
                    }
                } }
                if (total == 0L) throw IOException("الملف الذي تم تنزيله فارغ.")
                runOnUiThread { progress.setMessage("تم تنزيل ${(total / 1024)} KB — جارٍ التفريغ عبر ${settings.sttProviderLabel}…") }
                val result = transcribeWithSelectedProvider(Uri.fromFile(temp!!), language)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
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
                temp?.delete()
            }
        }.start()
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
                val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
                val cues = SrtParser.parse(bytes.inputStream())
                if (cues.isEmpty()) throw IOException("الرابط لا يحتوي على مقاطع SRT صالحة. تأكد أنه رابط ملف مباشر وليس صفحة ويب.")
                runOnUiThread {
                    if (!isFinishing) {
                        repo.setCues(cues)
                        lastSrtText = text
                        val videoUrl = webView.url ?: urlInput.text.toString().trim()
                        if (videoUrl.startsWith("http")) {
                            storage.save(videoUrl, text, cues.size)
                            lastSrtUrl = videoUrl
                        }
                        overlayOn = true
                        statusText.text = "تم تحميل SRT من رابط مباشر: ${cues.size} مقطع${if (videoUrl.startsWith("http")) " — حُفظ للفيديو الحالي" else ""}"
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

    /** Translate a fetched/generated SRT to Arabic, preserve cue timestamps, show it, and cache it. */
    private fun translateAndShowSrtAutomatically(videoUrl: String, sourceSrt: String, fileName: String) {
        val progress = ProgressDialog(this).apply {
            setTitle("الترجمة العربية")
            setMessage("جارٍ اكتشاف اللغة ثم الترجمة باستخدام Gemini/المزوّد المتاح…")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }
        Thread {
            try {
                val translated = SrtTranslationService.translateAuto(
                    source = sourceSrt,
                    targetLang = "ar",
                    context = applicationContext,
                    onDetected = { lang -> runOnUiThread { if (!isFinishing) progress.setMessage("لغة المصدر: $lang — جارٍ الترجمة إلى العربية…") } },
                    onProgress = { done, total -> runOnUiThread { if (!isFinishing) progress.setMessage("جارٍ ترجمة المقاطع: $done / $total") } }
                )
                val cues = SrtParser.parse(translated.byteInputStream(Charsets.UTF_8))
                if (cues.isEmpty()) throw IllegalStateException("لم ينتج ملف SRT صالح")
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    progress.dismiss()
                    repo.setCues(cues)
                    lastSrtText = translated
                    lastSrtUrl = videoUrl
                    pendingWhisperSrt = translated
                    storage.save(videoUrl, translated, cues.size)
                    storage.saveLibraryItem("ar_${fileName.removeSuffix(".srt")}.srt", translated, cues.size)
                    overlayOn = true
                    statusText.visibility = android.view.View.VISIBLE
                    statusText.text = "اكتملت الترجمة العربية: ${cues.size} مقطع. حُفظت لإعادة الاستخدام."
                    pollCurrentTimeOnce()
                    Toast.makeText(this, "اكتملت الترجمة العربية وحُفظت داخل التطبيق", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        progress.dismiss()
                        statusText.text = "عُثر على SRT لكن تعذرت ترجمته تلقائياً: ${e.message ?: "خطأ غير معروف"}"
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("تعذرت الترجمة العربية")
                            .setMessage("حُفظت التوقيتات الأصلية، لكن الترجمة لم تكتمل. تحقق من مفتاح Gemini والإنترنت ثم أعد المحاولة.\n\n${e.message ?: "خطأ غير معروف"}")
                            .setPositiveButton("حسنًا", null)
                            .show()
                    }
                }
            }
        }.start()
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
        val raw = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) { null }
        if (raw.isNullOrBlank()) {
            Toast.makeText(this, "تعذّر قراءة ملف SRT", Toast.LENGTH_LONG).show()
            return
        }
        val progress = ProgressDialog(this).apply {
            setTitle("ترجمة ملف SRT")
            setMessage("جارٍ بدء الترجمة…")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }
        Thread {
            try {
                val translated = SrtTranslationService.translateSrt(raw, pendingSourceLang, pendingTargetLang, applicationContext) { done, total ->
                    runOnUiThread { progress.setMessage("جارٍ ترجمة المقاطع: $done / $total") }
                }
                pendingTranslatedSrt = translated
                runOnUiThread {
                    progress.dismiss()
                    val create = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        setType("application/x-subrip")
                        putExtra(Intent.EXTRA_TITLE, "translated_${pendingTargetLang}.srt")
                    }
                    startActivityForResult(create, REQ_SAVE_TRANSLATED_SRT)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.dismiss()
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("تعذرت الترجمة")
                        .setMessage("${e.message ?: "خطأ غير معروف"}\n\nتحقق من الإنترنت ورمز اللغة وحدود الخدمة المجانية، ثم حاول مرة أخرى.")
                        .setPositiveButton("حسنًا", null)
                        .show()
                }
            }
        }.start()
    }

    private fun saveTranslatedSrt(uri: Uri) {
        val translated = pendingTranslatedSrt ?: run {
            Toast.makeText(this, "لا يوجد ملف مترجم للحفظ", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(translated.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException("تعذّر فتح الملف للحفظ")
            pendingTranslatedSrt = null
            Toast.makeText(this, "تم حفظ ملف SRT المترجم مع الحفاظ على التوقيتات", Toast.LENGTH_LONG).show()
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
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
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
            lastSrtUrl = webView.url?.takeIf { it.startsWith("http") }
            overlayOn = true
            if (!lastSrtUrl.isNullOrBlank()) storage.save(lastSrtUrl!!, raw, cues.size)
            val sourceName = getSrtDisplayName(uri).ifBlank { "ترجمة مستوردة.srt" }
            storage.saveLibraryItem(sourceName, raw, cues.size)
            statusText.visibility = android.view.View.VISIBLE
            statusText.text = "تم تحميل الترجمة العربية: ${cues.size} مقطع. حُفظت في ملفاتي مع التوقيتات الأصلية."
            Toast.makeText(this, "تم تحميل الترجمة وحفظها في ملفاتي", Toast.LENGTH_SHORT).show()
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
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) { null }

        if (raw.isNullOrBlank()) {
            Toast.makeText(this, "تعذّر قراءة ملف SRT", Toast.LENGTH_LONG).show()
            return
        }

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
            progress.getButton(ProgressDialog.BUTTON_NEGATIVE)?.isEnabled = false
            progress.setMessage("جارٍ الإيقاف بعد انتهاء طلب الترجمة الجاري…")
        }

        Thread {
            try {
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
                    shouldCancel = { cancelRequested.get() }
                )
                if (cancelRequested.get()) throw java.util.concurrent.CancellationException("ألغى المستخدم العملية")

                pendingTranslatedSrt = translated
                pendingTargetLang = "ar"
                val translatedCues = SrtParser.parse(translated.byteInputStream(Charsets.UTF_8))
                if (translatedCues.isEmpty()) throw IllegalStateException("لم ينتج ملف SRT صالح بعد الترجمة")

                runOnUiThread {
                    progress.dismiss()
                    repo.setCues(translatedCues)
                    lastSrtText = translated
                    overlayOn = true
                    val currentUrl = webView.url?.takeIf { it.startsWith("http") }
                        ?: urlInput.text.toString().trim().takeIf { it.startsWith("http") }
                    if (!currentUrl.isNullOrBlank()) {
                        storage.save(currentUrl, translated, translatedCues.size)
                        lastSrtUrl = currentUrl
                    }
                    val originalName = getSrtDisplayName(uri).ifBlank { "subtitle.srt" }
                    val translatedName = "ar_${originalName.substringAfterLast('/').removeSuffix(".srt").removeSuffix(".SRT")}.srt"
                    val libraryId = storage.saveLibraryItem(translatedName, translated, translatedCues.size)
                    if (libraryId == null) android.util.Log.w("SariSrt", "Could not persist translated SRT in local library")
                    statusText.visibility = android.view.View.VISIBLE
                    statusText.text = "اكتملت الترجمة العربية: ${translatedCues.size} مقطع. حُفظت في ملفاتي والتوقيتات محفوظة."
                    pollCurrentTimeOnce()
                    Toast.makeText(this, "اكتملت الترجمة وحُفظت تلقائيًا في ملفاتي", Toast.LENGTH_LONG).show()
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("اكتملت الترجمة")
                        .setMessage("تمت ترجمة ${translatedCues.size} مقطعًا، وحُفظ الملف داخل التطبيق. يمكنك فتحه لاحقًا من «ملفاتي». هل تريد أيضًا تصدير نسخة إلى مجلد الهاتف؟")
                        .setNegativeButton("ليس الآن", null)
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
        }.start()
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
        val recordDurationMs = (minOf(duration * 1000.0, 120000.0)).toInt()
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
                
                // 🔴 إلغاء الكتم
                v.muted = false;
                v.volume = 1.0;
                
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
        val progress = ProgressDialog(this).apply {
            setTitle("إنشاء الترجمة العربية")
            setMessage("جارٍ تجهيز الملف الصوتي…")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }
        statusText.visibility = android.view.View.VISIBLE
        statusText.text = "جارٍ تفريغ الملف الصوتي أو الفيديو ثم ترجمته إلى العربية…"
        Thread {
            var extractedAudio: java.io.File? = null
            try {
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
                val rawSrt = result.srt
                if (SrtParser.parse(rawSrt.byteInputStream(Charsets.UTF_8)).isEmpty()) {
                    throw IllegalStateException("لم ينتج التفريغ ملف SRT صالحاً")
                }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    progress.setMessage("اكتمل التفريغ — جارٍ ترجمة SRT إلى العربية…")
                    translateAndShowSrtAutomatically("local-media://${uri.lastPathSegment ?: System.currentTimeMillis()}", rawSrt, "downloaded-media-${System.currentTimeMillis()}.srt")
                    progress.dismiss()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        progress.dismiss()
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
        }.start()
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

        val progress = android.app.ProgressDialog(this).apply {
            setTitle("🌐 ${settings.sttProviderLabel}")
            setMessage("جارٍ التفريغ…")
            setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }

        val startTime = System.currentTimeMillis()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val timer = object : Runnable {
            override fun run() {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                progress.setMessage("🌐 جارٍ التفريغ…\nمضى: ${elapsed}s")
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(timer)

        Thread {
            try {
                val tempFile = java.io.File(cacheDir, "x_audio_${System.currentTimeMillis()}.webm")
                tempFile.writeBytes(audioBytes)

                val result = try {
                    transcribeWithSelectedProvider(android.net.Uri.fromFile(tempFile), "auto")
                } finally {
                    tempFile.delete()
                }

                handler.removeCallbacks(timer)
                runOnUiThread {
                    progress.dismiss()
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
                        // Route through SrtTranslationService whenever Gemini or OmniRoute routing is configured.
                        // This keeps OmniRoute selection/fallback available in the X-video translation path too.
                        val useTranslationRouter = settings.geminiApiKey.isNotBlank() || settings.omniRouteEnabled ||
                            settings.translationProvider == SubtitleSettings.TRANSLATION_OMNIROUTE
                        val useDeepL = !useTranslationRouter && deepLKey.isNotBlank()
                        
                        if (!isArabic && detectedLang.isNotBlank() && detectedLang != "au") {
                            val provider = when { useTranslationRouter -> if (settings.translationProvider == SubtitleSettings.TRANSLATION_OMNIROUTE) "OmniRoute/Gemini" else "Gemini/OmniRoute"; useDeepL -> "DeepL"; else -> "Google/MyMemory" }
                            val progress2 = android.app.ProgressDialog(this).apply {
                                setTitle("🌐 ترجمة → العربية ($provider)")
                                setMessage("جارٍ الترجمة…")
                                setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
                                setCancelable(false)
                                max = 100
                                show()
                            }
                            
                            Thread {
                                try {
                                    val translatedSrt = if (useTranslationRouter) {
                                        SrtTranslationService.translateSrt(
                                            source = result.srt,
                                            sourceLang = detectedLang,
                                            targetLang = "ar",
                                            context = applicationContext
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
                                            context = applicationContext
                                        ) { done, total ->
                                            runOnUiThread {
                                                progress2.progress = (done * 100 / total)
                                                progress2.setMessage("$provider: $done / $total")
                                            }
                                        }
                                    }
                                    
                                    val translatedCues = SrtParser.parse(translatedSrt.byteInputStream())
                                    runOnUiThread {
                                        progress2.dismiss()
                                        repo.setCues(translatedCues)
                                        lastSrtText = translatedSrt
                                        val videoUrlForCache = urlInput.text.toString().trim().ifBlank { webView.url.orEmpty() }
                                        val cacheSaved = if (videoUrlForCache.startsWith("http")) {
                                            lastSrtUrl = videoUrlForCache
                                            storage.save(videoUrlForCache, translatedSrt, translatedCues.size) != null
                                        } else false
                                        val librarySaved = storage.saveLibraryItem(
                                            "ar_transcription_${System.currentTimeMillis()}.srt", translatedSrt, translatedCues.size
                                        ) != null
                                        overlayOn = true
                                        statusText.text = when {
                                            cacheSaved -> "✅ SRT عربي: ${translatedCues.size} مقطع — حُفظت الترجمة لاستعادتها تلقائيًا"
                                            librarySaved && videoUrlForCache.startsWith("http") -> "⚠️ تمت الترجمة وحُفظ الملف في مكتبتي، لكن تعذّر حفظ ربطه بالفيديو؛ لن تُستعاد تلقائيًا لهذا الرابط."
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
                                        // فشل الترجمة — استخدم SRT الأصلي
                                        repo.setCues(cues)
                                        overlayOn = true
                                        statusText.text = "⚠️ الترجمة فشلت (${e.message}) — SRT أصلي"
                                        
                                        Toast.makeText(this, "⚠️ الترجمة فشلت: ${e.message}", Toast.LENGTH_LONG).show()
                                        
                                        val currentUrl = webView.url ?: urlInput.text.toString().trim()
                                        if (currentUrl.startsWith("http")) {
                                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                webView.loadUrl(currentUrl)
                                            }, 500)
                                        }
                                    }
                                }
                            }.start()
                        } else {
                            // SRT عربي بالفعل
                            repo.setCues(cues)
                            lastSrtText = result.srt
                            val videoUrlForCache = urlInput.text.toString().trim().ifBlank { webView.url.orEmpty() }
                            if (videoUrlForCache.startsWith("http")) {
                                lastSrtUrl = videoUrlForCache
                                storage.save(videoUrlForCache, result.srt, cues.size)
                            }
                            storage.saveLibraryItem("ar_transcription_${System.currentTimeMillis()}.srt", result.srt, cues.size)
                            overlayOn = true
                            statusText.text = "✅ SRT عربي: ${cues.size} مقطع — تم الحفظ"
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
        }.start()
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
        val cloudProviders = when (selected) {
            SubtitleSettings.STT_LOCAL -> emptyList()
            // المطلوب في الوضع التلقائي: Groq إن كان المفتاح صالحاً، ثم Vosk المحلي عند توفر النموذج.
            SubtitleSettings.STT_AUTO -> listOf(SubtitleSettings.STT_GROQ)
            SubtitleSettings.STT_DEEPGRAM -> listOf(SubtitleSettings.STT_DEEPGRAM, SubtitleSettings.STT_GROQ)
            SubtitleSettings.STT_GROQ -> listOf(SubtitleSettings.STT_GROQ, SubtitleSettings.STT_DEEPGRAM)
            else -> listOf(SubtitleSettings.STT_OPENAI)
        }.filter { provider -> when (provider) {
            SubtitleSettings.STT_DEEPGRAM -> settings.deepgramApiKey.isNotBlank()
            SubtitleSettings.STT_OPENAI -> settings.openAiApiKey.isNotBlank()
            else -> settings.groqApiKey.isNotBlank()
        } }
        val providers = if (selected == SubtitleSettings.STT_LOCAL) listOf(SubtitleSettings.STT_LOCAL)
            else cloudProviders + if (hasLocalModel) listOf(SubtitleSettings.STT_LOCAL) else emptyList()

        if (providers.isEmpty()) {
            val message = if (selected == SubtitleSettings.STT_AUTO)
                "لا يوجد مزود جاهز للوضع التلقائي. أضف مفتاح Groq أو Deepgram، أو نزّل نموذج Vosk المحلي من الإعدادات. لن يُستخدم OpenAI تلقائياً لأنه قد يتطلب دفعاً."
            else "لا يوجد مزود تفريغ جاهز. أضف مفتاح المزود المختار أو نزّل نموذج Vosk المحلي من الإعدادات."
            throw IllegalStateException(message)
        }
        val failures = mutableListOf<String>()
        for (provider in providers.distinct()) {
            try {
                val result = call(provider)
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
                val label = when (provider) {
                    SubtitleSettings.STT_DEEPGRAM -> "Deepgram"
                    SubtitleSettings.STT_OPENAI -> "OpenAI"
                    SubtitleSettings.STT_LOCAL -> "Vosk المحلي"
                    else -> "Groq"
                }
                failures += "$label: ${e.message ?: "فشل غير معروف"}"
            }
        }
        throw IllegalStateException("فشلت محاولات التفريغ المتاحة: ${failures.joinToString(" | ")}")
    }

    /** مكتبة موحدة لملفات SRT المستقلة وترجمات الفيديو المحفوظة. */
    private fun showSavedSubtitles() {
        data class LibraryRow(val label: String, val url: String? = null, val item: SavedSrtItem? = null)
        val rows = mutableListOf<LibraryRow>()
        storage.listLibraryItems().forEach { item ->
            rows += LibraryRow("📄 ${item.title}  •  ${item.cueCount} مقطع", item = item)
        }
        storage.listUrls().distinct().sorted().forEach { url ->
            rows += LibraryRow("🎬 $url", url = url)
        }
        if (rows.isEmpty()) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("ملفاتي")
                .setMessage("لا توجد ملفات محفوظة بعد. عند استيراد SRT أو ترجمته، سيحفظه التطبيق هنا تلقائيًا.")
                .setPositiveButton("حسنًا", null)
                .show()
            return
        }
        val list = ListView(this)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows.mapIndexed { i, row -> "${i + 1}. ${row.label}" })
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("ملفاتي (${rows.size}) — اضغط للفتح، واضغط مطولًا للحذف")
            .setView(list)
            .setNegativeButton("إغلاق", null)
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            val row = rows[position]
            if (row.item != null) {
                val srt = storage.loadLibraryItem(row.item.id)
                if (srt.isNullOrBlank()) {
                    Toast.makeText(this, "تعذّر فتح ملف الترجمة", Toast.LENGTH_LONG).show()
                } else {
                    val cues = try { SrtParser.parse(srt.byteInputStream(Charsets.UTF_8)) } catch (_: Exception) { emptyList() }
                    if (cues.isEmpty()) Toast.makeText(this, "ملف SRT غير صالح", Toast.LENGTH_LONG).show()
                    else {
                        repo.setCues(cues)
                        lastSrtText = srt
                        lastSrtUrl = null
                        overlayOn = true
                        statusText.visibility = android.view.View.VISIBLE
                        statusText.text = "تم فتح ${row.item.title}: ${cues.size} مقطع."
                        pollCurrentTimeOnce()
                        Toast.makeText(this, "تم تحميل الترجمة", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                val url = row.url.orEmpty()
                urlInput.setText(url)
                webView.loadUrl(url)
                if (restoreCachedSrt(url)) {
                    Toast.makeText(this, "تم تحميل الترجمة المحفوظة دون إعادة المعالجة", Toast.LENGTH_LONG).show()
                    showStatus("تم استرجاع ${repo.size} مقطع محفوظ لهذا الفيديو؛ لم يتم استدعاء مزود ترجمة.")
                } else Toast.makeText(this, "الرابط محفوظ لكن ملف SRT غير متاح", Toast.LENGTH_LONG).show()
            }
            dialog.dismiss()
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val row = rows[position]
            val title = row.item?.title ?: row.url.orEmpty()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("حذف من ملفاتي؟")
                .setMessage("سيُحذف ملف SRT المحلي فقط.\n\n$title")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حذف") { _, _ ->
                    val deleted = if (row.item != null) storage.deleteLibraryItem(row.item.id) else storage.delete(row.url.orEmpty())
                    Toast.makeText(this, if (deleted) "تم الحذف" else "تعذر الحذف", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    showSavedSubtitles()
                }
                .show()
            true
        }
        dialog.show()
    }

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
            openPasteLinkFlow()
            return
        }

        urlInput.setText(link)
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

    private fun sameVideoUrl(first: String, second: String): Boolean {
        fun normalize(value: String): String {
            return try {
                val uri = Uri.parse(value.trim())
                val host = uri.host?.lowercase(java.util.Locale.ROOT)?.removePrefix("www.")
                    ?: return value.trim().trimEnd('/')
                val path = uri.path.orEmpty().trimEnd('/')
                val id = uri.getQueryParameter("v")
                host + path + if (!id.isNullOrBlank()) "?v=$id" else ""
            } catch (_: Exception) { value.trim().trimEnd('/') }
        }
        return normalize(first).equals(normalize(second), ignoreCase = true)
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

        // إنشاء ProgressDialog مع مؤقت
        val progress = android.app.ProgressDialog(this).apply {
            setTitle("تفريغ الصوت (${settings.sttProviderLabel})")
            setMessage("جارٍ بدء الطلب…")
            setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            setButton(
                android.content.DialogInterface.BUTTON_NEGATIVE,
                "إلغاء"
            ) { _, _ -> /* يمكن إلغاؤه لاحقاً */ }
            show()
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

        Thread {
            try {
                val result = transcribeWithSelectedProvider(uri, language)
                val totalTime = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                cancelled = true
                runOnUiThread {
                    handler.removeCallbacks(timer)
                    progress.dismiss()
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
                        val currentUrl = urlInput.text.toString().trim()
                        if (currentUrl.startsWith("http://", ignoreCase = true) || currentUrl.startsWith("https://", ignoreCase = true)) {
                            storage.save(currentUrl, result.srt, parsedCues.size)
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
                    val msg = e.message ?: "خطأ غير معروف"
                    statusText.text = "❌ فشل التفريغ بعد ${totalTime}s: $msg"
                    android.app.AlertDialog.Builder(this)
                        .setTitle("فشل التفريغ")
                        .setMessage("$msg\n\n(بعد ${totalTime} ثانية)")
                        .setPositiveButton("حسناً", null)
                        .show()
                }
            }
        }.start()
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
                val currentUrl = urlInput.text.toString().trim()
                if (currentUrl.startsWith("http")) {
                    storage.save(currentUrl, srtText, cues.size)
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
        when (requestCode) {
            REQ_PICK_SRT -> processSmartSrt(uri)
            REQ_PICK_SRT_TO_TRANSLATE -> processTranslateSrt(uri)
            REQ_TRANSLATE_SRT_PICK -> translateSelectedSrt(uri)
            REQ_SAVE_TRANSLATED_SRT -> saveTranslatedSrt(uri)
            REQ_PICK_AUDIO -> transcribeAudioFile(uri)
            REQ_PICK_VIDEO_FOR_AUTO -> transcribeDownloadedVideo(uri)
            REQ_SAVE_WHISPER_SRT -> saveWhisperSrt(uri)
        }
    }

    private fun loadSrtFromUri(uri: Uri) {
        try {
            val input = contentResolver.openInputStream(uri)
            if (input == null) {
                Toast.makeText(this, "تعذّر فتح الملف", Toast.LENGTH_SHORT).show()
                return
            }
            val srtText = input.use { it.readBytes().toString(Charsets.UTF_8) }
            val cues = SrtParser.parse(srtText.byteInputStream())
            if (cues.isEmpty()) {
                Toast.makeText(this, "لا ترجمات في الملف", Toast.LENGTH_LONG).show()
                return
            }
            repo.setCues(cues)
            lastSrtText = srtText
            if (!overlayOn) overlayOn = true

            // ⚡ احفظ مرتبطاً بـ URL الحالي
            val currentUrl = webView.url ?: urlInput.text.toString().trim()
            if (currentUrl.isNotEmpty() && currentUrl.startsWith("http")) {
                storage.save(currentUrl, srtText, cues.size)
                lastSrtUrl = currentUrl
                Toast.makeText(this, "✅ تم تحميل ${cues.size} سطر + حفظ", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "✅ تم تحميل ${cues.size} سطر", Toast.LENGTH_SHORT).show()
            }
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

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.post(object : Runnable {
            override fun run() {
                if (!polling) return
                pollCurrentTimeOnce()
                handler.postDelayed(this, 500L)
            }
        })
    }

    private fun pollCurrentTimeOnce() {
        val js = """
            (function() {
              try {
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
                var t = Number(best.currentTime);
                var d = Number(best.duration);
                return JSON.stringify({
                  totalVideos: vids.length,
                  currentTime: Number.isFinite(t) ? t : 0,
                  duration: Number.isFinite(d) ? d : 0,
                  paused: !!best.paused,
                  readyState: best.readyState
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

            val cue = repo.findCueAt((t * 1000).toLong())

            if (overlayOn && !repo.isEmpty) {
                if (cue != null) subtitleOverlay.show(cue.text)
                else subtitleOverlay.hide()
            } else if (!overlayOn) {
                subtitleOverlay.hide()
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

    private fun formatSec(s: Double): String {
        if (s.isNaN() || s.isInfinite() || s < 0) return "--:--"
        val total = s.toInt()
        val m = total / 60
        val sec = total % 60
        return "%02d:%02d".format(m, sec)
    }

    /** Updates the visible status area from workflows that restore saved subtitles. */
    private fun showStatus(message: String) {
        statusText.text = message
        statusText.visibility = android.view.View.VISIBLE
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

    override fun onDestroy() {
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
