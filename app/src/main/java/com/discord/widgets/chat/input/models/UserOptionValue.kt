package com.discord.widgets.chat.input.models

import com.discord.models.user.User

class UserOptionValue(user: User) : CommandOptionValue(user.id.toString())
