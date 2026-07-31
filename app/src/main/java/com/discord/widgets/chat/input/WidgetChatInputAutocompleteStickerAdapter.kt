package com.discord.widgets.chat.input

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.api.sticker.Sticker
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder

class WidgetChatInputAutocompleteStickerAdapter(
    recycler: RecyclerView,
    val onClickSticker: (Sticker) -> Unit,
) : MGRecyclerAdapterSimple<AutocompleteStickerItem>(recycler, false) {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, AutocompleteStickerItem> =
        if (viewType == 0) StickerViewHolder(this) else throw invalidViewTypeException(viewType)
}
