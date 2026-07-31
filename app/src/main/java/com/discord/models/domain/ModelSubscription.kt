package com.discord.models.domain

import com.discord.models.domain.premium.SubscriptionPlanType
import com.discord.utilities.time.TimeUtils
import com.google.gson.annotations.SerializedName

data class ModelSubscription(
    val id: String,
    val createdAt: String,
    val canceledAt: String?,
    val currentPeriodStart: String,
    val currentPeriodEnd: String,
    val paymentSourceId: String?,
    val paymentGateway: Int?,
    val trialId: String?,
    val trialEndsAt: String?,
    val renewalMutations: SubscriptionRenewalMutations?,
    override val items: List<SubscriptionItem>,
    val paymentGatewayPlanId: String?,
    @SerializedName("status") private val statusValue: Int,
    @SerializedName("type") private val typeValue: Int,
) : HasSubscriptionItems() {
    data class SubscriptionItem(val id: Long, val planId: Long, val quantity: Int)

    /**
     * A plan bought on top of the base plan — in practice the guild-boost slots bundled with a
     * Nitro subscription. Unlike [SubscriptionItem] it has no id of its own because it is derived
     * from the subscription's items rather than sent as a separate record.
     */
    data class SubscriptionAdditionalPlan(val planId: Long, val quantity: Int)

    data class SubscriptionRenewalMutations(
        override val items: List<SubscriptionItem>,
        val paymentGatewayPlanId: String?,
    ) : HasSubscriptionItems()

    enum class PaymentGateway(val intRepresentation: Int?) {
        STRIPE(1),
        BRAINTREE(2),
        APPLE(3),
        GOOGLE(4),
        UNKNOWN(null);

        companion object {
            fun from(value: Int?) = entries.firstOrNull { it.intRepresentation == value } ?: UNKNOWN
        }
    }

    enum class Status(val intRepresentation: Int) {
        UNPAID(0),
        ACTIVE(1),
        PAST_DUE(2),
        CANCELED(3),
        ENDED(4),
        ACCOUNT_HOLD(6);

        companion object {
            fun from(value: Int) = entries.firstOrNull { it.intRepresentation == value }
                ?: throw IllegalArgumentException("unsupported subscription status: $value")
        }
    }

    enum class Type { PREMIUM, GUILD }

    val status: Status get() = Status.from(statusValue)
    val type: Type
        get() = when (typeValue) {
            1 -> Type.PREMIUM
            2 -> Type.GUILD
            else -> throw IllegalArgumentException("unsupported subscription type: $typeValue")
        }

    fun hasPlan(planId: Long) = items.any { it.planId == planId }
    fun hasAnyOfPlans(planIds: Set<Long>) = items.any { it.planId in planIds }
    fun getAccountHoldEstimatedExpirationTimestamp() = TimeUtils.addDaysToDate(currentPeriodEnd, 30)
    fun getPlanType(): SubscriptionPlanType = SubscriptionPlanType.from(premiumBasePlanId)
    fun isAppleSubscription() = PaymentGateway.from(paymentGateway) == PaymentGateway.APPLE
    fun isGoogleSubscription() = PaymentGateway.from(paymentGateway) == PaymentGateway.GOOGLE
    fun isMobileManaged() = isAppleSubscription() || isGoogleSubscription()
    fun isNonePlan() = when (getPlanType()) {
        SubscriptionPlanType.NONE_MONTH,
        SubscriptionPlanType.NONE_YEAR,
        SubscriptionPlanType.NONE_3_MONTH,
        SubscriptionPlanType.NONE_6_MONTH,
        -> true
        else -> false
    }
}
