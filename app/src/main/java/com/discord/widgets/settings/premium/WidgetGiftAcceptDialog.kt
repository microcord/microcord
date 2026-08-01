package com.discord.widgets.settings.premium

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.premium.SubscriptionInterval
import com.discord.app.AppDialog
import com.discord.app.AppViewFlipper
import com.discord.models.domain.ModelGift
import com.discord.stores.StoreGifting
import com.discord.stores.StoreStream
import com.discord.utilities.premium.PremiumUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.view.text.LinkifiedTextView
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton

internal class WidgetGiftAcceptDialog : AppDialog(R.layout.widget_accept_gift_dialog) {
    private val giftCode get() = argumentsOrDefault.getString(ARG_GIFT_CODE).orEmpty()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        if (giftCode.isEmpty()) dismiss()
        view.findViewById<LinkifiedTextView>(R.id.accept_gift_disclaimer_text).setText(
            R.string.gift_confirmation_body_confirm_nitro_disclaimer,
        )
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        giftingStore.requestGift(giftCode).ui(this)
            .appSubscribe(javaClass, onNext = ::render)
    }

    private fun render(state: StoreGifting.GiftState) {
        val view = view ?: return
        val flipper = view.findViewById<AppViewFlipper>(R.id.accept_gift_flipper)
        if (state is StoreGifting.GiftState.Loading) {
            flipper.displayedChild = 0
            return
        }
        val gift = (state as? StoreGifting.HasGift)?.gift
        if (gift == null) {
            dismiss()
            return
        }
        flipper.displayedChild = 1
        bindImage(view, gift)
        val header = view.findViewById<TextView>(R.id.accept_gift_header)
        val body = view.findViewById<TextView>(R.id.accept_gift_body_text)
        val confirm = view.findViewById<MaterialButton>(R.id.accept_gift_confirm)
        val progress = view.findViewById<ProgressBar>(R.id.accept_gift_progress)
        val skuName = gift.storeListing?.sku?.name.orEmpty()
        progress.visibility = if (state is StoreGifting.GiftState.Redeeming) View.VISIBLE else View.GONE
        confirm.visibility = if (state is StoreGifting.GiftState.Redeeming) View.GONE else View.VISIBLE
        isCancelable = state !is StoreGifting.GiftState.Redeeming

        when {
            state is StoreGifting.GiftState.RedeemedFailed -> {
                header.setText(R.string.gift_confirmation_header_fail)
                body.setText(errorMessage(state.errorCode))
                confirm.setText(if (state.canRetry) R.string.retry else R.string.gift_confirmation_button_fail)
                confirm.setOnClickListener {
                    if (state.canRetry) giftingStore.acceptGift(gift) else dismiss()
                }
            }
            gift.redeemed -> {
                header.text = if (gift.isAnyNitroGift()) {
                    text(R.string.gift_confirmation_header_success_nitro, "{skuName}" to skuName)
                } else getString(R.string.gift_confirmation_header_success)
                body.text = when {
                    gift.isNitroClassicGift() -> text(
                        R.string.gift_confirmation_body_success_nitro_classic_mobile,
                        "{timeFrame}" to timeFrame(gift),
                    )
                    gift.isNitroGift() -> text(
                        R.string.gift_confirmation_body_success_nitro_mobile,
                        "{timeframe}" to timeFrame(gift),
                    )
                    else -> text(R.string.gift_confirmation_body_success_mobile, "{skuName}" to skuName)
                }
                confirm.setText(R.string.gift_confirmation_button_success_mobile)
                confirm.setOnClickListener { dismiss() }
            }
            gift.maxUses == gift.uses -> {
                header.setText(R.string.gift_confirmation_header_fail)
                body.setText(R.string.gift_confirmation_body_claimed)
                confirm.setText(R.string.gift_confirmation_button_fail)
                confirm.setOnClickListener { dismiss() }
            }
            else -> {
                header.text = if (gift.isAnyNitroGift()) {
                    text(R.string.gift_confirmation_header_confirm_nitro, "{skuName}" to skuName)
                } else getString(R.string.gift_confirmation_header_confirm)
                body.text = if (gift.isAnyNitroGift()) {
                    text(
                        R.string.gift_confirmation_body_confirm_nitro,
                        "{skuName}" to skuName,
                        "{timeFrame}" to timeFrame(gift),
                    )
                } else text(R.string.gift_confirmation_body_confirm, "{skuName}" to skuName)
                confirm.setText(R.string.gift_confirmation_button_confirm_mobile)
                confirm.setOnClickListener { giftingStore.acceptGift(gift) }
            }
        }
    }

    private fun bindImage(view: View, gift: ModelGift) {
        val image = view.findViewById<SimpleDraweeView>(R.id.accept_gift_body_image)
        if (gift.isAnyNitroGift()) image.setActualImageResource(PremiumUtils.getNitroGiftIcon(gift))
    }

    private fun errorMessage(code: Int?) = when (code) {
        50054 -> R.string.gift_confirmation_body_self_gift_no_payment
        100024 -> R.string.gift_confirmation_body_error_nitro_upgrade_downgrade
        100022 -> R.string.gift_confirmation_body_error_subscription_managed
        100025 -> R.string.gift_confirmation_body_error_invoice_open
        else -> R.string.gift_confirmation_body_unknown_error
    }

    private fun timeFrame(gift: ModelGift): String {
        val plan = gift.subscriptionPlan ?: return ""
        val plural = if (plan.interval == SubscriptionInterval.MONTHLY) {
            R.plurals.gift_confirmation_nitro_time_frame_months_time
        } else {
            R.plurals.gift_confirmation_nitro_time_frame_years_time
        }
        return resources.getQuantityString(plural, plan.intervalCount, plan.intervalCount)
    }

    private fun text(resource: Int, vararg values: Pair<String, String>): String =
        values.fold(getString(resource)) { value, replacement -> value.replace(replacement.first, replacement.second) }

    companion object {
        private const val ARG_GIFT_CODE = "ARG_GIFT_CODE"
        private val giftingStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            StoreGifting(
                dispatcher = StoreStream.getUserConnections().dispatcher,
                onGiftRedeemed = { StoreStream.getEntitlements().fetchMyGiftEntitlements() },
            )
        }

        fun show(fragmentManager: FragmentManager, giftCode: String) {
            WidgetGiftAcceptDialog().apply {
                arguments = Bundle().apply { putString(ARG_GIFT_CODE, giftCode) }
            }.show(fragmentManager, WidgetGiftAcceptDialog::class.java.name)
        }
    }
}
