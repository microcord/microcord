package com.discord.widgets.chat.list.adapter

import androidx.fragment.app.findFragment
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.permission.Permission
import com.discord.app.AppFragment
import com.discord.models.domain.ModelInvite
import com.discord.models.guild.Guild
import com.discord.models.guild.UserGuildMember
import com.discord.stores.StoreStream
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.GuildScheduledEventInviteEntry
import com.discord.widgets.guildscheduledevent.GuildScheduledEventItemView
import com.discord.widgets.guildscheduledevent.WidgetGuildScheduledEventDetailsBottomSheet
import rx.Observable
import rx.Subscription
import java.lang.ref.WeakReference

/**
 * An invite that carries `?event=`: the whole row is the scheduled-event card.
 *
 * The card offers a different primary action depending on where the viewer stands. Already in the
 * guild, it opens the event details and lets them mark themselves interested; not in the guild, the
 * only thing on offer is the invite itself.
 */
class WidgetChatListAdapterItemGuildScheduledEventInvite(
    adapter: WidgetChatListAdapter,
) : WidgetChatListAdapterItemInviteBase(
    R.layout.widget_chat_list_adapter_item_guild_scheduled_event_invite,
    adapter,
) {

    private val card: GuildScheduledEventItemView =
        itemView.findViewById(R.id.guild_scheduled_event_invite_container)

    override var subscription: Subscription? = null

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as GuildScheduledEventInviteEntry
        observeModel(entry).ui().appSubscribe(
            WidgetChatListAdapterItemGuildScheduledEventInvite::class.java,
            subscriptionHandler = { subscription = it },
            onNext = ::configureUI,
        )
    }

    private fun observeModel(entry: GuildScheduledEventInviteEntry): Observable<Model> {
        val guilds = StoreStream.getGuilds()
        val users = StoreStream.getUsers()
        val channels = StoreStream.getChannels()
        val permissions = StoreStream.getPermissions()
        val scheduledEvents = StoreStream.getGuildScheduledEvents()
        val voiceChannelSelected = StoreStream.getVoiceChannelSelected()
        return ObservationDeckProvider.get()
            .connectRx(guilds, users, channels, permissions, scheduledEvents, voiceChannelSelected)
            .map { _ ->
                val event = entry.guildScheduledEvent
                // The invite's channel snapshot has no guild id of its own; the event's guild owns it.
                val inviteChannel = entry.invite.channel?.copy(guildId = entry.invite.guild?.id ?: 0L)
                val creatorId = event.creatorId
                Model(
                    invite = entry.invite,
                    guildScheduledEvent = event,
                    isInGuild = guilds.getMember(event.guildId, users.getMe().id) != null,
                    isRsvped = scheduledEvents.isMeRsvpedToEvent(event.guildId, event.id),
                    channel = inviteChannel?.let { channels.getChannel(it.id) },
                    guild = guilds.getGuild(event.guildId),
                    creator = creatorId?.let { id ->
                        users.getUsers()[id]?.let { UserGuildMember(it, guilds.getMember(event.guildId, id)) }
                    },
                    canConnect = inviteChannel != null && PermissionUtils.can(
                        Permission.CONNECT,
                        permissions.getPermissionsByChannel()[inviteChannel.id],
                    ),
                    isConnected = event.channelId == voiceChannelSelected.getSelectedVoiceChannelId(),
                )
            }
    }

    private fun configureUI(model: Model) {
        // The card supplies its own background inside a chat row, which already has one.
        card.background = null
        card.configureInChatList(
            guildScheduledEvent = model.guildScheduledEvent,
            channel = model.channel,
            guild = model.guild,
            creator = model.creator,
            isInGuild = model.isInGuild,
            isRsvped = model.isRsvped,
            canConnect = model.canConnect,
            isConnected = model.isConnected,
            onCardClicked = {
                if (model.isInGuild) {
                    WidgetGuildScheduledEventDetailsBottomSheet.showForGuild(
                        adapter.fragmentManager,
                        model.guildScheduledEvent.id,
                    )
                } else {
                    joinServerOrDM(javaClass, model.invite)
                }
            },
            rsvpButtonClickListener = {
                StoreStream.getGuildScheduledEvents().toggleMeRsvpForEvent(model.guildScheduledEvent)
            },
            joinServerButtonClickListener = {
                joinServerOrDM(javaClass, model.invite)
            },
            shareButtonClickListener = {
                adapter.onShareButtonClick(
                    model.guildScheduledEvent,
                    WeakReference(adapter.context),
                    WeakReference(hostFragment()),
                )
            },
        )
    }

    /** The fragment the list lives in; the share sheet needs it to present itself. */
    private fun hostFragment(): AppFragment? =
        runCatching { adapter.recycler.findFragment<AppFragment>() }.getOrNull()

    private data class Model(
        val invite: ModelInvite,
        val guildScheduledEvent: GuildScheduledEvent,
        val isInGuild: Boolean,
        val isRsvped: Boolean,
        val channel: Channel?,
        val guild: Guild?,
        val creator: UserGuildMember?,
        val canConnect: Boolean,
        val isConnected: Boolean,
    )
}
