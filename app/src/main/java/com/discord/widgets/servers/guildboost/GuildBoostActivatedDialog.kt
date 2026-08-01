package com.discord.widgets.servers.guildboost

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.databinding.GuildBoostActivatedDialogBinding
import com.discord.i18n.i18nRender
import com.discord.i18n.i18nPlural

class GuildBoostActivatedDialog : AppDialog(R.layout.guild_boost_activated_dialog) {
    private var binding: GuildBoostActivatedDialogBinding? = null
    private var dismissListener: (() -> Unit)? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val binding = GuildBoostActivatedDialogBinding.bind(view).also { this.binding = it }
        binding.guildBoostActivatedBody1.text = argumentsOrDefault.getCharSequence(EXTRA_BODY_1)
        binding.guildBoostActivatedBody2.text = argumentsOrDefault.getCharSequence(EXTRA_BODY_2)
        binding.guildBoostActivatedOk.setOnClickListener { dismiss() }
        binding.guildBoostActivatedLottie.apply {
            setMaxFrame(825)
            frame = 180
            repeatCount = ValueAnimator.INFINITE
            addAnimatorListener(object : Animator.AnimatorListener {
                override fun onAnimationRepeat(animation: Animator) = setMinFrame(540)
                override fun onAnimationStart(animation: Animator) = Unit
                override fun onAnimationEnd(animation: Animator) = Unit
                override fun onAnimationCancel(animation: Animator) = Unit
            })
            playAnimation()
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        dismissListener?.invoke()
        super.onDismiss(dialog)
    }

    companion object {
        private const val EXTRA_BODY_1 = "extra_body1_text"
        private const val EXTRA_BODY_2 = "extra_body2_text"

        fun show(
            fragmentManager: FragmentManager,
            context: Context,
            guildName: String?,
            subscriptionCount: Int,
            transferred: Boolean,
            dismissListener: () -> Unit,
        ) {
            val dialog = GuildBoostActivatedDialog()
            dialog.dismissListener = dismissListener
            dialog.arguments = Bundle().apply {
                putCharSequence(
                    EXTRA_BODY_1,
                    context.getString(
                        if (transferred) R.string.guild_subscription_purchase_modal_transferred_description_mobile1
                        else R.string.guild_subscription_purchase_modal_activated_description_mobile1,
                    ),
                )
                putCharSequence(
                    EXTRA_BODY_2,
                    if (guildName == null) {
                        context.i18nRender(
                            R.string.guild_subscription_purchase_modal_activated_description_no_application,
                            "guildSubscriptionQuantity" to context.i18nPlural(
                                R.plurals.guild_subscription_purchase_modal_activated_description_no_application_guildSubscriptionQuantity,
                                subscriptionCount,
                                "#" to subscriptionCount,
                            ),
                        )
                    } else {
                        context.i18nRender(
                            if (transferred) R.string.guild_subscription_purchase_modal_transferred_description_mobile2
                            else R.string.guild_subscription_purchase_modal_activated_description_mobile2,
                            "guildName" to guildName,
                            "guildSubscriptionQuantity" to context.i18nPlural(
                                if (transferred) R.plurals.guild_subscription_purchase_modal_transferred_description_mobile2_guildSubscriptionQuantity
                                else R.plurals.guild_subscription_purchase_modal_activated_description_guildSubscriptionQuantity,
                                subscriptionCount,
                                "#" to subscriptionCount,
                            ),
                        )
                    },
                )
            }
            dialog.show(fragmentManager, GuildBoostActivatedDialog::class.java.name)
        }
    }
}
