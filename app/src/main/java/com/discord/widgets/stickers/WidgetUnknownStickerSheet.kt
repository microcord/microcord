package com.discord.widgets.stickers

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.sticker.StickerPartial
import com.discord.app.AppBottomSheet
import com.discord.views.sticker.StickerView

/**
 * The popout for a sticker whose full record could not be resolved — a deleted sticker, or one from
 * a guild that is now unavailable. It shows the art and the name from the message's own
 * [StickerPartial] and offers no action, because there is nothing to link to.
 */
class WidgetUnknownStickerSheet : AppBottomSheet() {

    override fun getContentViewResId(): Int = R.layout.widget_unknown_sticker_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val sticker = argumentsOrDefault.getSerializable(EXTRA_STICKER) as? StickerPartial
        if (sticker == null) {
            dismiss()
            return
        }
        view.findViewById<StickerView>(R.id.unknown_sticker_sheet_sticker)
            .setSticker(sticker, ALWAYS_ANIMATE)
        view.findViewById<TextView>(R.id.unknown_sticker_sheet_sticker_name).text = sticker.name
    }

    companion object {
        private const val EXTRA_STICKER = "com.discord.intent.extra.EXTRA_STICKER"

        /** [com.discord.utilities.stickers.StickerUtils.ALWAYS_ANIMATE]. */
        private const val ALWAYS_ANIMATE = 0

        fun show(fragmentManager: FragmentManager, sticker: StickerPartial) {
            WidgetUnknownStickerSheet().apply {
                arguments = Bundle().apply { putSerializable(EXTRA_STICKER, sticker) }
            }.show(fragmentManager, WidgetUnknownStickerSheet::class.java.name)
        }
    }
}
