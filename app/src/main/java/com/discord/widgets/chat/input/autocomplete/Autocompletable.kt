package com.discord.widgets.chat.input.autocomplete

import com.discord.api.channel.Channel
import com.discord.api.commands.CommandChoice
import com.discord.api.role.GuildRole
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.models.domain.emoji.Emoji
import com.discord.models.member.GuildMember
import com.discord.models.presence.Presence
import com.discord.models.user.User
import com.discord.utilities.collections.MultiListIterator
import com.discord.utilities.user.UserUtils.discriminatorWithPadding

/**
 * One row the autocomplete popup can offer, and the rules for matching it against typed text.
 *
 * Three distinct string sets exist per candidate, and conflating them is the usual source of bugs:
 *
 * * [getInputTextMatchers] — the canonical typed forms, e.g. `@name#0001`, `#general`, `:smile:`.
 *   These are matched both while typing (autocomplete) and when scanning finished input for
 *   mentions to pillify.
 * * [getAutocompleteTextMatchers] — extra forms offered *only while typing*, e.g. a guild
 *   nickname. They must not pillify finished text, because two members can share a nickname.
 * * [getHiddenInputTextMatchers] — extra forms recognised *only in finished text*, e.g. a Unicode
 *   emoji that does not require colons. They must not appear as autocomplete matches, or typing
 *   a bare `a` would offer every emoji whose name contains one.
 *
 * [iterateAutocompleteMatchers] and [iterateTextMatchers] are the two combinations, iterated
 * lazily via [MultiListIterator] because the user list can be tens of thousands of entries.
 */
sealed class Autocompletable {

    /** What replaces the token in the composer when this row is picked. */
    abstract fun getInputReplacement(): String

    /** Canonical typed forms. Never empty. */
    abstract fun getInputTextMatchers(): List<String>

    open fun getAutocompleteTextMatchers(): List<String>? = null

    open fun getHiddenInputTextMatchers(): List<String>? = null

    open fun leadingIdentifier(): LeadingIdentifier = LeadingIdentifier.NONE

    fun iterateAutocompleteMatchers(): Iterator<String> {
        val extra = getAutocompleteTextMatchers()
        return if (extra.isNullOrEmpty()) getInputTextMatchers().iterator()
        else MultiListIterator(getInputTextMatchers(), extra)
    }

    fun iterateTextMatchers(): Iterator<String> {
        val extra = getHiddenInputTextMatchers()
        return if (extra.isNullOrEmpty()) getInputTextMatchers().iterator()
        else MultiListIterator(getInputTextMatchers(), extra)
    }

    fun matchesText(text: String): Boolean =
        getInputTextMatchers().contains(text) || getHiddenInputTextMatchers()?.contains(text) == true
}

/**
 * Ranking bucket. Rows of different kinds never interleave: users first, then roles, channels,
 * emoji, commands, and finally the non-selectable placeholders.
 *
 * Reference: `AutocompletableKt.getSortIndex`.
 */
fun Autocompletable.getSortIndex(): Int = when (this) {
    is UserAutocompletable -> 0
    is RoleAutocompletable -> 1
    is GlobalRoleAutocompletable -> 2
    is ChannelAutocompletable -> 3
    is EmojiAutocompletable -> 4
    is ApplicationCommandAutocompletable -> 5
    is ApplicationCommandChoiceAutocompletable -> 6
    is ApplicationCommandLoadingPlaceholder -> 7
    is ApplicationPlaceholder -> 8
    is EmojiUpsellPlaceholder -> 9
}

data class UserAutocompletable(
    val user: User,
    val guildMember: GuildMember? = null,
    val nickname: String? = null,
    val presence: Presence? = null,
    /**
     * Whether the user can actually read the channel being typed in. `false` rows are still
     * offered — mentioning someone who cannot see the channel is legal and prompts an invite —
     * but the message-context filter drops them.
     */
    val canUserReadChannel: Boolean = true,
) : Autocompletable() {

    private val textMatchers: List<String> =
        listOf("${leadingIdentifier().identifier}${user.username}${user.discriminatorWithPadding}")

    private val autoTextMatchers: List<String>? =
        nickname?.let { listOf("${leadingIdentifier().identifier}$it") }

    override fun getInputReplacement(): String = "<${leadingIdentifier().identifier}${user.id}>"

    override fun getInputTextMatchers(): List<String> = textMatchers

    override fun getAutocompleteTextMatchers(): List<String>? = autoTextMatchers

    override fun leadingIdentifier(): LeadingIdentifier = LeadingIdentifier.MENTION
}

