package com.discord.views

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.discord.R
import com.discord.models.domain.ModelSubscription
import com.discord.utilities.drawable.getThemedDrawableRes
import com.google.android.material.button.MaterialButton

class ActiveSubscriptionView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs, 0) {
    private val cancelButton: TextView
    private val headerBackground: ImageView
    private val headerIcon: ImageView
    private val headerLogo: ImageView
    private val headerText: TextView
    private val manageGuildBoostButton: MaterialButton
    private val progress: ProgressBar
    private val topButton: MaterialButton

    init {
        LayoutInflater.from(context).inflate(R.layout.view_active_subscription, this, true)
        cancelButton = requireNotNull(findViewById(R.id.active_subscription_cancel_button))
        headerBackground = requireNotNull(findViewById(R.id.active_subscription_header_background))
        headerIcon = requireNotNull(findViewById(R.id.active_subscription_header_icon))
        headerLogo = requireNotNull(findViewById(R.id.active_subscription_header_logo))
        headerText = requireNotNull(findViewById(R.id.active_subscription_header_text))
        manageGuildBoostButton = requireNotNull(findViewById(R.id.active_subscription_manage_guild_boost_button))
        progress = requireNotNull(findViewById(R.id.active_subscription_progress))
        topButton = requireNotNull(findViewById(R.id.active_subscription_top_button))
    }

    @JvmOverloads
    fun configure(
        activeSubscriptionType: ActiveSubscriptionType,
        status: ModelSubscription.Status,
        isTrialSubscription: Boolean = false,
        priceText: CharSequence,
        isLoading: Boolean = false,
        guildBoostCount: Int = 0,
        topBtnCallback: (() -> Unit)? = null,
        manageGuildBoostBtnCallback: (() -> Unit)? = null,
        cancelBtnCallback: (() -> Unit)? = null,
        isBasePlanMutatingToNonePlan: Boolean = false,
    ) {
        val visualState = when (status) {
            ModelSubscription.Status.CANCELED -> VisualState.RESUBSCRIBE
            ModelSubscription.Status.PAST_DUE, ModelSubscription.Status.ACCOUNT_HOLD -> VisualState.ERROR
            else -> VisualState.ACTIVE
        }
        headerBackground.setImageResource(activeSubscriptionType.background(visualState))
        headerIcon.setImageResource(activeSubscriptionType.image(visualState))
        headerLogo.setImageResource(context.getThemedDrawableRes(activeSubscriptionType.headerLogo))
        headerLogo.contentDescription = context.getString(activeSubscriptionType.label)
        headerText.text = description(activeSubscriptionType, status, priceText, guildBoostCount)

        progress.visibility = if (isLoading) View.VISIBLE else View.GONE
        topButton.apply {
            visibility = if (topBtnCallback != null) View.VISIBLE else View.GONE
            isEnabled = !isTrialSubscription
            setText(
                when (status) {
                    ModelSubscription.Status.CANCELED -> R.string.resubscribe
                    ModelSubscription.Status.ACCOUNT_HOLD -> R.string.premium_subscription_past_due_invalid_payment_notice_button_text
                    else -> R.string.billing_manage_subscription
                },
            )
            setOnClickListener { topBtnCallback?.invoke() }
        }
        manageGuildBoostButton.apply {
            visibility = if (manageGuildBoostBtnCallback != null) View.VISIBLE else View.GONE
            setOnClickListener { manageGuildBoostBtnCallback?.invoke() }
        }
        cancelButton.apply {
            visibility = if (
                cancelBtnCallback != null && !isLoading &&
                status != ModelSubscription.Status.CANCELED && !isBasePlanMutatingToNonePlan
            ) View.VISIBLE else View.GONE
            setOnClickListener { cancelBtnCallback?.invoke() }
        }
    }

