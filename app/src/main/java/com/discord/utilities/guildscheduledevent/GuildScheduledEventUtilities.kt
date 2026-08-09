package com.discord.utilities.guildscheduledevent

import androidx.fragment.app.Fragment
import com.discord.api.channel.Channel
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.channel.isVoiceChannel
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionBit
import com.discord.app.AppToast
import com.discord.models.guild.UserGuildMember
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuilds
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.permissions.PermissionUtils
import com.discord.widgets.guildscheduledevent.GuildScheduledEventModel
import com.discord.widgets.guildscheduledevent.GuildScheduledEventUrlUtils
import com.discord.widgets.voice.model.CallModel
import rx.Observable

/**
 * The permission questions the scheduled-event surfaces ask, in one place.
 *
 * Three distinct capabilities, and they do not imply one another:
 *
 * - **create** — `MANAGE_EVENTS` on the guild allows an external event anywhere; a channel event
 *   additionally needs the channel-specific set below, so a moderator scoped to one stage can
 *   still create an event there without guild-wide `MANAGE_EVENTS`.
 * - **start / end** — the same channel-specific set as create, evaluated against the event's own
 *   channel. Stage channels demand full stage moderation (`MANAGE_CHANNELS | MUTE_MEMBERS |
 *   MOVE_MEMBERS`) because starting the event starts the stage; voice channels only demand
 *   `CONNECT`.
 * - **share** — `CREATE_INSTANT_INVITE`, plus the requirement that `@everyone` can actually see
 *   the channel and that the viewer is not still pending membership screening. Sharing a link
 *   into a channel nobody can enter is worse than not offering the button.
 */
class GuildScheduledEventUtilities {

