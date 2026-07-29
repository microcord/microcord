package com.discord.widgets.chat.input.models

import com.discord.api.channel.Channel

class ChannelOptionValue(val channel: Channel) : CommandOptionValue(channel.id.toString())
