package com.discord.widgets.chat.input.expression

/**
 * A request made *of* the expression tray by something outside it — the sticker popout's "view
 * pack" button, an autocomplete row, a deep link.
 *
 * These travel over [ExpressionPickerEventBus] rather than through a store because they are
 * one-shot commands, not state: replaying "open the sticker picker" to a new subscriber would
 * re-open it.
 */
sealed class ExpressionPickerEvent {

    /** Collapse the tray entirely. */
    object CloseExpressionPicker : ExpressionPickerEvent()

    /**
     * Show the sticker picker.
     *
     * @param stickerPackId scroll to this pack once it is open, when known.
     * @param searchText pre-fill the search field.
     * @param inline `true` selects the tray's sticker tab; `false` opens the sticker bottom sheet.
     */
    data class OpenStickerPicker(
        val stickerPackId: Long? = null,
        val searchText: String? = null,
        val inline: Boolean = false,
    ) : ExpressionPickerEvent()
}
