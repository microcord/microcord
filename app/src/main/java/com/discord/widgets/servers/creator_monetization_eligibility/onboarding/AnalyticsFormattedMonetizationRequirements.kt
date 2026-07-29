package com.discord.widgets.servers.creator_monetization_eligibility.onboarding

import com.discord.api.creatormonetization.CreatorMonetizationEligibilityRequirements
import com.discord.api.creatormonetization.CreatorMonetizationEnableRequest
import com.google.gson.Gson

data class AnalyticsFormattedMonetizationRequirements(
    val isEligibleForMonetization: Boolean,
    val hasSufficientMembers: Boolean,
    val meetsServerAgeRequirement: Boolean,
    val noRecentViolations: Boolean,
    val weeklyCommunicators: Boolean,
    val hasMemberRetention: Boolean,
    val notNSFW: Boolean,
    val canApply: Boolean,
    val isApplicationPending: Boolean,
    val minimumAgeInDays: Int,
    val minimumSize: Int,
    val latestRequest: CreatorMonetizationEnableRequest?,
    val rejection: CreatorMonetizationEligibilityRequirements.Rejection?,
    val guildMemberCount: Int?,
    val communicatorCount: Int?,
    val retentionScore: Double?,
) {
    fun toJson(): String = gsonInstance.toJson(this)

    companion object {
        private val gsonInstance by lazy(::Gson)

        fun CreatorMonetizationEligibilityRequirements.toAnalyticsFormat() =
            AnalyticsFormattedMonetizationRequirements(
                isEligibleForMonetization = sufficient,
                hasSufficientMembers = size,
                meetsServerAgeRequirement = age,
                noRecentViolations = safeEnvironment,
                weeklyCommunicators = engagementHealthy,
                hasMemberRetention = retentionHealthy,
                notNSFW = nsfwProperties.isEmpty(),
                canApply = canApply,
                isApplicationPending = latestRequest?.state == CreatorMonetizationEnableRequest.State.OPEN,
                minimumAgeInDays = minimumAgeInDays,
                minimumSize = minimumSize,
                latestRequest = latestRequest,
                rejection = rejection,
                guildMemberCount = healthScore?.guildSize,
                communicatorCount = healthScore?.communicators,
                retentionScore = healthScore?.percRetW1,
            )
    }
}
