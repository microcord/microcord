package com.discord.widgets.chat.list.adapter

import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.stageinstance.StageInstance
import com.discord.models.domain.ModelInvite
import com.discord.models.member.GuildMember
import com.discord.models.user.CoreUser
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.views.stages.StageCardSpeakersView
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.StageInviteEntry
import com.discord.views.VitoImageView
import com.google.android.material.button.MaterialButton
import rx.Observable
import rx.Subscription
import com.discord.utilities.user.UserUtils.getDisplayName

/**
 * The card an invite gets when it points at a stage that is live right now.
 *
 * It is a different row from the ordinary invite card rather than a variant of it because it sells
 * the *conversation* — topic, who is speaking, how many are listening — instead of the server.
 */
class WidgetChatListAdapterItemStageInvite(
    adapter: WidgetChatListAdapter,
) : WidgetChatListAdapterItemInviteBase(R.layout.widget_chat_list_adapter_item_stage_invite, adapter) {

    private val image: VitoImageView = itemView.findViewById(R.id.item_invite_image)
    private val imageText: TextView = itemView.findViewById(R.id.item_invite_image_text)
    private val guildName: TextView = itemView.findViewById(R.id.item_invite_guild_name)
    private val channelTopic: TextView = itemView.findViewById(R.id.item_invite_channel_topic)
    private val listenersCount: TextView = itemView.findViewById(R.id.item_listeners_count)
    private val speakersView: StageCardSpeakersView =
        itemView.findViewById(R.id.stage_discovery_speakers_view)
    private val joinButton: MaterialButton = itemView.findViewById(R.id.item_invite_join_button)

    private var iconUrl: String? = null
    private var inviteInstanceId: String? = null

    override var subscription: Subscription? = null

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as StageInviteEntry
        inviteInstanceId = inviteInstanceId(entry.messageId, entry.inviteCode)
        observeModel(entry).ui().appSubscribe(
            WidgetChatListAdapterItemStageInvite::class.java,
            subscriptionHandler = { subscription = it },
            onNext = ::configureUI,
        )
    }

    private fun observeModel(entry: StageInviteEntry): Observable<Model> {
        val channels = StoreStream.getChannels()
        val stageInstances = StoreStream.getStageInstances()
        val accessibility = StoreStream.getAccessibility()
        val users = StoreStream.getUsers()
        val guilds = StoreStream.getGuilds()
        val requestedStageChannels = StoreStream.getRequestedStageChannels()
        val stageChannels = StoreStream.getStageChannels()
        return ObservationDeckProvider.get()
            .connectRx(
                users,
                channels,
                guilds,
                accessibility,
                stageInstances,
                requestedStageChannels,
                stageChannels,
            )
            .map { _ ->
                // The invite carries a snapshot of the channel; the local copy is fresher when the
                // viewer is already in the guild.
                val channel = entry.invite.channel?.let { channels.getChannel(it.id) } ?: entry.invite.channel
                val channelId = channel?.id
                val requestedState = channelId?.let {
                    requestedStageChannels.getRequestedInstanceStatesByChannel()[it]
                }
                val channelRoles = channelId?.let(stageChannels::getChannelRoles)
                val localSpeakers = channelRoles?.mapNotNull { (userId, roles) ->
                    if (!roles.isSpeaker) return@mapNotNull null
                    val user = users.getUsers()[userId] ?: return@mapNotNull null
                    speaker(user, guilds.getMember(entry.guildId, userId))
                }
                val speakers = localSpeakers ?: requestedState?.stageInstance?.sampleSpeakerMembers
                    ?.map { member ->
                        val user = CoreUser(member.user)
                        speaker(user, GuildMember.from(member, member.guildId))
                    }
                    .orEmpty()
                val listenersCount = if (channelRoles != null && localSpeakers != null) {
                    channelRoles.size - speakers.size
                } else {
                    requestedState?.stageInstance?.let { it.participantCount - it.speakers.size }
                }
                Model(
                    invite = entry.invite,
                    meId = users.getMe().id,
                    authorUser = users.getUsers()[entry.userId],
                    channel = channel,
                    stageInstance = channelId?.let {
                        stageInstances.getStageInstanceForChannel(it)
                            ?: requestedStageChannels.getStageInstanceForChannel(it)
                    },
                    shouldAnimateGuildIcon = !accessibility.isReducedMotionEnabled(),
                    speakers = speakers,
                    listenersCount = listenersCount,
                )
            }
    }

    private fun configureUI(model: Model) {
        val guild = model.invite.guild

        updateIconUrlIfChanged(
            IconUtils.getForGuild(
                guild?.id,
                guild?.icon,
                DEFAULT_GUILD_ICON,
                model.shouldAnimateGuildIcon,
            ),
        )
        imageText.setTextAndVisibilityBy(
            guild?.name?.takeIf { guild.icon.isNullOrEmpty() }?.replace(POSSESSIVE, " "),
        )

        guildName.setTextAndVisibilityBy(guild?.name ?: model.channel?.name)
        // A live stage names itself through its instance topic; a stage that has not set one falls
        // back to the channel topic and finally to the channel's own name.
        channelTopic.setTextAndVisibilityBy(
            model.stageInstance?.topic ?: model.channel?.topic ?: model.channel?.name,
        )
        listenersCount.setTextAndVisibilityBy(model.listenersCount?.toString())
        speakersView.setSpeakers(model.speakers)
        speakersView.visibility = if (model.speakers.isEmpty()) View.GONE else View.VISIBLE

        joinButton.setOnClickListener {
            joinServerOrDM(javaClass, model.invite, inviteInstanceId = inviteInstanceId)
        }
    }

    private fun updateIconUrlIfChanged(url: String?) {
        if (iconUrl == url) return
        IconUtils.setIcon(image, url, R.dimen.avatar_size_large)
        iconUrl = url
    }

    private fun speaker(user: User, guildMember: GuildMember?) = StageCardSpeakersView.Speaker(
        user = user,
        guildMember = guildMember,
        displayName = user.getDisplayName(guildMember?.nick),
    )

    private data class Model(
        val invite: ModelInvite,
        val meId: Long,
        val authorUser: User?,
        val channel: Channel?,
        val shouldAnimateGuildIcon: Boolean,
        val stageInstance: StageInstance?,
        val speakers: List<StageCardSpeakersView.Speaker>,
        val listenersCount: Int?,
    )

    private companion object {
        private const val DEFAULT_GUILD_ICON = "asset://asset/images/default_icon_selected.jpg"
        private val POSSESSIVE = Regex("'s ")
    }
}
