package com.example.mp3renamer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

class Mp3Adapter(
    private val items: MutableList<Mp3Item>,
    private val onPlayClick: (position: Int) -> Unit
) : RecyclerView.Adapter<Mp3Adapter.ViewHolder>() {

    /** Позиция сейчас проигрываемого файла, -1 если ничего не играет. */
    var playingPosition: Int = -1
        private set

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(R.id.tvFileName)
        val positionBadge: TextView = view.findViewById(R.id.tvPosition)
        val btnPlay: ImageView = view.findViewById(R.id.btnPlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_mp3, parent, false)
        val holder = ViewHolder(view)
        holder.btnPlay.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) onPlayClick(pos)
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.textView.text = item.documentFile.name ?: "—"
        // Бейдж показывает ТЕКУЩУЮ позицию в списке (визуально, в файл не пишется,
        // пока не нажата кнопка "Пронумеровать").
        holder.positionBadge.text = String.format("%02d", position + 1)
        holder.btnPlay.setImageResource(
            if (position == playingPosition) R.drawable.ic_pause else R.drawable.ic_play
        )
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

    /** Обновляет, какая строка сейчас проигрывается (или -1, если ничего). */
    fun setPlayingPosition(position: Int) {
        val old = playingPosition
        playingPosition = position
        if (old >= 0 && old < itemCount) notifyItemChanged(old)
        if (position >= 0 && position < itemCount) notifyItemChanged(position)
    }
}
