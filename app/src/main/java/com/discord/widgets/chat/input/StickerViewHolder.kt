package com.discord.widgets.chat.input

import com.discord.R
import com.discord.databinding.StickerAutocompleteItemBinding
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder

class StickerViewHolder(
    adapter: WidgetChatInputAutocompleteStickerAdapter,
) : MGRecyclerViewHolder<WidgetChatInputAutocompleteStickerAdapter, AutocompleteStickerItem>(
    R.layout.sticker_autocomplete_item,
    adapter,
) {
    private val binding = StickerAutocompleteItemBinding.bind(itemView)

    override fun onConfigure(position: Int, data: AutocompleteStickerItem) {
        super.onConfigure(position, data)
        binding.stickerAutocompleteSticker.setSticker(data.sticker)
        binding.stickerAutocompleteSticker.clearBackgrounds()
        binding.root.setOnClickListener { adapter.onClickSticker(data.sticker) }
    }
}
