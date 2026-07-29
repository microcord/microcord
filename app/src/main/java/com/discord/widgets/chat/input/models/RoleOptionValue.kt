package com.discord.widgets.chat.input.models

import com.discord.api.role.GuildRole

class RoleOptionValue(role: GuildRole) : CommandOptionValue(role.id.toString())
