package com.discord.widgets.voice.fullscreen

import com.discord.api.activity.ActivityType
import com.discord.api.channel.Channel
import com.discord.api.guild.GuildMaxVideoChannelUsers
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.api.user.NsfwAllowance
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.embeddedactivities.EmbeddedActivity
import com.discord.rtcconnection.mediaengine.MediaEngineConnection
import com.discord.stores.StoreAudioManagerV2
import com.discord.stores.StoreMediaSettings
import com.discord.utilities.voice.VoiceChannelJoinability
import com.discord.widgets.voice.model.CallModel
import com.discord.widgets.voice.sheet.CallParticipantsAdapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.android.plugins.RxAndroidPlugins
import rx.android.plugins.RxAndroidSchedulersHook
import rx.observers.TestSubscriber
import rx.plugins.RxJavaHooks
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject

class WidgetCallPreviewFullscreenViewModelTest {
    private val states = PublishSubject.create<WidgetCallPreviewFullscreenViewModel.StoreState>()
    private val selectedVoiceChannels = mutableListOf<Long>()
    private val muted = mutableListOf<Boolean>()
    private val fetchedApplications = mutableListOf<Long>()
    private val selectedTextChannels = mutableListOf<Channel?>()
    private val previousGuilds = mutableListOf<Long>()
    private var navigationCloses = 0
    private var hasParticipantRows = false
    private lateinit var viewModel: WidgetCallPreviewFullscreenViewModel
    private lateinit var events: TestSubscriber<WidgetCallPreviewFullscreenViewModel.Event>

    @Before
    fun setUp() {
        RxJavaHooks.setOnComputationScheduler { Schedulers.immediate() }
        RxAndroidPlugins.getInstance().reset()
        RxAndroidPlugins.getInstance().registerSchedulersHook(
            object : RxAndroidSchedulersHook() {
                override fun getMainThreadScheduler() = Schedulers.immediate()
            },
        )
        viewModel = WidgetCallPreviewFullscreenViewModel(
            voiceChannelId = VOICE_CHANNEL_ID,
            storeStateObservable = states,
            isMobileOverlayEnabled = { false },
            selectVoiceChannel = selectedVoiceChannels::add,
            setSelfMuted = muted::add,
            fetchApplication = fetchedApplications::add,
            refreshStreams = { _, _ -> hasParticipantRows },
            selectTextChannel = selectedTextChannels::add,
            closeNavigation = { navigationCloses++ },
            selectPreviousChannel = previousGuilds::add,
        )
        events = TestSubscriber()
        viewModel.observeEvents().subscribe(events)
    }

    @After
    fun tearDown() {
        RxJavaHooks.reset()
        RxAndroidPlugins.getInstance().reset()
    }

    @Test
    fun validStateBuildsExactEmptyPresentationAndFetchesEmbeddedApplication() {
        states.onNext(validState(VoiceChannelJoinability.PERMISSIONS_MISSING))

        val state = requireNotNull(viewModel.viewState)
        assertEquals("Voice", state.titleText)
        assertTrue(state.isTextInVoiceChannelSelected)
        assertEquals(0, state.otherChannelsMentionsCount)
        assertEquals(5, state.channelMentionsCount)
        assertFalse(state.isConnectEnabled)
        assertTrue(state.isNsfwUnconsented)
        assertTrue(state.isChannelNsfw)
        assertEquals(NsfwAllowance.DISALLOWED, state.nsfwAllowed)
        assertTrue(state.participantsList is WidgetCallPreviewFullscreenViewModel.ParticipantsList.Empty)
        val items = (state.participantsList as WidgetCallPreviewFullscreenViewModel.ParticipantsList.Empty).items
        assertTrue(items.single() is CallParticipantsAdapter.ListItem.Event)
        assertEquals(listOf(APPLICATION_ID), fetchedApplications)
        events.assertNoValues()
    }

    @Test
    fun alreadySelectedVoiceChannelLaunchesBeforePublishingOrFetching() {
        states.onNext(validState(selectedVoiceChannel = channel(VOICE_CHANNEL_ID)))

        assertNull(viewModel.viewState)
        assertTrue(fetchedApplications.isEmpty())
        events.assertValue(
            WidgetCallPreviewFullscreenViewModel.Event.LaunchVideoCall(VOICE_CHANNEL_ID, null),
        )
    }

    @Test
    fun participantRowsSelectTheListPresentation() {
        hasParticipantRows = true
        states.onNext(validState())

        assertTrue(
            viewModel.requireViewState().participantsList is
                WidgetCallPreviewFullscreenViewModel.ParticipantsList.ListItems,
        )
    }

