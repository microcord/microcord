package com.discord.widgets.botuikit.views.select

import java.io.Serializable

/**
 * Everything the interaction endpoint needs to identify *which* component on *which* message was
 * used.
 *
 * [Serializable] because it travels to [SelectComponentBottomSheet] inside a fragment argument
 * `Bundle` and has to survive process death alongside the sheet.
 *
 * [messageFlags] is carried rather than re-read because an ephemeral response must be answered as
 * ephemeral, and by the time the user confirms a selection the message may already have been
 * replaced in the store.
 */
data class ComponentContext(
    val guildId: Long?,
    val messageId: Long,
    val channelId: Long,
    val messageFlags: Long?,
    val applicationId: Long,
) : Serializable