    fun a(
        activeSubscriptionType: ActiveSubscriptionType,
        status: ModelSubscription.Status,
        isTrialSubscription: Boolean,
        priceText: CharSequence,
        isLoading: Boolean,
        guildBoostCount: Int,
        topBtnCallback: (() -> Unit)?,
        manageGuildBoostBtnCallback: (() -> Unit)?,
        cancelBtnCallback: (() -> Unit)?,
        isBasePlanMutatingToNonePlan: Boolean,
    ) = configure(
        activeSubscriptionType,
        status,
        isTrialSubscription,
        priceText,
        isLoading,
        guildBoostCount,
        topBtnCallback,
        manageGuildBoostBtnCallback,
        cancelBtnCallback,
        isBasePlanMutatingToNonePlan,
    )

    private fun description(
        type: ActiveSubscriptionType,
        status: ModelSubscription.Status,
        price: CharSequence,
        guildBoostCount: Int,
    ): CharSequence {
        val resource = when (type) {
            ActiveSubscriptionType.PREMIUM_BASIC -> when (status) {
                ModelSubscription.Status.CANCELED -> R.string.premium_subscription_description_tier_0_pending_cancelation
                ModelSubscription.Status.ACCOUNT_HOLD -> R.string.premium_subscription_description_tier_0_account_hold
                else -> R.string.premium_subscription_description_tier_0
            }
            ActiveSubscriptionType.PREMIUM_CLASSIC -> when (status) {
                ModelSubscription.Status.CANCELED -> R.string.premium_subscription_description_tier_1_pending_cancelation
                ModelSubscription.Status.ACCOUNT_HOLD -> R.string.premium_subscription_description_tier_1_account_hold
                else -> R.string.premium_subscription_description_tier_1
            }
            ActiveSubscriptionType.PREMIUM,
            ActiveSubscriptionType.PREMIUM_AND_PREMIUM_GUILD,
            -> when (status) {
                ModelSubscription.Status.CANCELED -> R.string.premium_subscription_description_tier_2_pending_cancelation
                ModelSubscription.Status.ACCOUNT_HOLD -> R.string.premium_subscription_description_tier_2_account_hold
                else -> R.string.premium_subscription_description_tier_2
            }
            ActiveSubscriptionType.PREMIUM_GUILD -> when (status) {
                ModelSubscription.Status.CANCELED -> R.string.premium_guild_subscriptions_renewal_info_pending_cancelation
                ModelSubscription.Status.ACCOUNT_HOLD -> R.string.premium_guild_subscriptions_renewal_info_account_hold
                else -> R.string.premium_guild_subscriptions_renewal_info
            }
        }
        return bold(
            context.getString(resource)
                .replace("{num}", guildBoostCount.toString())
                .replace("{quantity}", context.getString(R.string.premium_guild_subscription))
                .replace("{boostQuantity}", guildBoostCount.toString())
                .replace("{rate}", price.toString())
                .replace("!!", ""),
        )
    }

