package com.example.mp3renamer

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * isLongPressDragEnabled = true включает перетаскивание строки
 * долгим нажатием (без отдельной "ручки"), как и просил пользователь.
 *
 * onDragFinished вызывается один раз, когда пользователь отпускает строку —
 * в этот момент обновляются бейджи с номерами позиций и сохраняется порядок.
 */
class DragTouchHelperCallback(
    private val adapter: Mp3Adapter,
    private val onDragFinished: () -> Unit
) : ItemTouchHelper.Callback() {

    override fun isLongPressDragEnabled(): Boolean = true

    override fun isItemViewSwipeEnabled(): Boolean = false

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
        return makeMovementFlags(dragFlags, 0)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        return adapter.onItemMove(viewHolder.adapterPosition, target.adapterPosition)
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // свайпы не используются
    }

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            viewHolder?.itemView?.alpha = 0.75f
            viewHolder?.itemView?.animate()?.scaleX(1.03f)?.scaleY(1.03f)?.setDuration(120)?.start()
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        viewHolder.itemView.alpha = 1.0f
        viewHolder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        adapter.notifyDataSetChanged() // обновить номера бейджей после отпускания
        onDragFinished()
    }
}
