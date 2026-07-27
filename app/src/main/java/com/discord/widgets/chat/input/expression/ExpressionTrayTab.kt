package com.discord.widgets.chat.input.expression

/**
 * The three tabs of the chat-input expression tray.
 *
 * Ordinal order is load-bearing twice over: it is the index of the matching segment in the tray's
 * segmented control, and it is what `StoreExpressionPickerNavigation` persists across launches.
 */
enum class ExpressionTrayTab {
    EMOJI,
    GIF,
    STICKER,
}
