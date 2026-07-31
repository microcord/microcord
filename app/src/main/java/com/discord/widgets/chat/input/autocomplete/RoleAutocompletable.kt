package com.discord.widgets.chat.input.autocomplete

import com.discord.api.role.GuildRole

data class RoleAutocompletable(val role: GuildRole, val canMention: Boolean) : Autocompletable() {
    private val textMatchers = listOf("${leadingIdentifier().identifier}${role.name}")

    override fun getInputReplacement() = "<${leadingIdentifier().identifier}&${role.id}>"
    override fun getInputTextMatchers() = textMatchers
    override fun leadingIdentifier() = LeadingIdentifier.MENTION
}