    private fun bold(value: String): CharSequence {
        val result = SpannableStringBuilder(value)
        while (true) {
            val start = result.indexOf("**")
            if (start < 0) break
            result.delete(start, start + 2)
            val end = result.indexOf("**", start)
            if (end < 0) break
            result.delete(end, end + 2)
            result.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return result
    }

    enum class ActiveSubscriptionType(
        val headerLogo: Int,
        private val headerImage: Int,
        private val headerImageResub: Int,
        private val headerImageError: Int,
        private val headerBackground: Int,
        private val headerBackgroundResub: Int,
        private val headerBackgroundError: Int,
        val label: Int,
    ) {
        PREMIUM_BASIC(
            R.attr.img_premium_basic_subscription_header_logo,
            R.drawable.img_premium_basic_subscription_header,
            R.drawable.img_premium_subscription_header_resub,
            R.drawable.img_premium_subscription_header_error,
            R.drawable.drawable_bg_nitro_gradient,
            R.drawable.drawable_bg_nitro_gradient_diagonal,
            R.drawable.drawable_bg_nitro_gradient_diagonal,
            R.string.premium_tier_0,
        ),
        PREMIUM_CLASSIC(
            R.attr.img_premium_classic_subscription_header_logo,
            R.drawable.img_premium_classic_subscription_header,
            R.drawable.img_premium_classic_subscription_header_resub,
            R.drawable.img_premium_classic_subscription_header_error,
            R.drawable.drawable_bg_nitro_classic_gradient_diagonal,
            R.drawable.drawable_bg_nitro_classic_gradient_diagonal,
            R.drawable.drawable_bg_nitro_classic_gradient_diagonal,
            R.string.premium_tier_1,
        ),
        PREMIUM(
            R.attr.img_premium_subscription_header_logo,
            R.drawable.img_premium_subscription_header,
            R.drawable.img_premium_subscription_header_resub,
            R.drawable.img_premium_subscription_header_error,
            R.drawable.drawable_bg_nitro_gradient_diagonal,
            R.drawable.drawable_bg_nitro_gradient_diagonal,
            R.drawable.drawable_bg_nitro_gradient_diagonal,
            R.string.premium_tier_2,
        ),
        PREMIUM_GUILD(
            R.attr.img_premium_guild_subscription_header_logo,
            R.drawable.img_premium_guild_subscription_header,
            R.drawable.img_premium_guild_subscription_header_resub,
            R.drawable.img_premium_guild_subscription_header_error,
            R.drawable.drawable_bg_premium_guild_gradient,
            R.drawable.drawable_bg_premium_guild_gradient,
            R.drawable.drawable_bg_premium_guild_gradient,
            R.string.premium_guild_subscription,
        ),
        PREMIUM_AND_PREMIUM_GUILD(
            R.attr.img_logo_discord_nitro_and_boost,
            R.drawable.img_premium_and_premium_guild_subscription_header,
            R.drawable.img_premium_and_premium_guild_subscription_header,
            R.drawable.img_premium_and_premium_guild_subscription_header,
            R.drawable.drawable_bg_premium_tier_2_and_premium_guild_gradient,
            R.drawable.drawable_bg_premium_tier_2_and_premium_guild_gradient,
            R.drawable.drawable_bg_premium_tier_2_and_premium_guild_gradient,
            R.string.premium_tier_2,
        );

        internal fun image(state: VisualState) = when (state) {
            VisualState.ACTIVE -> headerImage
            VisualState.RESUBSCRIBE -> headerImageResub
            VisualState.ERROR -> headerImageError
        }

        internal fun background(state: VisualState) = when (state) {
            VisualState.ACTIVE -> headerBackground
            VisualState.RESUBSCRIBE -> headerBackgroundResub
            VisualState.ERROR -> headerBackgroundError
        }
    }

    internal enum class VisualState { ACTIVE, RESUBSCRIBE, ERROR }

    companion object {
        fun fromSubscription(subscription: ModelSubscription): ActiveSubscriptionType {
            val sku = subscription.paymentGatewayPlanId.orEmpty()
            if (subscription.isGoogleSubscription()) {
                if ("guild" in sku && "premium" in sku) {
                    return ActiveSubscriptionType.PREMIUM_AND_PREMIUM_GUILD
                }
                if ("guild" in sku) return ActiveSubscriptionType.PREMIUM_GUILD
            }
            return when (subscription.items.firstOrNull()?.planId) {
                978380692553465866L -> ActiveSubscriptionType.PREMIUM_BASIC
                511651871736201216L,
                511651876987469824L,
                -> ActiveSubscriptionType.PREMIUM_CLASSIC
                590665532894740483L,
                944037355453415424L,
                944037391444738048L,
                590665538238152709L,
                -> ActiveSubscriptionType.PREMIUM_GUILD
                else -> ActiveSubscriptionType.PREMIUM
            }
        }

        @JvmStatic
        fun b(subscription: ModelSubscription) = fromSubscription(subscription)
    }
}
