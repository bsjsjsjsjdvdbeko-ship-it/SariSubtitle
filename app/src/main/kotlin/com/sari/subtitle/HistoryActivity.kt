package com.sari.subtitle

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch

class HistoryActivity : AppCompatActivity() {
    private lateinit var adapter: TranslationAdapter
    private lateinit var emptyState: TextView
    private lateinit var recycler: RecyclerView
    private val dao by lazy { AppDatabase.get(this).translationDao() }

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
        findViewById<FloatingActionButton>(R.id.clearHistoryFab).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.clear_history)
                .setMessage(R.string.clear_history_confirm).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ -> lifecycleScope.launch { dao.deleteAll(); refresh(); Toast.makeText(this@HistoryActivity, R.string.history_cleared, Toast.LENGTH_SHORT).show() } }.show()
        }
        lifecycleScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val items = dao.latest(50)
        adapter.submit(items)
        (emptyState.parent as? android.view.View)?.visibility = if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        recycler.visibility = if (items.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }
}
