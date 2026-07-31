@file:JvmName("WidgetChatListItemKt")

package com.discord.widgets.chat.list.adapter

import com.discord.models.message.Message

fun WidgetChatListAdapter.isUserMentioned(message: Message): Boolean {
    if (!mentionMeMessageLevelHighlighting) return false
    if (message.mentionEveryone == true) return true
    if (message.mentions.orEmpty().any { it.id == data.userId }) return true
    return message.mentionRoles.orEmpty().any { data.myRoleIds.contains(it) }
}
