package com.discord.widgets.chat.list.adapter

import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.stageinstance.StageInstance
import com.discord.models.domain.ModelInvite
import com.discord.stores.StoreStream
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.views.stages.StageCardSpeakersView
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.StageInviteEntry
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton
import rx.Observable
import rx.Subscription

/**
 * The card an invite gets when it points at a stage that is live right now.
 *
 * It is a different row from the ordinary invite card rather than a variant of it because it sells
 * the *conversation* — topic, who is speaking, how many are listening — instead of the server.
 */
class WidgetChatListAdapterItemStageInvite(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_stage_invite, adapter) {

    private val image: SimpleDraweeView = itemView.findViewById(R.id.item_invite_image)
    private val imageText: TextView = itemView.findViewById(R.id.item_invite_image_text)
    private val guildName: TextView = itemView.findViewById(R.id.item_invite_guild_name)
    private val channelTopic: TextView = itemView.findViewById(R.id.item_invite_channel_topic)
    private val listenersCount: TextView = itemView.findViewById(R.id.item_listeners_count)
    private val speakersView: StageCardSpeakersView =
        itemView.findViewById(R.id.stage_discovery_speakers_view)
    private val joinButton: MaterialButton = itemView.findViewById(R.id.item_invite_join_button)

    private var iconUrl: String? = null

    override var subscription: Subscription? = null

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as StageInviteEntry
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
        return ObservationDeckProvider.get()
            .connectRx(channels, stageInstances, accessibility)
            .map { _ ->
                // The invite carries a snapshot of the channel; the local copy is fresher when the
                // viewer is already in the guild.
                val channel = entry.invite.channel?.let { channels.getChannel(it.id) } ?: entry.invite.channel
                Model(
                    invite = entry.invite,
                    channel = channel,
                    stageInstance = channel?.let { stageInstances.getStageInstanceForChannel(it.id) },
                    shouldAnimateGuildIcon = !accessibility.isReducedMotionEnabled(),
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

        joinButton.setOnClickListener { view ->
            ChatListRowNavigation.joinInvite?.invoke(view, model.invite, INVITE_SOURCE)
        }
    }

    private fun updateIconUrlIfChanged(url: String?) {
        if (iconUrl == url) return
        IconUtils.setIcon(image, url, R.dimen.avatar_size_large)
        iconUrl = url
    }

    /**
     * [speakers] and [listenersCount] are always empty/null in this reconstruction: the reference
     * reads them from `StoreStageChannels` (per-user stage roles) and `StoreRequestedStageChannels`
     * (the discovery sample), neither of which exists yet, and `StoreVoiceStates` is not reachable
     * through `StoreStream`. The speaker strip hides itself rather than showing an empty row.
     */
    private data class Model(
        val invite: ModelInvite,
        val channel: Channel?,
        val stageInstance: StageInstance?,
        val shouldAnimateGuildIcon: Boolean,
        val speakers: List<StageCardSpeakersView.Speaker> = emptyList(),
        val listenersCount: Int? = null,
    )

    private companion object {
        private const val INVITE_SOURCE = "Invite Button Embed"
        private const val DEFAULT_GUILD_ICON = "asset://asset/images/default_icon_selected.jpg"
        private val POSSESSIVE = Regex("'s ")
    }
}
