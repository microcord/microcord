package com.discord.widgets.chat.input.sticker

/**
 * How [WidgetStickerPicker] is being hosted.
 *
 * `INLINE` is the picker embedded in the chat input's expression tray: no search bar, no toolbar.
 * `BOTTOM_SHEET` is [WidgetStickerPickerSheet], which adds the search bar and opens the keyboard.
 *
 * Ordinal order matters — the value is put into a `Bundle` as a `Serializable`.
 */
enum class StickerPickerMode {
    INLINE,
    BOTTOM_SHEET,
}
