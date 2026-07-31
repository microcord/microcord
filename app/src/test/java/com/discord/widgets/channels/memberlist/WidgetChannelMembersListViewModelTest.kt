package com.discord.widgets.channels.memberlist

import com.discord.api.channel.Channel
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListAdapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import rx.android.plugins.RxAndroidPlugins
import rx.android.plugins.RxAndroidSchedulersHook
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject

class WidgetChannelMembersListViewModelTest {
    private val states = PublishSubject.create<WidgetChannelMembersList.State>()
    private val channelRanges = mutableListOf<Pair<Long, IntRange>>()
    private val threads = mutableListOf<Long>()
    private val subscriber = object : WidgetChannelMembersListViewModel.GuildChannelSubscriber {
        override fun subscribeToChannelRange(channel: Channel, range: IntRange) {
            channelRanges += channel.id to range
        }

        override fun subscribeToThread(channel: Channel) {
            threads += channel.id
        }
    }

    @Before fun setUpMainScheduler() {
        RxAndroidPlugins.getInstance().reset()
        RxAndroidPlugins.getInstance().registerSchedulersHook(object : RxAndroidSchedulersHook() {
            override fun getMainThreadScheduler() = Schedulers.immediate()
        })
    }

    @After fun resetMainScheduler() = RxAndroidPlugins.getInstance().reset()

    @Test fun openingChangedGuildListRequestsInitialRange() {
        WidgetChannelMembersListViewModel(states, subscriber)
        states.onNext(state(channel(10, Channel.GUILD_TEXT), "everyone", open = true))

        assertEquals(listOf(10L to (0 until 100)), channelRanges)
        assertEquals(emptyList<Long>(), threads)
    }

    @Test fun openingThreadSubscribesToThreadInsteadOfGuildRange() {
        WidgetChannelMembersListViewModel(states, subscriber)
        states.onNext(state(channel(20, Channel.PUBLIC_THREAD), "20", open = true))

        assertEquals(emptyList<Pair<Long, IntRange>>(), channelRanges)
        assertEquals(listOf(20L), threads)
    }

    private fun state(channel: Channel, listId: String, open: Boolean) = WidgetChannelMembersList.State(
        channel,
        open,
        object : WidgetChannelMembersListViewModel.MemberList {
            override val listId = listId
            override val size = 0
            override fun get(index: Int): ChannelMembersListAdapter.Item = throw IndexOutOfBoundsException()
            override fun getHeaderPositionForItem(itemPosition: Int): Int? = null
        },
    )

    private fun channel(id: Long, type: Int) = Channel(
        topic = null, type = type, guildId = 1, name = "channel", lastMessageId = 0, id = id,
        ownerId = 0, recipientIds = null, recipients = null, position = 0, permissionOverwrites = null,
        userLimit = 0, bitrate = 0, icon = null, originChannelId = 0, applicationId = 0, nicks = null,
        nsfw = false, parentId = 0, memberListId = null, rateLimitPerUser = 0,
        defaultAutoArchiveDuration = null, rtcRegion = null, flags = null, guildHashes = null,
        availableTags = null, appliedTags = null, threadMetadata = null, messageCount = null,
        memberCount = null, memberIdsPreview = null, member = null,
    )
}
