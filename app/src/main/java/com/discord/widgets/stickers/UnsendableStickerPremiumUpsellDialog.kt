package com.discord.widgets.stickers

import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.i18n.setI18nText
import com.google.android.material.button.MaterialButton

/**
 * Shown when the user taps a sticker in the picker that they cannot send: one line of copy, a
 * Subscribe button and a dismiss.
 */
class UnsendableStickerPremiumUpsellDialog : AppDialog(R.layout.sticker_premium_upsell_dialog) {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<TextView>(R.id.sticker_premium_upsell_description)
            .setI18nText(R.string.premium_upsell_global_stickers_description_mobile)
        view.findViewById<TextView>(R.id.sticker_premium_upsell_close_button)
            .setOnClickListener { dismiss() }
        // Same dead end as StickerPremiumUpsellDialog's Subscribe: WidgetSettingsPremium is absent.
        view.findViewById<MaterialButton>(R.id.sticker_premium_upsell_subscribe_button)
            .setOnClickListener { dismiss() }
    }

    companion object {
        fun show(fragmentManager: FragmentManager) {
            UnsendableStickerPremiumUpsellDialog()
                .show(fragmentManager, UnsendableStickerPremiumUpsellDialog::class.java.simpleName)
        }
    }
}
