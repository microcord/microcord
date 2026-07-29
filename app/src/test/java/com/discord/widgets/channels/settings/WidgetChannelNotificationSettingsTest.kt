package com.discord.widgets.channels.settings

import com.discord.api.channel.Channel
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.guild.Guild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetChannelNotificationSettingsTest {

    @Test
    fun modelResolvesChannelAndForumOverrides() {
        val parent = channel(id = 20, type = Channel.CATEGORY)
        val forum = channel(id = 30, type = Channel.GUILD_FORUM, parentId = parent.id)
        val settings = ModelNotificationSettings(
            messageNotifications = ModelNotificationSettings.FREQUENCY_NOTHING,
            guildId = 10,
            channelOverrides = listOf(
                ModelNotificationSettings.ChannelOverride(
                    messageNotifications = ModelNotificationSettings.FREQUENCY_ALL,
                    channelId = parent.id,
                ),
                ModelNotificationSettings.ChannelOverride(
                    messageNotifications = ModelNotificationSettings.FREQUENCY_MENTIONS,
                    flags = ModelNotificationSettings.FORUM_THREAD_CREATED_ON,
                    channelId = forum.id,
                ),
            ),
        )

        val model = WidgetChannelNotificationSettings.Model.create(
            forum,
            parent,
            Guild(id = 10, defaultMessageNotifications = ModelNotificationSettings.FREQUENCY_NOTHING),
            settings,
            joinedThread = null,
            approximateMemberCount = 2_501,
            canAccessRedesignedForumChannels = true,
        )

        assertEquals(ModelNotificationSettings.FREQUENCY_MENTIONS, model.notificationSetting)
        assertEquals(ModelNotificationSettings.FREQUENCY_ALL, model.parentNotificationSetting)
        assertEquals(ModelNotificationSettings.FORUM_THREAD_CREATED_ON, model.forumPostNotificationSetting)
        assertTrue(model.isAboveNotifyAllSize)
        assertTrue(model.canAccessRedesignedForumChannels)
        assertFalse(model.notificationSettingIsInherited)
    }

    private fun channel(id: Long, type: Int, parentId: Long = 0) = Channel(
        topic = null,
        type = type,
        guildId = 10,
        name = "channel-$id",
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
        parentId = parentId,
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
