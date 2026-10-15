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
import org.json.JSONObject

/**
 * Sari Subtitle — المرحلة 1.2
 * - اختيار الفيديو المرئي فقط (بدل الأول في DOM)
 * - Polling مستقل عن التنقل
 * - عرض القيم الخام للتشخيص
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
                // لا نبدأ Polling هنا — نتركه يعمل باستمرار
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

        // ⚠️ ابدأ Polling فوراً — لا ينتظر onPageFinished
        startPolling()

        webView.loadUrl("https://x.com/")
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
                handler.postDelayed(this, 250L)  // 250ms
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
                  return JSON.stringify({
                    videoCount: 0,
                    totalVideos: 0,
                    message: "no video"
                  });
                }

                // اختر الفيديو المرئي الأكبر على الشاشة
                var best = null;
                var maxArea = 0;
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
                    if (area > maxArea) {
                      maxArea = area;
                      best = v;
                    }
                  } catch(e){}
                }
                if (!best) best = vids[0];

                var t = Number(best.currentTime);
                var d = Number(best.duration);

                return JSON.stringify({
                  videoCount: 1,
                  totalVideos: vids.length,
                  currentTime: Number.isFinite(t) ? t : 0,
                  duration: Number.isFinite(d) ? d : 0,
                  durationRaw: String(best.duration),
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

            runOnUiThread {
                statusText.text = formatTime(decoded)
            }
        }
    }

    private fun formatTime(json: String): String {
        return try {
            val o = JSONObject(json)
            if (o.has("error")) return "خطأ: ${o.getString("error")}"

            val total = o.optInt("totalVideos", 0)
            if (total == 0) {
                return "لا يوجد فيديو — total=0  [tick ${System.currentTimeMillis() % 100000}]"
            }

            val t = o.optDouble("currentTime", 0.0)
            val d = o.optDouble("duration", 0.0)
            val dRaw = o.optString("durationRaw", "?")
            val p = o.optBoolean("paused", false)
            val r = o.optInt("readyState", 0)
            val w = o.optInt("videoWidth", 0)
            val h = o.optInt("videoHeight", 0)

            val pos = formatSec(t)
            val dur = formatSec(d)
            val state = if (p) "⏸" else "▶"
            val tick = System.currentTimeMillis() % 100000

            "video: $pos / $dur  $state  rs=$r  (n=$total, ${w}x${h})\n" +
            "خام → t=$t  d=$d  durRaw=$dRaw\n" +
            "tick=$tick"
        } catch (e: Exception) {
            "تعذر التحليل: ${e.message}\n$json"
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