    companion object {
        const val ANALYTICS_SOURCE: String = "Guild Events"
        const val QUERY_PARAM_NAME: String = "event"

        /**
         * `MANAGE_EVENTS | VIEW_CHANNEL` — the floor for any channel-scoped event action.
         * Spelled as a literal because `const val` initialisers cannot call `or`.
         */
        private const val MANAGE_EVENTS_IN_CHANNEL: PermissionBit = 8589935616L

        private fun getGuildScheduledEventCreatableChannelsForGuild(
            guildId: Long,
            channelsStore: StoreChannels,
            permissionsStore: StorePermissions,
        ): List<Channel> {
            val permissionsByChannel = permissionsStore.getPermissionsByChannel()
            return channelsStore.getChannelsForGuild(guildId).values.filter { channel ->
                canStartEventInChannel(channel, permissionsByChannel[channel.id])
            }
        }

        /**
         * Whether the "create event" affordance should be offered at all: either the viewer can
         * create an external event, or there is at least one channel they could host one in.
         */
        @JvmStatic
        @JvmOverloads
        fun canCreateAnyEvent(
            guildId: Long,
            channelsStore: StoreChannels = StoreStream.getChannels(),
            permissionsStore: StorePermissions = StoreStream.getPermissions(),
        ): Boolean {
            val guildPermissions = permissionsStore.getGuildPermissions()[guildId] ?: return false
            return PermissionUtils.can(Permission.CREATE_EVENTS, guildPermissions) ||
                PermissionUtils.can(Permission.MANAGE_EVENTS, guildPermissions) ||
                getGuildScheduledEventCreatableChannelsForGuild(guildId, channelsStore, permissionsStore).isNotEmpty()
        }

        @JvmStatic
        fun canCreateExternalEvent(guildPermissions: Long?): Boolean =
            PermissionUtils.can(Permission.CREATE_EVENTS, guildPermissions) ||
                PermissionUtils.can(Permission.MANAGE_EVENTS, guildPermissions)

        /**
         * Whether the viewer may hand out a link to this event.
         *
         * With no channel the event is external and only `CREATE_INSTANT_INVITE` on the guild
         * matters. With a channel the invite has a destination, so `@everyone` must be able to see
         * that destination and the viewer must have finished membership screening.
         */
        @JvmStatic
        @JvmOverloads
        fun canShareEvent(
            channelId: Long?,
            guildId: Long,
            channelsStore: StoreChannels = StoreStream.getChannels(),
            guildsStore: StoreGuilds = StoreStream.getGuilds(),
            usersStore: StoreUser = StoreStream.getUsers(),
            permissionsStore: StorePermissions = StoreStream.getPermissions(),
        ): Boolean {
            if (channelId == null) {
                return PermissionUtils.can(
                    Permission.CREATE_INSTANT_INVITE,
                    permissionsStore.getGuildPermissions()[guildId],
                )
            }
            val channel = channelsStore.getChannel(channelId) ?: return false
            if (!PermissionUtils.can(
                    Permission.CREATE_INSTANT_INVITE,
                    permissionsStore.getPermissionsByChannel()[channelId],
                )
            ) {
                return false
            }
            val roles = guildsStore.getRoles()[channel.guildId].orEmpty()
            if (!PermissionUtils.canEveryoneRole(Permission.VIEW_CHANNEL, channel, roles)) return false
            val member = guildsStore.getMember(channel.guildId, usersStore.getMe().id) ?: return false
            return !member.pending
        }

        /** Whether the viewer may start (or end) an event hosted in [channelId]. */
        @JvmStatic
        @JvmOverloads
        fun canStartEvent(
            guildId: Long,
            channelId: Long?,
            channelsStore: StoreChannels = StoreStream.getChannels(),
            permissionsStore: StorePermissions = StoreStream.getPermissions(),
        ): Boolean {
            if (channelId == null) {
                val guildPermissions = permissionsStore.getGuildPermissions()[guildId] ?: return false
                return PermissionUtils.can(Permission.MANAGE_EVENTS, guildPermissions)
            }
            val channel = channelsStore.getChannel(channelId) ?: return false
            return canStartEventInChannel(channel, permissionsStore.getPermissionsByChannel()[channelId])
        }

        /**
         * Channels that are neither voice nor stage can never host a startable event, so this
         * returns `false` for them even with `MANAGE_EVENTS`.
         */
        @JvmStatic
        fun canStartEventInChannel(channel: Channel, channelPermissions: Long?): Boolean {
            if (!PermissionUtils.can(MANAGE_EVENTS_IN_CHANNEL, channelPermissions)) return false
            return when {
                channel.isStageVoiceChannel() ->
                    PermissionUtils.can(Permission.START_STAGE_EVENT, channelPermissions)
                channel.isVoiceChannel() ->
                    PermissionUtils.can(Permission.START_VOICE_EVENT, channelPermissions)
                else -> false
            }
        }

        /**
         * The live event that leaving this call should offer to end, or `null`.
         *
         * Deliberately conservative: the prompt is only shown when *every other participant* also
         * lacks the permission to end it, so the last moderator to leave is the one asked. If
         * anyone still in the channel could end it themselves, this returns `null`.
         */
        @JvmStatic
        @JvmOverloads
        fun getGuildScheduledEventToEndForCall(
            callModel: CallModel,
            myPermissions: Long?,
            guildsStore: StoreGuilds = StoreStream.getGuilds(),
        ): GuildScheduledEvent? {
            val channel = callModel.channel
            val guild = guildsStore.getGuild(channel.guildId) ?: return null
            val liveEvent = callModel.guildScheduledEvents.firstOrNull {
                it.status == GuildScheduledEventStatus.ACTIVE
            } ?: return null
            if (!canStartEventInChannel(channel, myPermissions)) return null

            val roles = guildsStore.getRoles()[guild.id].orEmpty()
            for ((userId, participant) in callModel.participants) {
                if (participant.isMe) continue
                val participantPermissions = PermissionUtils.computeNonThreadPermissions(
                    userId,
                    guild.id,
                    guild.ownerId,
                    participant.guildMember,
                    roles,
                    channel.permissionOverwrites,
                )
                if (!canStartEventInChannel(channel, participantPermissions)) return null
            }
            return liveEvent
        }

        /** An external event (no channel) is visible to everyone by definition. */
        @JvmStatic
        fun isEventViewableByEveryone(channelId: Long?): Boolean {
            if (channelId == null) return true
            val channel = StoreStream.getChannels().getChannel(channelId) ?: return false
            val roles = StoreStream.getGuilds().getRoles()[channel.guildId].orEmpty()
            return PermissionUtils.canEveryoneRole(Permission.VIEW_CHANNEL, channel, roles)
        }

        /**
         * Shares the event: an invite when the viewer may create one, otherwise the public event
         * URL on the clipboard.
         *
         * **Degraded.** The reference's [canShare] branch opens the invite-share sheet through
         * `ChannelInviteLaunchUtils.inviteToChannel(fragment, channel, ANALYTICS_SOURCE, eventId)`.
         * Neither `ChannelInviteLaunchUtils` nor `WidgetGuildInviteShare` exists in this target
         * yet, so both branches currently copy the event link. Restore the invite path when that
         * screen lands; the branch is kept so only its body has to change.
         */
        @JvmStatic
        fun launchInvite(
            canShare: Boolean,
            fragment: Fragment,
            guildId: Long,
            channel: Channel?,
            guildEventId: Long,
        ) {
            val context = fragment.context ?: return
            @Suppress("UNUSED_EXPRESSION")
            canShare
            AppToast.copyToClipboard(
                context,
                GuildScheduledEventUrlUtils.getEventDetailsUrl(guildId, guildEventId),
            )
        }

        @JvmStatic
        @JvmOverloads
        fun observeCanCreateAnyEvent(
            guildId: Long,
            observationDeck: ObservationDeck = ObservationDeckProvider.get(),
            channelsStore: StoreChannels = StoreStream.getChannels(),
            permissionsStore: StorePermissions = StoreStream.getPermissions(),
        ): Observable<Boolean> = observationDeck.connectRx(channelsStore, permissionsStore) {
            canCreateAnyEvent(guildId, channelsStore, permissionsStore)
        }
    }
}

