package com.discord.widgets.chat.input.sticker

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.sticker.Sticker
import com.discord.app.AppDialog
import com.discord.views.sticker.StickerView

/**
 * A borderless, tap-anywhere-to-close preview of a single sticker at full size. Used by the pack
 * store sheet, where tapping a tile previews rather than sends.
 */
class StickerFullSizeDialog : AppDialog(R.layout.sticker_full_size_dialog) {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        dialog?.setCanceledOnTouchOutside(true)
        // The sticker is the entire dialog; any window background would show as a card behind it.
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        val sticker = arguments?.getSerializable(EXTRA_STICKER) as? Sticker
        if (sticker == null) {
            dismiss()
            return
        }
        requireView().findViewById<StickerView>(R.id.sticker_full_size_sticker)
            .setSticker(sticker, ALWAYS_ANIMATE)
    }

    companion object {
        private const val EXTRA_STICKER = "com.discord.intent.extra.EXTRA_STICKER"

        /** [com.discord.utilities.stickers.StickerUtils.ALWAYS_ANIMATE]. */
        private const val ALWAYS_ANIMATE = 0

        fun show(fragmentManager: FragmentManager, sticker: Sticker) {
            StickerFullSizeDialog().apply {
                arguments = Bundle().apply { putSerializable(EXTRA_STICKER, sticker) }
            }.show(fragmentManager, StickerFullSizeDialog::class.java.simpleName)
        }
    }
}
