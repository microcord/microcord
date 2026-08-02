package com.discord.widgets.chat.input.autocomplete

import com.discord.models.member.GuildMember
import com.discord.models.presence.Presence
import com.discord.models.user.User
import com.discord.utilities.user.UserUtils.discriminatorWithPadding
import com.discord.utilities.user.UserUtils.displayName

data class UserAutocompletable(
    val user: User,
    val guildMember: GuildMember? = null,
    val nickname: String? = null,
    val presence: Presence? = null,
    val canUserReadChannel: Boolean = true,
) : Autocompletable() {
    private val textMatchers = listOf(
        "${leadingIdentifier().identifier}${user.username}${user.discriminatorWithPadding}",
        "${leadingIdentifier().identifier}${user.displayName}",
    ).distinct()
    private val autoTextMatchers = listOfNotNull(
        nickname?.takeUnless(String::isBlank),
        user.globalName?.takeUnless(String::isBlank),
    ).distinct().map { "${leadingIdentifier().identifier}$it" }.takeIf(List<String>::isNotEmpty)

    override fun getInputReplacement() = "<${leadingIdentifier().identifier}${user.id}>"
    override fun getInputTextMatchers() = textMatchers
    override fun getAutocompleteTextMatchers() = autoTextMatchers
    override fun leadingIdentifier() = LeadingIdentifier.MENTION
}
