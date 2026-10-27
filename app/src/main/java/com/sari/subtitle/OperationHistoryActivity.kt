package com.sari.subtitle

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Dedicated, persistent operation-history screen; never displays API secrets. */
class OperationHistoryActivity : AppCompatActivity() {
    private lateinit var entriesView: TextView
    private lateinit var countView: TextView
    private val english: Boolean get() = resources.configuration.locales[0].language.equals("en", true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = Color.rgb(8, 11, 22)
        val panel = Color.rgb(17, 24, 42)
        val text = Color.rgb(244, 245, 255)
        val muted = Color.rgb(167, 176, 200)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 16)
            setBackgroundColor(bg)
            layoutDirection = if (english) android.view.View.LAYOUT_DIRECTION_LTR else android.view.View.LAYOUT_DIRECTION_RTL
        }
        root.addView(TextView(this).apply {
            this.text = if (english) "Operation history" else "سجل العمليات"
            textSize = 24f; setTextColor(text); setTypeface(null, android.graphics.Typeface.BOLD)
        })
        countView = TextView(this).apply { textSize = 13f; setTextColor(muted); setPadding(0, 6, 0, 14) }
        root.addView(countView)
        entriesView = TextView(this).apply {
            textSize = 13f; setTextColor(text); setPadding(14, 14, 14, 14)
            setTextIsSelectable(true); setTextDirection(android.view.View.TEXT_DIRECTION_FIRST_STRONG)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(panel)
            addView(entriesView, ViewGroup.LayoutParams(-1, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, 12, 0, 0)
        }
        actions.addView(Button(this).apply {
            setText(if (english) "Copy" else "نسخ السجل")
            setOnClickListener {
                val all = OperationLogStore.events(this@OperationHistoryActivity)
                    .joinToString("\n\n") { OperationLogStore.format(it) }
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Sari Subtitle history", all))
                android.widget.Toast.makeText(this@OperationHistoryActivity,
                    if (english) "History copied" else "تم نسخ السجل", android.widget.Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply {
            setText(if (english) "Clear" else "مسح السجل")
            setOnClickListener {
                AlertDialog.Builder(this@OperationHistoryActivity)
                    .setTitle(if (english) "Clear history?" else "مسح السجل؟")
                    .setMessage(if (english) "This removes saved operation events from this device." else "سيتم حذف سجل العمليات المحفوظ على هذا الجهاز.")
                    .setNegativeButton(if (english) "Cancel" else "إلغاء", null)
                    .setPositiveButton(if (english) "Clear" else "مسح") { _, _ ->
                        OperationLogStore.clear(this@OperationHistoryActivity); refreshHistory()
                    }.show()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply {
            setText(if (english) "Back" else "رجوع")
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
        setContentView(root)
        refreshHistory()
    }

    override fun onResume() { super.onResume(); if (::entriesView.isInitialized) refreshHistory() }

    private fun refreshHistory() {
        val events = OperationLogStore.events(this)
        countView.text = if (english) "${events.size} saved events (maximum 500)" else "${events.size} عملية محفوظة (الحد الأقصى 500)"
        entriesView.text = if (events.isEmpty()) {
            if (english) "No operations recorded yet." else "لا توجد عمليات مسجلة بعد."
        } else events.joinToString("\n\n────────────────────\n\n") { OperationLogStore.format(it) }
    }
}
