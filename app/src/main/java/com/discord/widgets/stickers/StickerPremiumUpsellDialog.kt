package com.discord.widgets.stickers

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.i18n.i18nPlural
import com.discord.i18n.setI18nText
import com.google.android.material.button.MaterialButton

/**
 * The full "Get Nitro to unlock stickers" modal, reached from the sticker popout's buy button and
 * from the guild popout when the viewer has no Nitro.
 */
class StickerPremiumUpsellDialog : AppDialog(R.layout.premium_sticker_upsell_dialog) {

    override fun onStart() {
        super.onStart()
        // The layout is a card that wants the full dialog width; without this the window shrinks to
        // its measured content and the perk list wraps.
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        dialog?.setCanceledOnTouchOutside(true)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        // The reference asks Google Play for the live Tier-2 monthly price and drops back to the
        // price-free copy when billing has not answered. There is no billing layer in this project,
        // so the price-free variant is the only one that can be shown honestly.
        view.findViewById<TextView>(R.id.premium_upsell_description)
            .setI18nText(R.string.premium_upsell_feature_pretext_without_price)

        view.findViewById<TextView>(R.id.premium_upsell_perk_boosts).setI18nText(
            R.string.premium_upsell_feature_free_guild_subscription,
            "numFreeGuildSubscriptions" to requireContext().i18nPlural(
                R.plurals.premium_upsell_feature_free_guild_subscription_numFreeGuildSubscriptions,
                FREE_GUILD_SUBSCRIPTIONS,
                "#" to FREE_GUILD_SUBSCRIPTIONS,
            ),
        )

        view.findViewById<ImageView>(R.id.premium_upsell_close).setOnClickListener { dismiss() }
        view.findViewById<TextView>(R.id.close_button).setOnClickListener { dismiss() }
        // The reference's Subscribe button opens WidgetSettingsPremium at tier 2. That screen has
        // not been reconstructed, so this is the one dead end in the sticker flow — reported to the
        // settings wave. It dismisses rather than doing nothing at all.
        view.findViewById<MaterialButton>(R.id.subscribe_button).setOnClickListener { dismiss() }
    }

    companion object {
        /** Nitro includes two Server Boosts. */
        private const val FREE_GUILD_SUBSCRIPTIONS = 2

        fun show(fragmentManager: FragmentManager) {
            StickerPremiumUpsellDialog()
                .show(fragmentManager, StickerPremiumUpsellDialog::class.java.simpleName)
        }
    }
}
