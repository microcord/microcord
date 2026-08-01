package com.discord.widgets.settings.premium

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.databinding.PremiumAndGuildBoostActivatedDialogBinding
import com.discord.i18n.i18nRender

class PremiumAndGuildBoostActivatedDialog :
    AppDialog(R.layout.premium_and_guild_boost_activated_dialog) {
    private var binding: PremiumAndGuildBoostActivatedDialogBinding? = null
    private var onConfirm: (() -> Unit)? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val binding = PremiumAndGuildBoostActivatedDialogBinding.bind(view).also { this.binding = it }
        binding.premiumAndGuildBoostActivatedText.text = requireContext().i18nRender(
            R.string.billing_premium_and_premium_guild_plan_activated,
            "planName" to argumentsOrDefault.getString(ARG_PLAN_NAME).orEmpty(),
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
        private const val ARG_PLAN_NAME = "extra_plan_text"

        fun show(fragmentManager: FragmentManager, planName: String, onConfirm: () -> Unit) {
            PremiumAndGuildBoostActivatedDialog().apply {
                this.onConfirm = onConfirm
                arguments = Bundle().apply { putString(ARG_PLAN_NAME, planName) }
            }.show(fragmentManager, PremiumAndGuildBoostActivatedDialog::class.java.simpleName)
        }
    }
}
