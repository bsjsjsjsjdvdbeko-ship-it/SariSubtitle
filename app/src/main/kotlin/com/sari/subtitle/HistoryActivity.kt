package com.sari.subtitle

import android.os.Bundle
import android.net.Uri
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {
    private lateinit var adapter: TranslationAdapter
    private lateinit var emptyState: TextView
    private lateinit var recycler: RecyclerView
    private val dao by lazy { AppDatabase.get(this).translationDao() }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val items = dao.latest(50)
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { out ->
                        out.write("\uFEFF")
                        out.appendLine("created_at,source_language,target_language,source_text,translated_text")
                        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        items.asReversed().forEach { item ->
                            val row = listOf(
                                dateFormat.format(Date(item.createdAt)), item.sourceLanguage, item.targetLanguage,
                                item.sourceText, item.translatedText
                            ).joinToString(",") { csv(it) }
                            out.appendLine(row)
                        }
                    } ?: error("تعذر فتح ملف التصدير للكتابة")
                }
                Toast.makeText(this@HistoryActivity, getString(R.string.history_export_success, items.size), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@HistoryActivity, getString(R.string.history_export_failed, e.message ?: "خطأ غير معروف"), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        findViewById<MaterialToolbar>(R.id.historyToolbar).setNavigationOnClickListener { finish() }
        recycler = findViewById(R.id.historyRecycler)
        emptyState = findViewById(R.id.emptyHistory)
        adapter = TranslationAdapter { item -> lifecycleScope.launch { dao.deleteById(item.id); refresh() } }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) { adapter.itemAt(vh.bindingAdapterPosition)?.let { item -> lifecycleScope.launch { dao.deleteById(item.id); refresh() } } }
        }).attachToRecyclerView(recycler)
        findViewById<com.google.android.material.button.MaterialButton>(R.id.exportHistoryButton).setOnClickListener {
            exportLauncher.launch("sari-subtitle-history.csv")
        }
        findViewById<FloatingActionButton>(R.id.clearHistoryFab).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.clear_history)
                .setMessage(R.string.clear_history_confirm).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ -> lifecycleScope.launch { dao.deleteAll(); refresh(); Toast.makeText(this@HistoryActivity, R.string.history_cleared, Toast.LENGTH_SHORT).show() } }.show()
        }
        lifecycleScope.launch { refresh() }
    }

    private suspend fun refresh() {
        try {
            val items = dao.latest(50)
            adapter.submit(items)
            (emptyState.parent as? android.view.View)?.visibility = if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            recycler.visibility = if (items.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.history_load_failed, e.message ?: "خطأ غير معروف"), Toast.LENGTH_LONG).show()
            adapter.submit(emptyList())
            recycler.visibility = android.view.View.GONE
            (emptyState.parent as? android.view.View)?.visibility = android.view.View.VISIBLE
        }
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
