package com.sari.subtitle

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.TimeUnit

class TranslationAdapter(private val onDelete: (TranslationEntity) -> Unit) : RecyclerView.Adapter<TranslationAdapter.Holder>() {
    private val items = mutableListOf<TranslationEntity>()
    fun submit(values: List<TranslationEntity>) { items.clear(); items.addAll(values); notifyDataSetChanged() }
    fun itemAt(position: Int): TranslationEntity? = items.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_translation, parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val source = view.findViewById<TextView>(R.id.itemOriginal)
        private val translated = view.findViewById<TextView>(R.id.itemTranslated)
        private val date = view.findViewById<TextView>(R.id.itemDate)
        private val delete = view.findViewById<View>(R.id.itemDelete)
        fun bind(item: TranslationEntity) {
            source.text = item.sourceText
            translated.text = item.translatedText
            val elapsed = (System.currentTimeMillis() - item.createdAt).coerceAtLeast(0)
            val mins = TimeUnit.MILLISECONDS.toMinutes(elapsed)
            date.text = when { mins < 1 -> itemView.context.getString(R.string.just_now); mins < 60 -> itemView.context.getString(R.string.minutes_ago, mins); mins < 1440 -> itemView.context.getString(R.string.hours_ago, mins / 60); else -> itemView.context.getString(R.string.days_ago, mins / 1440) }
            delete.setOnClickListener { onDelete(item) }
        }
    }
}
