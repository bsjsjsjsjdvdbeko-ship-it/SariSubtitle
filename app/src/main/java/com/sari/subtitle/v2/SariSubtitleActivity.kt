package com.sari.subtitle.v2

import android.annotation.SuppressLint
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
import androidx.appcompat.app.AppCompatActivity

/**
 * Sari Subtitle — المرحلة 1
 *
 * الهدف الوحيد من هذه المرحلة:
 *   1. التطبيق يفتح بدون انهيار.
 *   2. WebView يُحمّل X.
 *   3. currentTime يُقرأ ويُطبع في statusText كل 500ms.
 *
 * لا توجد ترجمة بعد. لا توجد طبقة رسم. هذه أساسات فقط.
 */
class SariSubtitleActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var webView: WebView

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        statusText = findViewById(R.id.statusText)
        webView = findViewById(R.id.webView)

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
                startPolling()
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

        // تحميل افتراضي
        webView.loadUrl("https://x.com/")
    }

    /**
     * حقن ضغط "Show" التلقائي (نفس منطق XTimeTest).
     */
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
              window.__sariAutoShowInterval = setInterval(scan, 1000);
              scan();
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * بدء الاستماع الدوري لـ currentTime (كل 500ms في هذه المرحلة).
     * في المرحلة 2 سنخفضها إلى 100ms.
     */
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

    /**
     * استعلام currentTime من الفيديو الحالي في WebView.
     * يعيد: { currentTime, duration, paused, readyState, videoCount }
     */
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
                if (vids.length === 0) return JSON.stringify({videoCount:0});
                var v = vids[0];
                return JSON.stringify({
                  videoCount: vids.length,
                  currentTime: Number(v.currentTime) || 0,
                  duration: Number(v.duration) || 0,
                  paused: !!v.paused,
                  readyState: v.readyState
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
                statusText.text = formatTime(decoded)
            }
        }
    }

    private fun formatTime(json: String): String {
        return try {
            val o = org.json.JSONObject(json)
            if (o.has("error")) return "خطأ: ${o.getString("error")}"
            val count = o.optInt("videoCount", 0)
            if (count == 0) return "لا يوجد فيديو — فيديوهات: 0"
            val t = o.optDouble("currentTime", 0.0)
            val d = o.optDouble("duration", 0.0)
            val p = o.optBoolean("paused", false)
            val r = o.optInt("readyState", 0)
            val pos = formatSec(t)
            val dur = formatSec(d)
            val state = if (p) "⏸" else "▶"
            "video: $pos / $dur  $state  rs=$r  (videos=$count)"
        } catch (e: Exception) {
            "تعذر التحليل: ${e.message}\n$json"
        }
    }

    private fun formatSec(s: Double): String {
        if (s.isNaN() || s.isInfinite()) return "--:--"
        val total = s.toInt()
        val m = total / 60
        val sec = total % 60
        return "%02d:%02d".format(m, sec)
    }

    override fun onPause() {
        super.onPause()
        polling = false
        handler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        if (!polling) startPolling()
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