    @Test
    fun connectAndStreamTransitionsPreserveCapacityAndSideEffectOrdering() {
        states.onNext(validState())
        viewModel.tryConnectToVoice(mute = true)
        viewModel.onStreamPreviewClicked("guild:7:88:9")

        assertEquals(listOf(true), muted)
        assertEquals(listOf(VOICE_CHANNEL_ID), selectedVoiceChannels)
        events.assertValues(
            WidgetCallPreviewFullscreenViewModel.Event.ShowOverlayNux,
            WidgetCallPreviewFullscreenViewModel.Event.LaunchVideoCall(VOICE_CHANNEL_ID, null),
            WidgetCallPreviewFullscreenViewModel.Event.LaunchVideoCall(88L, "guild:7:88:9"),
        )

        states.onNext(validState(VoiceChannelJoinability.GUILD_VIDEO_AT_CAPACITY))
        viewModel.tryConnectToVoice()
        viewModel.onStreamPreviewClicked("guild:7:88:9")

        assertEquals(listOf(true), muted)
        assertEquals(listOf(VOICE_CHANNEL_ID), selectedVoiceChannels)
        assertEquals(
            listOf(
                WidgetCallPreviewFullscreenViewModel.Event.ShowOverlayNux,
                WidgetCallPreviewFullscreenViewModel.Event.LaunchVideoCall(VOICE_CHANNEL_ID, null),
                WidgetCallPreviewFullscreenViewModel.Event.LaunchVideoCall(88L, "guild:7:88:9"),
                WidgetCallPreviewFullscreenViewModel.Event.ShowGuildVideoAtCapacityDialog,
                WidgetCallPreviewFullscreenViewModel.Event.ShowGuildVideoAtCapacityDialog,
            ),
            events.onNextEvents,
        )
    }

    @Test
    fun textInVoiceActionsUsePublishedChannel() {
        states.onNext(validState())

        viewModel.onTextInVoiceTapped()
        viewModel.selectTextChannelAfterFinish()

        assertEquals(listOf(channel()), selectedTextChannels)
        assertEquals(1, navigationCloses)
        assertEquals(listOf(GUILD_ID), previousGuilds)
    }

    private fun validState(
        joinability: VoiceChannelJoinability = VoiceChannelJoinability.CAN_JOIN,
        selectedVoiceChannel: Channel? = null,
    ) = WidgetCallPreviewFullscreenViewModel.StoreState.Valid(
        voiceChannel = channel(),
        selectedTextChannel = channel(77L),
        totalMentionsCount = 2,
        channelMentionsCount = 5,
        isNsfwUnconsented = true,
        isChannelNsfw = true,
        nsfwAllowed = NsfwAllowance.DISALLOWED,
        callModel = callModel(joinability),
        selectedVoiceChannel = selectedVoiceChannel,
    )

    private fun callModel(joinability: VoiceChannelJoinability) = CallModel(
        participants = emptyMap(),
        myId = 1L,
        timeConnectedMs = 0L,
        videoDevices = emptyList(),
        selectedVideoDevice = null,
        voiceSettings = StoreMediaSettings.VoiceConfiguration(
            inputMode = MediaEngineConnection.InputMode.VOICE_ACTIVITY,
        ),
        rtcConnectionMetadata = null,
        callFeedbackTriggerRateDenominator = 0,
        streamFeedbackTriggerRateDenominator = 0,
        activeStream = null,
        voiceChannelJoinability = joinability,
        guildMaxVideoChannelMembers = GuildMaxVideoChannelUsers.Unlimited,
        guild = null,
        isChannelSelected = false,
        isMyHandRaised = false,
        guildScheduledEvents = listOf(scheduledEvent()),
        embeddedActivitiesForChannel = mapOf(APPLICATION_ID to embeddedActivity()),
        applications = emptyMap(),
        channel = channel(),
        numUsersConnected = 0,
        channelPermissions = null,
        audioManagerState = StoreAudioManagerV2.DEFAULT_STATE,
    )

    private fun scheduledEvent() = GuildScheduledEvent(
        id = 10L,
        guildId = GUILD_ID,
        channelId = VOICE_CHANNEL_ID,
        creatorId = null,
        name = "Event",
        description = null,
        image = null,
        scheduledStartTime = UtcDateTime(0L),
        scheduledEndTime = null,
        privacyLevel = StageInstancePrivacyLevel.GUILD_ONLY,
        status = GuildScheduledEventStatus.ACTIVE,
        entityType = GuildScheduledEventEntityType.VOICE,
        entityId = null,
        entityMetadata = null,
        skuIds = emptyList(),
        userCount = null,
        userRsvp = null,
        guild = null,
    )

    private fun embeddedActivity() = EmbeddedActivity(
        applicationId = APPLICATION_ID,
        assets = null,
        createdAt = null,
        details = null,
        name = "Activity",
        secrets = null,
        state = null,
        timestamps = null,
        type = ActivityType.PLAYING,
        userIds = emptyList(),
        url = "",
        guildId = GUILD_ID,
    )

    private fun channel(id: Long = VOICE_CHANNEL_ID) = Channel(
        topic = null,
        type = Channel.GUILD_VOICE,
        guildId = GUILD_ID,
        name = "Voice",
        lastMessageId = 0L,
        id = id,
        ownerId = 0L,
        recipientIds = null,
        recipients = null,
        position = 0,
        permissionOverwrites = null,
        userLimit = 0,
        bitrate = 0,
        icon = null,
        originChannelId = 0L,
        applicationId = 0L,
        nicks = null,
        nsfw = true,
        parentId = 0L,
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

    private companion object {
        const val GUILD_ID = 7L
        const val VOICE_CHANNEL_ID = 42L
        const val APPLICATION_ID = 99L
    }
}
