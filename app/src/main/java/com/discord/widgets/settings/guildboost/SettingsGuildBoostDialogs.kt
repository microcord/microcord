package com.discord.widgets.settings.guildboost

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.AppViewFlipper
import com.discord.stores.StoreSubscriptions
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.time.TimeUtils
import com.discord.views.LoadingButton
import com.google.android.material.button.MaterialButton

internal class GuildBoostCancelDialog : AppDialog(R.layout.guild_boost_cancel_dialog) {
    private val slotId get() = argumentsOrDefault.getLong(ARG_SLOT_ID)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<MaterialButton>(R.id.guild_boost_cancel_nevermind)
            .setOnClickListener { dismiss() }
        view.findViewById<LoadingButton>(R.id.guild_boost_cancel_confirm)
            .setOnClickListener { cancelBoost() }
        bindLoaded(view)
    }

    private fun bindLoaded(view: View) {
        view.findViewById<AppViewFlipper>(R.id.guild_boost_cancel_flipper).displayedChild = 1
        val slot = (StoreStream.getGuildBoost().getState() as? com.discord.stores.StoreGuildBoost.State.Loaded)
            ?.boostSlotMap?.get(slotId)
        val subscription = (StoreStream.getSubscriptions().getSubscriptions() as? StoreSubscriptions.SubscriptionsState.Loaded)
            ?.premiumSubscription
        val endDate = subscription?.currentPeriodEnd?.let { TimeUtils.renderUtcDate(it, requireContext()) }.orEmpty()
        val body = if (slot?.premiumGuildSubscription == null) {
            R.string.premium_guild_subscription_cancel_body_inventory
        } else {
            R.string.premium_guild_subscription_cancel_body_guild
        }
        view.findViewById<TextView>(R.id.guild_boost_cancel_body).text =
            getString(body).replace("{endDate}", endDate).replace("**", "")
    }

    private fun cancelBoost() {
        val view = view ?: return
        val button = view.findViewById<LoadingButton>(R.id.guild_boost_cancel_confirm)
        val error = view.findViewById<TextView>(R.id.guild_boost_cancel_error)
        button.setIsLoading(true)
        error.visibility = View.GONE
        isCancelable = false
        RestAPI.api.cancelSubscriptionSlot(slotId).restSubscribeOn().ui(this).appSubscribe(
            javaClass,
            errorHandler = {
                button.setIsLoading(false)
                error.visibility = View.VISIBLE
                isCancelable = true
            },
        ) { slot ->
            StoreStream.getGuildBoost().updateGuildBoostSlot(slot)
            dismiss()
        }
    }

    companion object {
        private const val ARG_SLOT_ID = "extra_slot_id"
        fun show(fragmentManager: FragmentManager, slotId: Long) {
            GuildBoostCancelDialog().apply {
                arguments = Bundle().apply { putLong(ARG_SLOT_ID, slotId) }
            }.show(fragmentManager, GuildBoostCancelDialog::class.java.name)
        }
    }
}

internal class GuildBoostUncancelDialog : AppDialog(R.layout.guild_boost_uncancel_dialog) {
    private val slotId get() = argumentsOrDefault.getLong(ARG_SLOT_ID)
    private var completed = false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<AppViewFlipper>(R.id.guild_boost_uncancel_flipper).displayedChild = 1
        view.findViewById<MaterialButton>(R.id.guild_boost_uncancel_nevermind)
            .setOnClickListener { dismiss() }
        view.findViewById<LoadingButton>(R.id.guild_boost_uncancel_confirm)
            .setOnClickListener { if (completed) dismiss() else uncancelBoost() }
    }

    private fun uncancelBoost() {
        val view = view ?: return
        val button = view.findViewById<LoadingButton>(R.id.guild_boost_uncancel_confirm)
        val error = view.findViewById<TextView>(R.id.guild_boost_uncancel_error)
        button.setIsLoading(true)
        error.visibility = View.GONE
        isCancelable = false
        RestAPI.api.uncancelSubscriptionSlot(slotId).restSubscribeOn().ui(this).appSubscribe(
            javaClass,
            errorHandler = {
                button.setIsLoading(false)
                error.visibility = View.VISIBLE
                isCancelable = true
            },
        ) { slot ->
            StoreStream.getGuildBoost().updateGuildBoostSlot(slot)
            completed = true
            button.setIsLoading(false)
            button.setText(getString(R.string.okay))
            view.findViewById<TextView>(R.id.guild_boost_uncancel_header)
                .setText(R.string.premium_guild_subscription_inventory_uncancel_confirm_title)
            view.findViewById<TextView>(R.id.guild_boost_uncancel_body)
                .setText(R.string.premium_guild_subscription_inventory_uncancel_confirm_description)
            view.findViewById<ImageView>(R.id.guild_boost_uncancel_image).visibility = View.VISIBLE
            view.findViewById<MaterialButton>(R.id.guild_boost_uncancel_nevermind).visibility = View.GONE
            isCancelable = true
        }
    }

    companion object {
        private const val ARG_SLOT_ID = "extra_slot_id"
        fun show(fragmentManager: FragmentManager, slotId: Long) {
            GuildBoostUncancelDialog().apply {
                arguments = Bundle().apply { putLong(ARG_SLOT_ID, slotId) }
            }.show(fragmentManager, GuildBoostUncancelDialog::class.java.name)
        }
    }
}
