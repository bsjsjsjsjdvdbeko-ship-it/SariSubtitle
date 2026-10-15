package com.sari.subtitle.v2

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * Sari Subtitle — المرحلة 2.2
 * - تحميل SRT من ملف محلي
 * - عرض الترجمات الفعلية حسب currentTime
 */
class SariSubtitleActivity : AppCompatActivity() {

    companion object {
        private const val REQ_PICK_SRT = 1001
    }

    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var webView: WebView
    private lateinit var subtitleOverlay: SubtitleOverlay
    private lateinit var loadSrtButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val repo = SubtitleRepository()
    private var polling = false
    private var overlayOn = false
    private var currentSrtName: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        statusText = findViewById(R.id.statusText)
        webView = findViewById(R.id.webView)
        subtitleOverlay = findViewById(R.id.subtitleOverlay)
        loadSrtButton = findViewById(R.id.loadSrtButton)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"
        }
        WebView.setWebContentsDebuggingEnabled(true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                statusText.text = "تم التحميل: $url"
                injectAutoShow()
            }
        }
        webView.webChromeClient = WebChromeClient()

        findViewById<Button>(R.id.loadButton).setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            statusText.text = getString(R.string.status_loading)
            webView.loadUrl(url)
        }

        findViewById<Button>(R.id.reloadXButton).setOnClickListener {
            urlInput.setText("https://x.com/")
            webView.loadUrl("https://x.com/")
        }

        findViewById<Button>(R.id.checkTimeButton).setOnClickListener {
            pollCurrentTimeOnce()
        }

        findViewById<Button>(R.id.toggleOverlayButton).setOnClickListener {
            toggleOverlay()
        }

        loadSrtButton.setOnClickListener {
            openSrtPicker()
        }

        startPolling()
        webView.loadUrl("https://x.com/")
    }

    /**
     * فتح مدير الملفات لاختيار SRT.
     */
    private fun openSrtPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/x-subrip",
                    "text/plain",
                    "text/*",
                    "application/octet-stream"
                )
            )
        }
        try {
            startActivityForResult(intent, REQ_PICK_SRT)
        } catch (e: Exception) {
            Toast.makeText(this, "لا يوجد مدير ملفات", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_SRT && resultCode == Activity.RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) loadSrtFromUri(uri)
        }
    }

    /**
     * تحميل ملف SRT من Uri.
     */
    private fun loadSrtFromUri(uri: Uri) {
        try {
            val input = contentResolver.openInputStream(uri)
            if (input == null) {
                Toast.makeText(this, "تعذّر فتح الملف", Toast.LENGTH_SHORT).show()
                return
            }
            val cues = input.use { SrtParser.parse(it) }
            if (cues.isEmpty()) {
                Toast.makeText(this, "لم يُعثر على ترجمات في الملف", Toast.LENGTH_LONG).show()
                return
            }
            repo.setCues(cues)
            currentSrtName = getFileName(uri) ?: "SRT محمّل"
            Toast.makeText(this, "✅ تم تحميل ${cues.size} سطر ترجمة", Toast.LENGTH_SHORT).show()
            // تفعيل Overlay تلقائياً
            if (!overlayOn) {
                overlayOn = true
            }
        } catch (e: Exception) {
            Toast.makeText(this, "خطأ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getFileName(uri: Uri): String? {
        return try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return it.getString(idx)
                }
            }
            uri.lastPathSegment
        } catch (_: Exception) { null }
    }

    private fun toggleOverlay() {
        if (repo.isEmpty) {
            Toast.makeText(this, "حمّل ملف SRT أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        overlayOn = !overlayOn
        if (overlayOn) {
            Toast.makeText(this, "✅ الترجمة مفعّلة", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "❌ الترجمة معطّلة", Toast.LENGTH_SHORT).show()
            subtitleOverlay.hide()
        }
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
                handler.postDelayed(this, 250L)
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
                if (vids.length === 0) {
                  return JSON.stringify({totalVideos: 0});
                }
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
                  readyState: best.readyState,
                  videoWidth: best.videoWidth,
                  videoHeight: best.videoHeight
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

            // ⚡ ابحث عن cue مناسب
            val tMs = (t * 1000).toLong()
            val cue = repo.findCueAt(tMs)

            // ⚡ اعرض في Overlay
            if (overlayOn && !repo.isEmpty) {
                if (cue != null) {
                    subtitleOverlay.show(cue.text)
                } else {
                    subtitleOverlay.hide()
                }
            } else if (!overlayOn) {
                subtitleOverlay.hide()
            }

            val pos = formatSec(t)
            val dur = formatSec(d)
            val state = if (p) "⏸" else "▶"
            val srtInfo = if (repo.isEmpty) {
                "SRT: —"
            } else {
                "SRT: ${repo.size} سطر" + (if (cue != null) " → #${cue.index}" else " → —")
            }

            statusText.text = "video: $pos / $dur  $state  rs=$r\n" +
                "$srtInfo  ov=${if (overlayOn) "ON" else "OFF"}\n" +
                "t=${"%.2f".format(t)}s  tick=${System.currentTimeMillis() % 100000}"
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
