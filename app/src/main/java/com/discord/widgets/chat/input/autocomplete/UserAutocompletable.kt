package com.discord.widgets.chat.input.autocomplete

import com.discord.models.member.GuildMember
import com.discord.models.presence.Presence
import com.discord.models.user.User
import com.discord.utilities.user.UserUtils.discriminatorWithPadding

data class UserAutocompletable(
    val user: User,
    val guildMember: GuildMember? = null,
    val nickname: String? = null,
    val presence: Presence? = null,
    val canUserReadChannel: Boolean = true,
) : Autocompletable() {
    private val textMatchers =
        listOf("${leadingIdentifier().identifier}${user.username}${user.discriminatorWithPadding}")
    private val autoTextMatchers = nickname?.let { listOf("${leadingIdentifier().identifier}$it") }

    override fun getInputReplacement() = "<${leadingIdentifier().identifier}${user.id}>"
    override fun getInputTextMatchers() = textMatchers
    override fun getAutocompleteTextMatchers() = autoTextMatchers
    override fun leadingIdentifier() = LeadingIdentifier.MENTION
}
