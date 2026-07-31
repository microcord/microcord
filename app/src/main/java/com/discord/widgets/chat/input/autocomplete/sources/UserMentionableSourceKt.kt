@file:JvmName("UserMentionableSourceKt")

package com.discord.widgets.chat.input.autocomplete.sources

import com.discord.api.channel.Channel

internal fun Channel.isDmOrGroupDm(): Boolean = type == 1 || type == 3

internal fun Channel.isTextOrVoiceChannel(): Boolean = type == 0 || type == 2 || type == 5 ||
    type == 11 || type == 12
