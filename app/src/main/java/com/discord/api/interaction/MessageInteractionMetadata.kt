package com.discord.api.interaction

import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.user.User

/**
 * `interaction_metadata`: what the message came from.
 *
 * Supersedes the deprecated `interaction` field, and unlike it is also present on responses to
 * components and modals — so [type] must be checked before treating a message as a command
 * invocation.
 */
data class MessageInteractionMetadata(
    val id: Long? = null,
    val type: Int? = null,
    /** The full command path, subcommands included; only sent for command interactions. */
    val name: String? = null,
    val commandType: Int? = null,
    val ephemeralityReason: Int? = null,
    val user: User? = null,
    /** Installation context → the guild or user the application was installed by. */
    val authorizingIntegrationOwners: Map<Int, Long>? = null,
    val originalResponseMessageId: Long? = null,
    val interactedMessageId: Long? = null,
    /** The interaction that opened the modal, for `MODAL_SUBMIT` responses. */
    val triggeringInteractionMetadata: MessageInteractionMetadata? = null,
    val targetUser: User? = null,
    val targetMessageId: Long? = null,
) {
    companion object {
        /** Why an interaction response was made ephemeral, when the server volunteers a reason. */
        @StringRes
        fun ephemeralityReasonRes(reason: Int?): Int? = when (reason) {
            1 -> R.string.ephemeral_reason_feature_limited
            2 -> R.string.ephemeral_reason_guild_feature_limited
            3 -> R.string.ephemeral_reason_user_feature_limited
            4 -> R.string.ephemeral_reason_slowmode
            5 -> R.string.ephemeral_reason_rate_limit
            6 -> R.string.ephemeral_reason_cannot_message_user
            7 -> R.string.ephemeral_reason_user_verification_level
            8 -> R.string.ephemeral_reason_cannot_unarchive_thread
            9 -> R.string.ephemeral_reason_cannot_join_thread
            10 -> R.string.ephemeral_reason_missing_permissions
            11 -> R.string.ephemeral_reason_cannot_send_attachments
            12 -> R.string.ephemeral_reason_cannot_send_embeds
            13 -> R.string.ephemeral_reason_cannot_send_stickers
            14 -> R.string.ephemeral_reason_automod_blocked
            15 -> R.string.ephemeral_reason_harmful_link
            16 -> R.string.ephemeral_reason_cannot_use_command
            17 -> R.string.ephemeral_reason_beta_guild_size
            18 -> R.string.ephemeral_reason_cannot_use_external_apps
            else -> null
        }
    }
}
