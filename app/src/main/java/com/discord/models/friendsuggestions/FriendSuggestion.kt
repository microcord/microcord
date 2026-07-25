package com.discord.models.friendsuggestions

import com.discord.models.user.User

data class FriendSuggestion(val user: User, val publicName: String?)
