package com.discord.widgets.settings.premium

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.databinding.GuildBoostUpgradeDialogBinding
import com.discord.databinding.PremiumUpgradeYearlyDialogBinding
import com.discord.i18n.i18nPlural
import java.text.NumberFormat

internal class GuildBoostUpgradeDialog : AppDialog(R.layout.guild_boost_upgrade_dialog) {
    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val binding = GuildBoostUpgradeDialogBinding.bind(view)
        binding.premiumUpsellDescription.text = getString(R.string.premium_upsell_feature_pretext)
            .replace("{monthlyPrice}", argumentsOrDefault.getString(ARG_PRICE).orEmpty())
        binding.premiumUpsellServerPerk.text =
            getString(R.string.premium_upsell_feature_free_guild_subscription).replace(
                "{numFreeGuildSubscriptions}",
                requireContext().i18nPlural(
                    R.plurals.premium_upsell_feature_free_guild_subscription_numFreeGuildSubscriptions,
                    2,
                    "#" to 2,
                ),
            )
        binding.continueButton.visibility =
            if (argumentsOrDefault.getBoolean(ARG_HAS_BOOST_PLAN)) View.GONE else View.VISIBLE
        binding.continueButton.setOnClickListener { finish(ACTION_CONTINUE) }
        binding.upgradeButton.setOnClickListener { finish(ACTION_UPGRADE) }
        binding.premiumUpsellClose.setOnClickListener { dismiss() }
    }

    private fun finish(action: String) {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putString(RESULT_ACTION, action)
                putString(RESULT_SKU_NAME, argumentsOrDefault.getString(ARG_SKU_NAME))
            },
        )
        dismiss()
    }

    companion object {
        const val REQUEST_KEY = "GUILD_BOOST_UPGRADE_RESULT"
        const val RESULT_ACTION = "RESULT_ACTION"
        const val RESULT_SKU_NAME = "RESULT_SKU_NAME"
        const val ACTION_CONTINUE = "continue"
        const val ACTION_UPGRADE = "upgrade"
        private const val ARG_SKU_NAME = "ARG_SKU_NAME"
        private const val ARG_PRICE = "ARG_PRICE"
        private const val ARG_HAS_BOOST_PLAN = "ARG_HAS_BOOST_PLAN"

        fun show(fragmentManager: FragmentManager, skuName: String, price: String, hasBoostPlan: Boolean) {
            GuildBoostUpgradeDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_SKU_NAME, skuName)
                    putString(ARG_PRICE, price)
                    putBoolean(ARG_HAS_BOOST_PLAN, hasBoostPlan)
                }
            }.show(fragmentManager, GuildBoostUpgradeDialog::class.java.name)
        }
    }
}

internal class PremiumUpgradeYearlyDialog : AppDialog(R.layout.premium_upgrade_yearly_dialog) {
    private var actionTaken = false

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val binding = PremiumUpgradeYearlyDialogBinding.bind(view)
        val discount = NumberFormat.getPercentInstance().format(0.16f)
        val tierOne = argumentsOrDefault.getBoolean(ARG_TIER_ONE)
        val tierName = getString(if (tierOne) R.string.premium_tier_1 else R.string.premium_tier_2)
        binding.premiumUpsellYearlyTitle.text = getString(R.string.premium_upsell_yearly_title)
            .replace("{discountPercentage}", discount)
            .replace("{planName}", tierName)
        binding.premiumUpsellYearlyDescription.text = getString(R.string.premium_upsell_yearly_description)
            .replace("{discountPercentage}", discount)
        binding.premiumUpsellHeader.setImageResource(
            if (tierOne) R.drawable.img_premium_guild_subscription_purchase_header_tier_1
            else R.drawable.img_premium_guild_subscription_purchase_header_tier_2,
        )
        binding.purchasePremiumMonthly.visibility =
            if (argumentsOrDefault.getBoolean(ARG_HAS_MONTHLY_PLAN)) View.GONE else View.VISIBLE
        binding.purchasePremiumMonthly.setOnClickListener { finish(ACTION_MONTHLY) }
        binding.purchasePremiumYearly.text = getString(R.string.premium_upsell_yearly_cta)
            .replace("!!{price}!!", argumentsOrDefault.getString(ARG_PRICE).orEmpty())
        binding.purchasePremiumYearly.setOnClickListener { finish(ACTION_YEARLY) }
    }

    override fun onCancel(dialog: DialogInterface) {
        if (!actionTaken) sendResult(ACTION_CANCEL)
        super.onCancel(dialog)
    }

    private fun finish(action: String) {
        actionTaken = true
        sendResult(action)
        dismiss()
    }

    private fun sendResult(action: String) {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putString(RESULT_ACTION, action)
                putString(RESULT_SKU_NAME, argumentsOrDefault.getString(ARG_SKU_NAME))
            },
        )
    }

    companion object {
        const val REQUEST_KEY = "PREMIUM_YEARLY_UPGRADE_RESULT"
        const val RESULT_ACTION = "RESULT_ACTION"
        const val RESULT_SKU_NAME = "RESULT_SKU_NAME"
        const val ACTION_MONTHLY = "monthly"
        const val ACTION_YEARLY = "yearly"
        const val ACTION_CANCEL = "cancel"
        private const val ARG_SKU_NAME = "ARG_SKU_NAME"
        private const val ARG_PRICE = "ARG_PRICE"
        private const val ARG_HAS_MONTHLY_PLAN = "ARG_HAS_MONTHLY_PLAN"
        private const val ARG_TIER_ONE = "ARG_TIER_ONE"

        fun show(
            fragmentManager: FragmentManager,
            skuName: String,
            price: String,
            hasMonthlyPlan: Boolean,
            tierOne: Boolean,
        ) {
            PremiumUpgradeYearlyDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_SKU_NAME, skuName)
                    putString(ARG_PRICE, price)
                    putBoolean(ARG_HAS_MONTHLY_PLAN, hasMonthlyPlan)
                    putBoolean(ARG_TIER_ONE, tierOne)
                }
            }.show(fragmentManager, PremiumUpgradeYearlyDialog::class.java.name)
        }
    }
}
