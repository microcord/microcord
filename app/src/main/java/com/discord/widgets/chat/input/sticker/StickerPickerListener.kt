package com.discord.widgets.chat.input.sticker

import com.discord.api.sticker.Sticker

/**
 * Told after a sticker has actually been sent, so the host can close itself. It is *not* a veto
 * hook: [StickerPickerViewModel.onStickerSelected] already decided whether the send happened.
 */
fun interface StickerPickerListener {
    fun onStickerPicked(sticker: Sticker)
}
