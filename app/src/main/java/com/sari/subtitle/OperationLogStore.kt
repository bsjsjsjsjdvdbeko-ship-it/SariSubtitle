package com.sari.subtitle

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context.CLIPBOARD_SERVICE
import android.graphics.Color
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small persistent operation history. Never records API keys or full transcript contents. */
object OperationLogStore {
    private const val PREFS = "sari_operation_log_v1"
    private const val KEY = "events"
    private const val LIMIT = 500
    @Volatile var onChanged: (() -> Unit)? = null
    data class Event(val time: Long, val operation: String, val provider: String, val model: String,
                     val durationMs: Long, val status: String, val result: String, val detail: String)

    @Synchronized fun add(context: Context, operation: String, provider: String, model: String,
                          durationMs: Long, status: String, result: String = "", detail: String = "") {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = try { JSONArray(prefs.getString(KEY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val arr = JSONArray()
        val start = (old.length() - (LIMIT - 1)).coerceAtLeast(0)
        for (i in start until old.length()) arr.put(old.optJSONObject(i) ?: JSONObject())
        arr.put(JSONObject().put("time", System.currentTimeMillis()).put("operation", clean(operation))
            .put("provider", clean(provider)).put("model", clean(model)).put("durationMs", durationMs.coerceAtLeast(0))
            .put("status", clean(status)).put("result", clean(result)).put("detail", clean(detail)))
        prefs.edit().putString(KEY, arr.toString()).apply()
        try { onChanged?.invoke() } catch (_: Exception) { }
    }

    @Synchronized fun events(context: Context): List<Event> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { o ->
            Event(o.optLong("time"), o.optString("operation"), o.optString("provider"), o.optString("model"),
                o.optLong("durationMs"), o.optString("status"), o.optString("result"), o.optString("detail"))
        } }.reversed()
    }

    @Synchronized fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        try { onChanged?.invoke() } catch (_: Exception) { }
    }

    fun format(event: Event): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(event.time))
        return "$stamp | ${event.operation} | ${event.status} | provider=${event.provider} | model=${event.model} | duration=${event.durationMs}ms" +
            (if (event.result.isNotBlank()) " | result=${event.result}" else "") +
            (if (event.detail.isNotBlank()) " | detail=${event.detail}" else "")
    }

    fun showHistory(context: Context) {
        val entries = events(context)
        val body = TextView(context).apply {
            textSize = 12f; setTextColor(Color.DKGRAY); setPadding(24, 16, 24, 16)
            text = if (entries.isEmpty()) "لا توجد عمليات مسجلة بعد." else entries.joinToString("\n\n") { format(it) }
        }
        val scroll = ScrollView(context).apply { addView(body) }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (context.resources.displayMetrics.heightPixels * 0.55f).toInt()))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(context).apply { text = "نسخ السجل"; setOnClickListener {
                    val clipboard = context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Sari Subtitle operation log", entries.joinToString("\n") { format(it) }))
                    android.widget.Toast.makeText(context, "تم نسخ السجل", android.widget.Toast.LENGTH_SHORT).show()
                } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(context).apply { text = "مسح السجل"; setOnClickListener {
                    AlertDialog.Builder(context).setTitle("مسح سجل العمليات؟").setMessage("سيتم حذف سجل العمليات المحفوظ على هذا الجهاز.")
                        .setNegativeButton("إلغاء", null).setPositiveButton("مسح") { _, _ ->
                            clear(context); android.widget.Toast.makeText(context, "تم مسح السجل", android.widget.Toast.LENGTH_SHORT).show()
                            showHistory(context)
                        }.show()
                } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        AlertDialog.Builder(context).setTitle("سجل العمليات الكامل (${entries.size})").setView(root)
            .setPositiveButton("إغلاق", null).show().window?.setLayout(
                (context.resources.displayMetrics.widthPixels * 0.94f).toInt(),
                (context.resources.displayMetrics.heightPixels * 0.78f).toInt())
    }

    private fun clean(value: String): String = sanitizeLogValue(value)

    /** Pure sanitizer shared with unit tests; sensitive values must never enter persisted history. */
    internal fun sanitizeLogValue(value: String): String = value
        // Authorization headers may contain a scheme and token separated by spaces;
        // redact the whole header value before the generic key/token patterns run.
        .replace(Regex("(?i)(authorization)\\s*[:=]\\s*[^\\r\\n]+"), "${'$'}1=[REDACTED]")
        .replace(Regex("(?i)(api[_ -]?key|bearer)\\s*[:=]?\\s*\\S+"), "${'$'}1=[REDACTED]")
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .take(500)
}
