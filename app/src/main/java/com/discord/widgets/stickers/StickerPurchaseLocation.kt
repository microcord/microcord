package com.discord.widgets.stickers

/**
 * Where a sticker purchase or upsell flow was entered from.
 *
 * [analyticsValue] is the literal string the reference sends as the analytics `section`; it is a
 * wire value, so it is not localized and must not be reworded.
 */
enum class StickerPurchaseLocation(val analyticsValue: String) {
    EXPRESSION_PICKER("Expression Picker"),
    STICKER_POPOUT("Sticker Popout"),
    STICKER_UPSELL_POPOUT("Sticker Upsell Popout"),
    ;

    companion object {
        /**
         * The popout distinguishes "you could buy this pack" from "you already own it / it is not
         * for sale", because the upsell copy and the button differ.
         */
        fun getPopoutPurchaseLocation(canStickerPackBePurchased: Boolean): StickerPurchaseLocation =
            if (canStickerPackBePurchased) STICKER_UPSELL_POPOUT else STICKER_POPOUT

        /** Collapses both popout variants onto [STICKER_POPOUT]; everything else passes through. */
        fun getSimplifiedLocation(location: StickerPurchaseLocation?): StickerPurchaseLocation? =
            when (location) {
                STICKER_POPOUT, STICKER_UPSELL_POPOUT -> STICKER_POPOUT
                EXPRESSION_PICKER, null -> location
            }
    }
}
