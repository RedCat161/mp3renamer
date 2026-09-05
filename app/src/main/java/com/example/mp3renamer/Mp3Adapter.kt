package com.example.mp3renamer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

class Mp3Adapter(
    private val items: MutableList<Mp3Item>
) : RecyclerView.Adapter<Mp3Adapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(R.id.tvFileName)
        val positionBadge: TextView = view.findViewById(R.id.tvPosition)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_mp3, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.textView.text = item.documentFile.name ?: "—"
        // Бейдж показывает ТЕКУЩУЮ позицию в списке (визуально, в файл не пишется,
        // пока не нажата кнопка "Пронумеровать").
        holder.positionBadge.text = String.format("%02d", position + 1)
    }

    override fun getItemCount(): Int = items.size

    /** Вызывается из ItemTouchHelper при перетаскивании строки. */
    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition < 0 || toPosition < 0 ||
            fromPosition >= items.size || toPosition >= items.size
        ) return false

        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(items, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(items, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        return true
    }
}
