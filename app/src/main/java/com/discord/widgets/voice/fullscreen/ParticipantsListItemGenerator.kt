package com.discord.widgets.voice.fullscreen

import com.discord.api.application.Application
import com.discord.api.channel.Channel
import com.discord.api.channel.isPrivate
import com.discord.models.commands.Application as CommandApplication
import com.discord.models.embeddedactivities.EmbeddedActivity
import com.discord.models.guild.UserGuildMember
import com.discord.stores.StoreApplicationStreamPreviews
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.guildscheduledevent.GuildScheduledEventsComparator
import com.discord.utilities.user.UserUtils
import com.discord.widgets.guildscheduledevent.GuildScheduledEventTiming
import com.discord.widgets.guildscheduledevent.ScheduledEventUi
import com.discord.widgets.voice.model.CallModel
import com.discord.widgets.voice.sheet.CallParticipantsAdapter

/**
 * Turns the raw call state into the ordered row list that both the voice bottom sheet and the
 * fullscreen call render.
 *
 * The two surfaces show the same rows in the same order, so the ordering rules live here rather
 * than in either ViewModel.
 */
object ParticipantsListItemGenerator {

    /**
     * Builds the row list: at most one event card, then the embedded-activity cards, then the
     * ordered participants, and finally the invite affordance.
     *
     * @param mySpectatingStreamKey the stream I am currently watching, which pulls its streamer and
     *   its other viewers to the top of the list.
     */
    fun createConnectedListItems(
        voiceParticipants: Map<Long, StoreVoiceParticipants.VoiceUser>,
        mySpectatingStreamKey: String?,
        channel: Channel,
        callModel: CallModel,
        embeddedActivities: List<EmbeddedActivity>,
        applications: Map<Long, Application>,
    ): List<CallParticipantsAdapter.ListItem> {
        val listItems = mutableListOf<CallParticipantsAdapter.ListItem>()

        // Only the soonest relevant event gets a card; the rest live in the events list.
        callModel.guildScheduledEvents
            .filter {
                ScheduledEventUi.timing(it).let { timing ->
                    timing == GuildScheduledEventTiming.LIVE || timing.isStartable()
                }
            }
            .minWithOrNull(GuildScheduledEventsComparator)
            ?.let { event ->
                listItems += CallParticipantsAdapter.ListItem.Event(
                    event = event,
                    canStartEvent = callModel.canManageEvent(),
                    isCurrentUserInCall = callModel.isConnected,
                )
            }

        addEmbeddedActivitiesToListItems(listItems, voiceParticipants, embeddedActivities, applications)

        // In a DM the other party is listed even before they pick up, so the caller can see who is
        // being rung; in a guild channel only people actually connected are shown.
        val participants = voiceParticipants.values.filter { it.isConnected || channel.isPrivate() }
        if (participants.isNotEmpty()) {
            listItems += participants
                .sortedWith(createUserItemsComparator(mySpectatingStreamKey))
                .map { participant ->
                    CallParticipantsAdapter.ListItem.VoiceUser(
                        participant = participant,
                        canRing = channel.isPrivate() && !participant.isConnected,
                        isSpectatingSameStream = mySpectatingStreamKey != null &&
                            mySpectatingStreamKey == participant.watchingStream,
                    )
                }
            if (callModel.canInvite()) {
                listItems += CallParticipantsAdapter.ListItem.Invite
            }
        }
        return listItems
    }

    /**
     * Requests a preview image for every stream in [listItems] that has not been fetched yet, and
     * reports whether the list contains any participant rows at all.
     *
     * The return value is what decides between the participant list and the "nobody is here yet"
     * illustration — it is not a "did anything get fetched" flag.
     */
    fun refreshStreams(
        listItems: List<CallParticipantsAdapter.ListItem>,
        fetchedPreviews: MutableSet<String>,
        storeApplicationStreamPreviews: StoreApplicationStreamPreviews,
    ): Boolean {
        var hasParticipantRows = false
        listItems.forEach { listItem ->
            if (listItem !is CallParticipantsAdapter.ListItem.VoiceUser) return@forEach
            hasParticipantRows = true
            val streamContext = listItem.participant.streamContext ?: return@forEach
            val streamKey = streamContext.stream?.encodedStreamKey ?: return@forEach
            if (fetchedPreviews.add(streamKey)) {
                storeApplicationStreamPreviews.fetchStreamPreviewIfNotFetching(streamContext)
            }
        }
        return hasParticipantRows
    }

    private fun addEmbeddedActivitiesToListItems(
        listItems: MutableList<CallParticipantsAdapter.ListItem>,
        voiceParticipants: Map<Long, StoreVoiceParticipants.VoiceUser>,
        embeddedActivities: List<EmbeddedActivity>,
        applications: Map<Long, Application>,
    ) {
        embeddedActivities.forEach { embeddedActivity ->
            // An activity whose application has not been fetched yet has nothing to render.
            val application = applications[embeddedActivity.applicationId] ?: return@forEach
            listItems += CallParticipantsAdapter.ListItem.EmbeddedActivityItem(
                embeddedActivity = embeddedActivity,
                participants = embeddedActivity.userIds.mapNotNull { userId ->
                    voiceParticipants[userId]?.let { UserGuildMember(it.user, it.guildMember) }
                },
                application = CommandApplication.fromApiApplication(application),
            )
        }
    }

    /**
     * Participant order, most relevant first: the person whose stream you are watching, then
     * yourself, then the other viewers of that stream, then anyone else streaming, then anyone with
     * their camera on, and finally by display name.
     */
    private fun createUserItemsComparator(
        mySpectatingStreamKey: String? = null,
    ): Comparator<StoreVoiceParticipants.VoiceUser> = Comparator { user1, user2 ->
        val isSpectating = mySpectatingStreamKey != null

        fun rank(user: StoreVoiceParticipants.VoiceUser): Int = when {
            isSpectating && user.applicationStream?.encodedStreamKey == mySpectatingStreamKey -> 0
            user.isMe -> 1
            isSpectating && user.watchingStream == mySpectatingStreamKey -> 2
            user.applicationStream?.encodedStreamKey != null -> 3
            user.voiceState?.selfVideo == true -> 4
            else -> 5
        }

        val rank1 = rank(user1)
        val rank2 = rank(user2)
        if (rank1 != rank2) {
            rank1 - rank2
        } else {
            UserUtils.compareUserNames(user1.user, user2.user, user1.nickname, user2.nickname)
        }
    }
}