/**
 * Live events first, then everything else by start time.
 *
 * Nulls sort last, which only matters because `Comparator` is a Java interface and callers can hand
 * it one; the reference makes the same allowance.
 */
object GuildScheduledEventsComparator : Comparator<GuildScheduledEvent> {
    override fun compare(o1: GuildScheduledEvent?, o2: GuildScheduledEvent?): Int {
        val firstActive = o1?.status == GuildScheduledEventStatus.ACTIVE
        val secondActive = o2?.status == GuildScheduledEventStatus.ACTIVE
        if (firstActive && !secondActive) return -1
        if (!firstActive && secondActive) return 1
        return when {
            o1 != null && o2 != null -> o1.scheduledStartTime.compareTo(o2.scheduledStartTime)
            o1 == null -> 1
            else -> -1
        }
    }
}

/**
 * The event's creator as a user-plus-member pair, or `null` when the event has no creator or the
 * creator is not cached.
 */
fun GuildScheduledEvent.getCreatorUserGuildMember(
    guildsStore: StoreGuilds = StoreStream.getGuilds(),
    usersStore: StoreUser = StoreStream.getUsers(),
): UserGuildMember? {
    val creatorId = creatorId ?: return null
    val user = usersStore.getUsers(listOf(creatorId))[creatorId] ?: return null
    return UserGuildMember(user, guildsStore.getMember(guildId, creatorId))
}

/** [getCreatorUserGuildMember] for the editable form, used by the create/preview screens. */
fun GuildScheduledEventModel.getCreatorUserGuildMember(
    guildsStore: StoreGuilds = StoreStream.getGuilds(),
    usersStore: StoreUser = StoreStream.getUsers(),
): UserGuildMember? {
    val creatorId = creatorId ?: return null
    val user = usersStore.getUsers(listOf(creatorId))[creatorId] ?: return null
    return UserGuildMember(user, guildsStore.getMember(guildId, creatorId))
}