data class ChannelAutocompletable(val channel: Channel) : Autocompletable() {

    private val textMatchers: List<String> =
        listOf("${leadingIdentifier().identifier}${channel.name}")

    override fun getInputReplacement(): String = "<#${channel.id}>"

    override fun getInputTextMatchers(): List<String> = textMatchers

    override fun leadingIdentifier(): LeadingIdentifier = LeadingIdentifier.CHANNELS
}

data class EmojiAutocompletable(val emoji: Emoji, val animationsEnabled: Boolean) : Autocompletable() {

    private val textMatchers: List<String> = emoji.names.map { ":$it:" }

    override fun getInputReplacement(): String = emoji.getMessageContentReplacement()

    override fun getInputTextMatchers(): List<String> = textMatchers

    override fun getAutocompleteTextMatchers(): List<String>? = null

    /**
     * Unicode emoji are additionally recognised by their bare name in finished text, because the
     * message parser writes them back without colons.
     */
    override fun getHiddenInputTextMatchers(): List<String>? =
        if (!emoji.requiresColons()) emoji.names else null

    override fun leadingIdentifier(): LeadingIdentifier =
        if (emoji.requiresColons()) LeadingIdentifier.EMOJI_AND_STICKERS else LeadingIdentifier.NONE
}

data class RoleAutocompletable(val role: GuildRole, val canMention: Boolean) : Autocompletable() {

    private val textMatchers: List<String> = listOf("${leadingIdentifier().identifier}${role.name}")

    override fun getInputReplacement(): String = "<${leadingIdentifier().identifier}&${role.id}>"

    override fun getInputTextMatchers(): List<String> = textMatchers

    override fun leadingIdentifier(): LeadingIdentifier = LeadingIdentifier.MENTION
}

/** `@everyone` / `@here`, which are not backed by a [GuildRole]. */
data class GlobalRoleAutocompletable(val text: String) : Autocompletable() {

    private val textMatchers: List<String> = listOf("${leadingIdentifier().identifier}$text")

    override fun getInputReplacement(): String = "${leadingIdentifier().identifier}$text"

    override fun getInputTextMatchers(): List<String> = textMatchers

    override fun leadingIdentifier(): LeadingIdentifier = LeadingIdentifier.MENTION

    companion object {
        val everyone: GlobalRoleAutocompletable by lazy { GlobalRoleAutocompletable("everyone") }
        val here: GlobalRoleAutocompletable by lazy { GlobalRoleAutocompletable("here") }
    }
}

data class ApplicationCommandAutocompletable(
    val application: Application?,
    val command: ApplicationCommand,
    val hasPermissionToUse: Boolean,
    val showAvatar: Boolean,
) : Autocompletable() {

    private val textMatchers: List<String> =
        listOf("${leadingIdentifier().identifier}${command.name}")

    override fun getInputReplacement(): String = "${leadingIdentifier().identifier}${command.name}"

    override fun getInputTextMatchers(): List<String> = textMatchers

    override fun leadingIdentifier(): LeadingIdentifier = LeadingIdentifier.APP_COMMAND
}

/** A value offered for a command option that declares `choices`. */
data class ApplicationCommandChoiceAutocompletable(
    val choice: CommandChoice,
    val optionName: String? = null,
) : Autocompletable() {

    private val textMatchers: List<String> = listOf(choice.name)

    override fun getInputReplacement(): String = choice.value

    override fun getInputTextMatchers(): List<String> = textMatchers
}

/** Sticky section header above an application's commands. Not selectable. */
data class ApplicationPlaceholder(val application: Application) : Autocompletable() {
    override fun getInputReplacement(): String = ""
    override fun getInputTextMatchers(): List<String> = emptyList()
}

/** Skeleton row shown while an application's commands are still being fetched. */
data class ApplicationCommandLoadingPlaceholder(val application: Application? = null) : Autocompletable() {
    override fun getInputReplacement(): String = ""
    override fun getInputTextMatchers(): List<String> = emptyList()
}

/**
 * "N more emoji with Nitro" upsell row. Deliberately not a `data class` in the reference — two
 * upsells with equal contents are still distinct rows.
 */
class EmojiUpsellPlaceholder(
    val lockedTotal: Int,
    val lockedFirstThree: List<Emoji>,
    val allowEmojiToAnimate: Boolean,
) : Autocompletable() {

    override fun getInputReplacement(): String = ""
    override fun getInputTextMatchers(): List<String> = emptyList()

    companion object {
        const val EMOJI_PREVIEW_COUNT = 3
    }
}
