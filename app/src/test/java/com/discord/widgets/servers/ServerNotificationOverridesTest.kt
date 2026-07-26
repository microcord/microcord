package com.discord.widgets.servers

import com.discord.api.channel.Channel
import com.discord.models.domain.ModelMuteConfig
import com.discord.models.domain.ModelNotificationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pure pieces behind the server-notification screens: which channels appear in the override
 * list, and which channels the override selector offers.
 */
class ServerNotificationOverridesTest {

    private val category = channel(id = 100, type = Channel.CATEGORY, name = "general", position = 0)
    private val text = channel(id = 101, type = Channel.GUILD_TEXT, name = "chat", parentId = 100)
    private val voice = channel(id = 102, type = Channel.GUILD_VOICE, name = "lounge", parentId = 100)
    private val hidden = channel(id = 103, type = Channel.GUILD_TEXT, name = "secret", parentId = 100)

    private val channels = listOf(category, text, voice, hidden).associateBy { it.id }

    /** VIEW_CHANNEL for everything except [hidden]. */
    private val permissions = channels.keys.associateWith { id ->
        if (id == hidden.id) 0L else VIEW_CHANNEL
    }

    @Test
    fun `only channels with a meaningful override are listed`() {
        val settings = guildSettings(
            override(text.id, messageNotifications = ModelNotificationSettings.FREQUENCY_NOTHING),
            override(category.id, muted = true),
            // Neither muted nor an explicit frequency: nothing to show, nothing to delete.
            override(voice.id),
        )

        val items = WidgetServerNotificationsViewModel.buildOverrides(settings, channels, permissions)

        assertEquals(listOf(category.id, text.id), items.map { it.channel.id })
    }

    @Test
    fun `a channel the user cannot see is not listed even when it has an override`() {
        val settings = guildSettings(
            override(text.id, muted = true),
            override(hidden.id, muted = true),
        )

        val items = WidgetServerNotificationsViewModel.buildOverrides(settings, channels, permissions)

        assertEquals(listOf(text.id), items.map { it.channel.id })
    }

    @Test
    fun `a channel override carries its category so the row can name it`() {
        val settings = guildSettings(override(text.id, muted = true))

        val items = WidgetServerNotificationsViewModel.buildOverrides(settings, channels, permissions)

        assertEquals(category.id, items.single().parent?.id)
    }

    @Test
    fun `no overrides means no work`() {
        val items = WidgetServerNotificationsViewModel.buildOverrides(
            guildSettings(),
            channels,
            permissions,
        )
        assertTrue(items.isEmpty())
    }

    @Test
    fun `the override selector offers categories and text channels, filtered by name`() {
        val storeState = WidgetServerNotificationsOverrideSelectorViewModel.StoreState(
            guildChannels = channels,
            channelPermissions = permissions,
        )

        val all = WidgetServerNotificationsOverrideSelectorViewModel.buildItems(storeState, "")
        assertEquals(
            listOf(SettingsChannelListAdapter.TYPE_CATEGORY, SettingsChannelListAdapter.TYPE_CHANNEL),
            all.map { it.type },
        )
        assertEquals(listOf("100", "101"), all.map { it.key })

        val filtered = WidgetServerNotificationsOverrideSelectorViewModel.buildItems(storeState, "CHA")
        assertEquals(listOf("101"), filtered.map { it.key })
    }

    @Test
    fun `a category whose only visible entry is itself contributes nothing`() {
        val loneCategory = channel(id = 200, type = Channel.CATEGORY, name = "empty", position = 1)
        val onlyChannels = mapOf(loneCategory.id to loneCategory)

        val sorted = GuildChannelsSort.sortedVisibleChannels(
            onlyChannels,
            mapOf(loneCategory.id to VIEW_CHANNEL),
        )

        assertTrue(sorted.isEmpty())
    }

    private fun guildSettings(vararg overrides: ModelNotificationSettings.ChannelOverride) =
        ModelNotificationSettings(guildId = 1, channelOverrides = overrides.toList())

    private fun override(
        channelId: Long,
        muted: Boolean = false,
        messageNotifications: Int = ModelNotificationSettings.FREQUENCY_UNSET,
    ) = ModelNotificationSettings.ChannelOverride(
        muted = muted,
        muteConfig = if (muted) ModelMuteConfig(endTimeMs = null, endTime = null) else null,
        derivedMute = muted,
        messageNotifications = messageNotifications,
        channelId = channelId,
    )

    private fun channel(
        id: Long,
        type: Int,
        name: String,
        position: Int = 0,
        parentId: Long = 0,
    ) = Channel(
        topic = null, type = type, guildId = 1, name = name, lastMessageId = 0, id = id,
        ownerId = 0, recipientIds = null, recipients = null, position = position,
        permissionOverwrites = null, userLimit = 0, bitrate = 0, icon = null,
        originChannelId = 0, applicationId = 0, nicks = null, nsfw = false,
        parentId = parentId, memberListId = null, rateLimitPerUser = 0,
        defaultAutoArchiveDuration = null, rtcRegion = null, flags = null, guildHashes = null,
        availableTags = null, appliedTags = null, threadMetadata = null, messageCount = null,
        memberCount = null, memberIdsPreview = null, member = null,
    )

    private companion object {
        /** `Permission.VIEW_CHANNEL`, spelled out so the test does not depend on bit ordering. */
        const val VIEW_CHANNEL = 1024L
    }
}
