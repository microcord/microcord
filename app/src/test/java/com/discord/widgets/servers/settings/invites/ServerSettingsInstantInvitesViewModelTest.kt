package com.discord.widgets.servers.settings.invites

import com.discord.api.channel.Channel
import com.discord.models.domain.ModelInvite
import com.discord.models.guild.Guild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSettingsInstantInvitesViewModelTest {
    @Test
    fun `missing guild closes the screen and null invites remain loading`() {
        assertSame(
            ServerSettingsInstantInvitesViewModel.ViewState.Unavailable,
            ServerSettingsInstantInvitesViewModel.toViewState(
                ServerSettingsInstantInvitesViewModel.StoreState(null, emptyMap()),
                emptyList(),
            ),
        )

        val loading = ServerSettingsInstantInvitesViewModel.toViewState(
            ServerSettingsInstantInvitesViewModel.StoreState(Guild(id = 1, name = "Guild"), emptyMap()),
            null,
        ) as ServerSettingsInstantInvitesViewModel.ViewState.Loaded

        assertTrue(loading.isLoading)
        assertTrue(loading.invites.isEmpty())
    }

    @Test
    fun `invites are sorted by channel name`() {
        val beta = ModelInvite.createForTesting(channel(2, "beta"))
        val alpha = ModelInvite.createForTesting(channel(1, "Alpha"))

        val loaded = ServerSettingsInstantInvitesViewModel.toViewState(
            ServerSettingsInstantInvitesViewModel.StoreState(Guild(id = 7, name = "Guild"), emptyMap()),
            listOf(beta, alpha),
        ) as ServerSettingsInstantInvitesViewModel.ViewState.Loaded

        assertFalse(loaded.isLoading)
        assertEquals(listOf("Alpha", "beta"), loaded.invites.map { it.invite.channel?.name })
        assertTrue(loaded.invites.all { it.guildId == 7L })
    }

    private fun channel(id: Long, name: String) = Channel(
        topic = null,
        type = Channel.GUILD_TEXT,
        guildId = 7,
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
    )
}
