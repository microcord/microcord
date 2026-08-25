package com.discord.widgets.chat.input.autocomplete

import com.discord.api.channel.Channel
import com.discord.api.emoji.GuildEmoji
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.user.CoreUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TreeSet

class AutocompleteTextTest {

    private fun channelAutocompletable(id: Long, name: String) = ChannelAutocompletable(
        Channel(
            topic = null,
            type = Channel.GUILD_TEXT,
            guildId = 1L,
            name = name,
            lastMessageId = 0,
            id = id,
            ownerId = 0,
            recipientIds = null,
            recipients = null,
            position = 0,
            permissionOverwrites = null,
            userLimit = 0,
            bitrate = 0,
            icon = null,
            originChannelId = 0,
            applicationId = 0,
            nicks = null,
            nsfw = false,
            parentId = 0,
            memberListId = null,
            rateLimitPerUser = 0,
            defaultAutoArchiveDuration = null,
            rtcRegion = null,
            flags = null,
            guildHashes = null,
            availableTags = null,
            appliedTags = null,
            threadMetadata = null,
            messageCount = null,
            memberCount = null,
            memberIdsPreview = null,
            member = null,
        ),
    )

    @Test
    fun tokenIsOnlyProducedForAKnownLeadingIdentifier() {
        val at = AutocompleteModelUtils.getMessageAutocompleteToken("hello @ali", 10..10)
        assertEquals(LeadingIdentifier.MENTION, at?.leadingIdentifier)
        assertEquals("@ali", at?.token)
        assertEquals("ali", at?.formattedToken)
        assertEquals(6, at?.startIndex)

        assertNull(AutocompleteModelUtils.getMessageAutocompleteToken("hello ali", 9..9))
        // A caret sitting after a completed word offers nothing.
        assertNull(AutocompleteModelUtils.getMessageAutocompleteToken("hello @ali ", 11..11))
    }

    @Test
    fun aSlashCommandIsMatchedFromTheStartOfTheInputNotFromTheCaretsWord() {
        val token = AutocompleteModelUtils.getMessageAutocompleteToken("/ban someone", 12..12)
        assertEquals(LeadingIdentifier.APP_COMMAND, token?.leadingIdentifier)
        assertEquals(0, token?.startIndex)
        assertEquals(true, token?.isAtStart)
    }

    @Test
    fun mentionsAreRewrittenToTheirServerFormInAscendingOrder() {
        val input = "see #general and #random"
        val map = mapOf(
            IntRange(4, 12) to channelAutocompletable(1L, "general"),
            IntRange(17, 24) to channelAutocompletable(2L, "random"),
        )

        assertEquals("see <#1> and <#2>", input.replaceAutocompleteDataWithServerValues(map))
    }

    @Test
    fun composerPillsResolveUserChannelAndRoleWireTokens() {
        val users = mapOf(1L to "user")
        val channels = mapOf(2L to "general")
        val roles = mapOf(3L to "mods")
        fun display(token: String) = InputAutocomplete.mentionDisplay(
            token,
            users::get,
            channels::get,
            roles::get,
        )

        assertEquals("@user", display("<@1>"))
        assertEquals("@user", display("<@!1>"))
        assertEquals("#general", display("<#2>"))
        assertEquals("@mods", display("<@&3>"))
        assertNull(display("<#99>"))
    }

    @Test
    fun anOutOfBoundsRangeIsSkippedRatherThanThrowing() {
        val input = "hi"
        val map = mapOf(IntRange(50, 60) to channelAutocompletable(1L, "general"))

        assertEquals("hi", input.replaceAutocompleteDataWithServerValues(map))
    }

    @Test
    fun flattenPreservesBucketOrder() {
        val flattened = mapOf(
            LeadingIdentifier.CHANNELS to listOf<Autocompletable>(channelAutocompletable(1L, "a")),
            LeadingIdentifier.MENTION to listOf<Autocompletable>(GlobalRoleAutocompletable.everyone),
        ).flatten()

        assertEquals(2, flattened.size)
        assertEquals("<#1>", flattened[0].getInputReplacement())
        assertEquals("@everyone", flattened[1].getInputReplacement())
    }

    @Test
    fun customEmojiStaysReadableUntilSendRewritesIt() {
        val emoji = ModelEmojiCustom(
            GuildEmoji(42L, "party", emptyList(), true, false, false, true),
            guildId = 7L,
        )
        val candidate = EmojiAutocompletable(emoji, animationsEnabled = true)

        assertEquals(":party:", candidate.getInputReplacement())
        assertEquals(
            "hello <:party:42>",
            "hello :party:".replaceAutocompleteDataWithServerValues(mapOf(IntRange(6, 13) to candidate)),
        )
    }

    @Test
    fun userMentionsMatchUsernameGlobalNameAndNickname() {
        val user = UserAutocompletable(
            user = CoreUser(7, "handle", globalName = "Display"),
            nickname = "Guild Nick",
        )

        assertEquals(listOf("@handle", "@Display"), user.getInputTextMatchers())
        assertEquals(listOf("@Guild Nick", "@Display"), user.getAutocompleteTextMatchers())
    }

    @Test
    fun exactAutocompleteUtilitiesKeepReferenceOrderingAndMergeSemantics() {
        assertEquals(2, GlobalRoleAutocompletable.everyone.getSortIndex())

        val target = mutableMapOf(
            LeadingIdentifier.MENTION to TreeSet<Autocompletable>(AutocompletableComparator()).apply {
                add(GlobalRoleAutocompletable.everyone)
            },
        )
        target.merge(
            mapOf(
                LeadingIdentifier.MENTION to TreeSet<Autocompletable>(AutocompletableComparator()).apply {
                    add(GlobalRoleAutocompletable.here)
                },
            ),
        )

        assertEquals(listOf("@everyone", "@here"), target.getValue(LeadingIdentifier.MENTION).map {
            it.getInputReplacement()
        })
    }
}
