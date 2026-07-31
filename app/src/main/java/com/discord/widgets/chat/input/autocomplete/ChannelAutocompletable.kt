package com.discord.widgets.chat.input.autocomplete

import com.discord.api.channel.Channel

data class ChannelAutocompletable(val channel: Channel) : Autocompletable() {
    private val textMatchers = listOf("${leadingIdentifier().identifier}${channel.name}")

    override fun getInputReplacement() = "<#${channel.id}>"
    override fun getInputTextMatchers() = textMatchers
    override fun leadingIdentifier() = LeadingIdentifier.CHANNELS
}
