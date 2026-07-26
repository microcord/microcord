package com.discord.utilities.gifting

import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import com.discord.R
import com.discord.models.domain.ModelGift

/**
 * Bespoke art for a seasonal or promotional gift.
 *
 * A gift carries a numeric `gift_style` from the server; when it matches one of these the card
 * shows the illustration instead of the generic Nitro splash. An unrecognised style — a newer
 * server sending art this build does not ship — resolves to null and falls back cleanly.
 *
 * [id] is the wire value and is what identifies the style; the enum's own ordering is not
 * meaningful.
 */
enum class GiftStyle(
    val id: Int,
    @DrawableRes val staticRes: Int,
    @RawRes val animRes: Int,
) {
    WumpusGlobe(1, R.drawable.gift_wumpus_snowglobe, R.raw.anim_gift_wumpus_globe),
    WumpusBox(2, R.drawable.gift_wumpus_box, R.raw.anim_gift_wumpus_box),
    WumpusLatte(3, R.drawable.gift_wumpus_latte, R.raw.anim_gift_wumpus_latte),
    ;

    companion object {
        private val values = values()

        fun from(gift: ModelGift): GiftStyle? =
            values.firstOrNull { gift.giftStyle != null && it.id == gift.giftStyle }
    }
}

/** The bespoke art for this gift, or null if it uses the generic Nitro presentation. */
fun ModelGift.getCustomStyle(): GiftStyle? = GiftStyle.from(this)

fun ModelGift.hasCustomStyle(): Boolean = getCustomStyle() != null
