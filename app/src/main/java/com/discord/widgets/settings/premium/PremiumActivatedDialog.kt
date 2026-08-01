package com.discord.widgets.settings.premium

import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.databinding.PremiumActivatedDialogBinding
import com.discord.utilities.drawable.getThemedDrawableRes

class PremiumActivatedDialog : AppDialog(R.layout.premium_activated_dialog) {
    private var binding: PremiumActivatedDialogBinding? = null
    private var onConfirm: (() -> Unit)? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val binding = PremiumActivatedDialogBinding.bind(view).also { this.binding = it }
        val tier1 = argumentsOrDefault.getBoolean(ARG_IS_TIER_1)
        binding.premiumActivatedHeaderBackground.setBackgroundResource(
            if (tier1) R.drawable.bg_premium_classic_subscription_header
            else R.drawable.bg_premium_subscription_header,
        )
        binding.premiumActivatedLogo.setImageResource(
            if (tier1) R.drawable.img_logo_nitro_classic else R.drawable.img_logo_discord_nitro,
        )
        binding.premiumActivatedWumpus.apply {
            setImageResource(if (tier1) R.drawable.img_wumpus_riding_wheel else R.drawable.img_wumpus_jetpack)
            scaleX = if (tier1) 1f else -1f
        }
        binding.premiumActivatedTitle.setImageDrawable(
            ContextCompat.getDrawable(
                requireContext(),
                requireContext().getThemedDrawableRes(
                    if (tier1) R.attr.img_premium_activated_tier_1 else R.attr.img_premium_activated_tier_2,
                ),
            ),
        )
        binding.premiumActivatedDescription.setText(
            if (tier1) R.string.billing_switch_plan_confirm_tier_1
            else R.string.billing_switch_plan_confirm_tier_2,
        )
        binding.premiumActivatedConfirm.setOnClickListener {
            onConfirm?.invoke()
            dismiss()
        }
        requireDialog().setCanceledOnTouchOutside(true)
    }

    override fun onResume() {
        super.onResume()
        if (onConfirm == null) dismiss()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_IS_TIER_1 = "ARG_IS_TIER_1"

        fun show(fragmentManager: FragmentManager, tier1: Boolean, onConfirm: () -> Unit) {
            PremiumActivatedDialog().apply {
                this.onConfirm = onConfirm
                arguments = Bundle().apply { putBoolean(ARG_IS_TIER_1, tier1) }
            }.show(fragmentManager, PremiumActivatedDialog::class.java.simpleName)
        }
    }
}
