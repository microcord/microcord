package com.discord.widgets.voice.fullscreen.stage

import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder

/**
 * Base holder for every stage row.
 *
 * Tapping any row routes back through [StageCallAdapter.onItemClick], which opens the user sheet
 * for the participant rows and ignores the rest — so the click handler is installed once here
 * rather than in each of the seven subclasses.
 */
sealed class StageCallViewHolder(
    @LayoutRes layout: Int,
    adapter: StageCallAdapter,
) : MGRecyclerViewHolder<StageCallAdapter, StageCallItem>(layout, adapter) {

    init {
        itemView.setOnClickListener {
            val position = bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) {
                adapter.onItemClick(adapter.getItem(position))
            }
        }
    }
}
