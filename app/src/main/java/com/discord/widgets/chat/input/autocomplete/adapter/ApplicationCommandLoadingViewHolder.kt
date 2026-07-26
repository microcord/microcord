package com.discord.widgets.chat.input.autocomplete.adapter

import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Skeleton row shown while an application's commands are still loading.
 *
 * It has nothing to bind — `widget_chat_input_slash_loading_item` is three shimmer placeholders —
 * so the holder exists only to give the adapter a distinct view type.
 */
class ApplicationCommandLoadingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
