package com.discord.api.safetyhub

import com.google.gson.annotations.SerializedName

/** `GET /safety-hub/@me`, or `POST /safety-hub/suspended/@me` once login hands back a token. */
data class SafetyHub(
    val classifications: List<SafetyRecordClassification> = emptyList(),
    @SerializedName("guild_classifications") val guildClassifications: List<SafetyRecordClassification> = emptyList(),
    @SerializedName("account_standing") val accountStanding: AccountStanding = AccountStanding(),
    @SerializedName("is_dsa_eligible") val isDsaEligible: Boolean = false,
    @SerializedName("is_appeal_eligible") val isAppealEligible: Boolean = false,
    val username: String? = null,
    @SerializedName("appeal_eligibility") val appealEligibility: List<AppealEligibility> = emptyList(),
)

data class AccountStanding(val state: AccountStandingState = AccountStandingState.ALL_GOOD)

data class SafetyRecordClassification(
    // Defaulted like every other field so Kotlin emits a no-arg constructor: without one Gson
    // allocates the class directly and the non-null collections below come back null.
    val id: String = "",
    @SerializedName("classification_type") val classificationType: Int? = null,
    val description: String? = null,
    @SerializedName("explainer_link") val explainerLink: String? = null,
    val actions: List<SafetyRecordAction> = emptyList(),
    @SerializedName("max_expiration_time") val maxExpirationTime: String? = null,
    @SerializedName("flagged_content") val flaggedContent: List<FlaggedContent> = emptyList(),
    @SerializedName("appeal_status") val appealStatus: AppealStatus? = null,
    @SerializedName("guild_metadata") val guildMetadata: SafetyGuildMetadata? = null,
    @SerializedName("is_spam") val isSpam: Boolean = false,
    @SerializedName("is_coppa") val isCoppa: Boolean = false,
    @SerializedName("appeal_ingestion_type") val appealIngestionType: AppealIngestionType? = null,
)

data class SafetyRecordAction(
    val id: String? = null,
    @SerializedName("action_type") val actionType: SafetyActionType? = null,
    val descriptions: List<String> = emptyList(),
)

data class AppealStatus(val status: AppealStatusType? = null)

data class SafetyGuildMetadata(
    val name: String? = null,
    @SerializedName("icon_hash") val iconHash: String? = null,
    @SerializedName("member_type") val memberType: SafetyGuildMemberType? = null,
)

data class FlaggedContent(
    val type: String? = null,
    val id: String? = null,
    val content: String? = null,
    val attachments: List<FlaggedAttachment> = emptyList(),
) {
    companion object {
        const val TYPE_MESSAGE = "message"
    }
}

data class FlaggedAttachment(
    val id: String? = null,
    val url: String? = null,
    val height: Int? = null,
    val width: Int? = null,
    val filename: String? = null,
)
