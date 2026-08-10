@file:JvmName("WidgetChatListAdapterItemAutoModSystemMessageEmbedKt")

package com.discord.widgets.chat.list.adapter

import com.discord.api.message.embed.MessageEmbed

const val AUTOMOD_EMBED_FIELD_CHANNEL_ID = "channel_id"
const val AUTOMOD_EMBED_FIELD_RULE_NAME = "rule_name"
const val AUTOMOD_EMBED_FLAGGED_MESSAGE_ID = "flagged_message_id"
const val AUTOMOD_EMBED_DECISION_ID = "decision_id"
const val AUTOMOD_EMBED_KEYWORD = "keyword"
const val AUTOMOD_EMBED_KEYWORD_MATCHED_CONTENT = "keyword_matched_content"
const val AUTOMOD_EMBED_TIMEOUT_DURATION = "timeout_duration"
const val AUTOMOD_EMBED_QUARANTINE_USER = "quarantine_user"
const val AUTOMOD_EMBED_QUARANTINE_USER_ACTION = "quarantine_user_action"
const val AUTOMOD_EMBED_DECISION_REASON = "decision_reason"
const val AUTOMOD_EMBED_ALERT_ACTIONS_EXECUTION = "alert_actions_execution"
const val AUTOMOD_EMBED_QUARANTINE_EVENT = "quarantine_event"
const val AUTOMOD_EMBED_BLOCK_PROFILE_UPDATE_TYPE = "block_profile_update_type"
const val AUTOMOD_EMBED_VOICE_CHANNEL_STATUS_OUTCOME = "voice_channel_status_outcome"
const val AUTOMOD_EMBED_APPLICATION_NAME = "application_name"
const val AUTOMOD_EMBED_INTERACTION_USER_ID = "interaction_user_id"
const val AUTOMOD_EMBED_INTERACTION_CALLBACK_TYPE = "interaction_callback_type"
const val AUTOMOD_EMBED_DECISION_OUTCOME = "decision_outcome"

private const val AUTOMOD_NOTIFICATION_TYPE = "notification_type"
private const val AUTOMOD_NOTIFICATION_JOIN_ATTEMPTS = "join_attempts"
private const val AUTOMOD_NOTIFICATION_RAID_DATETIME = "raid_datetime"
private const val AUTOMOD_NOTIFICATION_DMS_SENT = "dms_sent"
private const val AUTOMOD_NOTIFICATION_RAID_TYPE = "raid_type"
private const val AUTOMOD_NOTIFICATION_RESOLVED_REASON = "resolved_reason"
private const val AUTOMOD_NOTIFICATION_ACTION_BY_USER_ID = "action_by_user_id"
private const val AUTOMOD_NOTIFICATION_SUSPICIOUS_MENTION_UNTIL = "suspicious_mention_activity_until"

data class AutoModMessageContext(
    val content: String,
    val channelId: Long?,
    val ruleName: String?,
    val decisionId: String?,
    val keyword: String?,
    val keywordMatchedContent: String?,
    val flaggedMessageId: Long?,
    val timeoutDuration: String?,
    val quarantineType: String?,
    val quarantineAction: String?,
    val quarantineEvent: String?,
    val blockProfileUpdateType: String?,
    val decisionReason: String?,
    val alertActionsExecution: String?,
    val voiceChannelStatusOutcome: String?,
    val applicationName: String?,
    val interactionUserId: Long?,
    val interactionCallbackType: String?,
    val decisionOutcome: String?,
    val notification: AutoModNotificationContext?,
)

data class AutoModNotificationContext(
    val type: String,
    val joinAttempts: Int?,
    val raidDatetime: String?,
    val dmsSent: Int?,
    val raidType: String?,
    val resolvedReason: String?,
    val actionByUserId: Long?,
    val suspiciousMentionActivityUntil: String?,
)

fun MessageEmbed.extractAutoModContext(): AutoModMessageContext {
    val values = fields.orEmpty().associate { it.name to it.value }
    val notificationType = values[AUTOMOD_NOTIFICATION_TYPE]
    return AutoModMessageContext(
        content = description.orEmpty(),
        channelId = values[AUTOMOD_EMBED_FIELD_CHANNEL_ID]?.toLongOrNull(),
        ruleName = values[AUTOMOD_EMBED_FIELD_RULE_NAME],
        decisionId = values[AUTOMOD_EMBED_DECISION_ID],
        keyword = values[AUTOMOD_EMBED_KEYWORD],
        keywordMatchedContent = values[AUTOMOD_EMBED_KEYWORD_MATCHED_CONTENT],
        flaggedMessageId = values[AUTOMOD_EMBED_FLAGGED_MESSAGE_ID]?.toLongOrNull(),
        timeoutDuration = values[AUTOMOD_EMBED_TIMEOUT_DURATION],
        quarantineType = values[AUTOMOD_EMBED_QUARANTINE_USER],
        quarantineAction = values[AUTOMOD_EMBED_QUARANTINE_USER_ACTION],
        quarantineEvent = values[AUTOMOD_EMBED_QUARANTINE_EVENT],
        blockProfileUpdateType = values[AUTOMOD_EMBED_BLOCK_PROFILE_UPDATE_TYPE],
        decisionReason = values[AUTOMOD_EMBED_DECISION_REASON],
        alertActionsExecution = values[AUTOMOD_EMBED_ALERT_ACTIONS_EXECUTION],
        voiceChannelStatusOutcome = values[AUTOMOD_EMBED_VOICE_CHANNEL_STATUS_OUTCOME],
        applicationName = values[AUTOMOD_EMBED_APPLICATION_NAME],
        interactionUserId = values[AUTOMOD_EMBED_INTERACTION_USER_ID]?.toLongOrNull(),
        interactionCallbackType = values[AUTOMOD_EMBED_INTERACTION_CALLBACK_TYPE],
        decisionOutcome = values[AUTOMOD_EMBED_DECISION_OUTCOME],
        notification = notificationType?.let {
            AutoModNotificationContext(
                type = it,
                joinAttempts = values[AUTOMOD_NOTIFICATION_JOIN_ATTEMPTS]?.toIntOrNull(),
                raidDatetime = values[AUTOMOD_NOTIFICATION_RAID_DATETIME],
                dmsSent = values[AUTOMOD_NOTIFICATION_DMS_SENT]?.toIntOrNull(),
                raidType = values[AUTOMOD_NOTIFICATION_RAID_TYPE],
                resolvedReason = values[AUTOMOD_NOTIFICATION_RESOLVED_REASON],
                actionByUserId = values[AUTOMOD_NOTIFICATION_ACTION_BY_USER_ID]?.toLongOrNull(),
                suspiciousMentionActivityUntil = values[AUTOMOD_NOTIFICATION_SUSPICIOUS_MENTION_UNTIL],
            )
        },
    )
}
