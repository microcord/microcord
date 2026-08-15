package com.discord.widgets.channels.list

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDMRecipient
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.isCategory
import com.discord.api.channel.isSpoilerChannel
import com.discord.api.channel.isThread
import com.discord.api.channel.isThreadOnlyChannel
import com.discord.api.channel.isAnnouncementChannel
import com.discord.api.channel.isGuildForumChannel
import com.discord.api.channel.isGuildTextChannel
import com.discord.api.channel.isGuildTextyChannel
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.channel.isMultiUserDM
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.channel.hasMessages
import com.discord.stores.SelectedChannelAnalyticsLocation
import com.discord.utilities.channel.ChannelSelector
import com.discord.api.channel.isTextChannel
import com.discord.api.channel.isThreaded
import com.discord.api.guild.GuildFeature
import com.discord.api.permission.Permission
import com.discord.app.AppBottomSheet
import com.discord.app.AppToast
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.restSubscribeOn
import com.discord.widgets.channels.WidgetCreateChannel
import com.discord.widgets.channels.GroupDmLeave
import com.discord.widgets.servers.settings.invites.WidgetServerSettingsInstantInvites
import com.discord.widgets.settings.WidgetMuteSettingsSheet
import com.discord.widgets.stage.StageChannelJoinHelper
import com.discord.widgets.stage.sheet.WidgetEndStageBottomSheet
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.facebook.drawee.view.SimpleDraweeView
import rx.Observable
import rx.subscriptions.CompositeSubscription

/**
 * The long-press sheet for a channel, category or DM in the channels sidebar.
 *
 * Every row is permission- and type-gated, so the sheet shows only the actions that actually
 * apply to the channel that was pressed; a channel with no applicable action still opens the
 * sheet with its header, matching the reference.
 */
class WidgetChannelsListItemChannelActions : AppBottomSheet() {

    override fun getContentViewResId(): Int = R.layout.widget_channels_list_item_actions

    private lateinit var icon: SimpleDraweeView
    private lateinit var title: TextView
    private lateinit var profile: TextView
    private lateinit var markAsRead: TextView
    private lateinit var optIn: TextView
    private lateinit var pinChannel: TextView
    private lateinit var mute: TextView
    private lateinit var threadBrowser: TextView
    private lateinit var channelSettings: TextView
    private lateinit var notifications: TextView
    private lateinit var invite: TextView
    private lateinit var openChat: TextView
    private lateinit var rehideSpoiler: TextView
    private lateinit var copyLink: TextView
    private lateinit var cloneChannel: TextView
    private lateinit var joinStage: TextView
    private lateinit var hideNames: TextView
    private lateinit var endStage: TextView
    private lateinit var closeChannel: TextView
    private lateinit var developerDivider: View
    private lateinit var copyId: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        icon = view.findViewById(R.id.channels_list_item_text_actions_icon)
        title = view.findViewById(R.id.channels_list_item_text_actions_title)
        profile = view.findViewById(R.id.dm_action_profile)
        openChat = view.findViewById(R.id.action_open_chat)
        markAsRead = view.findViewById(R.id.text_action_mark_as_read)
        optIn = view.findViewById(R.id.text_action_opt_in)
        pinChannel = view.findViewById(R.id.text_action_pin_channel)
        mute = view.findViewById(R.id.text_action_mute)
        threadBrowser = view.findViewById(R.id.text_action_thread_browser)
        channelSettings = view.findViewById(R.id.action_channel_settings)
        notifications = view.findViewById(R.id.action_channel_notifications)
        invite = view.findViewById(R.id.action_invite)
        rehideSpoiler = view.findViewById(R.id.action_rehide_spoiler)
        copyLink = view.findViewById(R.id.action_copy_link)
        cloneChannel = view.findViewById(R.id.action_clone_channel)
        joinStage = view.findViewById(R.id.action_join_stage)
        hideNames = view.findViewById(R.id.action_hide_names)
        endStage = view.findViewById(R.id.action_end_stage)
        closeChannel = view.findViewById(R.id.action_close_dm)
        developerDivider = view.findViewById(R.id.developer_divider)
        copyId = view.findViewById(R.id.action_copy_id)
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        super.bindSubscriptions(compositeSubscription)
        val channelId = arguments?.getLong(INTENT_EXTRA_CHANNEL_ID, INVALID_CHANNEL_ID)
            ?: INVALID_CHANNEL_ID
        Model.get(channelId)
            .ui(this)
            .appSubscribe(WidgetChannelsListItemChannelActions::class.java, onNext = ::configureUI)
    }

    /** The sheet describes one channel; if the user navigates away it has nothing left to show. */
    override fun onPause() {
        super.onPause()
        dismiss()
    }

    /** The reference calls the `FAVORITED` flag a pin in the UI, so this row does too. */
    private fun configurePinChannel(channel: Channel) {
        val settings = StoreStream.getUserGuildSettings().getGuildSettings()[channel.guildId]
        val pinned = settings?.getChannelOverride(channel.id)?.isFavorite == true
        val supported = !channel.isThread() && !channel.isCategory()

        pinChannel.visibility = if (supported) View.VISIBLE else View.GONE
        pinChannel.setText(
            // A DM is pinned within the DM list, so the reference drops "Channel to Top" there.
            when {
                channel.isPrivate() && pinned -> R.string.unpin
                channel.isPrivate() -> R.string.pin
                pinned -> R.string.unpin_channel
                else -> R.string.pin_channel_to_top
            },
        )
        pinChannel.setOnClickAndDismissListener {
            StoreStream.getUserGuildSettings().setChannelFavorite(requireContext(), channel, !pinned)
        }
    }

    /**
     * Adds or removes the channel from the viewer's list, but only while the guild has opt-in on;
     * otherwise every channel is already shown and the row would do nothing visible.
     */
    private fun configureOptIn(channel: Channel) {
        val settings = StoreStream.getUserGuildSettings().getGuildSettings()[channel.guildId]
        val guild = StoreStream.getGuilds().getGuild(channel.guildId)
        val enabled = settings?.isOptInChannelsEnabled == true &&
            guild?.features?.contains(GuildFeature.COMMUNITY) == true
        val parentOptedIn = channel.parentId != 0L &&
            settings?.getChannelOverride(channel.parentId)?.isOptedIn == true
        val optedIn = settings?.getChannelOverride(channel.id)?.isOptedIn == true

        optIn.visibility = if (enabled && !channel.isThread()) View.VISIBLE else View.GONE
        optIn.setText(
            when {
                parentOptedIn -> R.string.channel_opt_out_parent_category
                optedIn -> R.string.channel_opt_out
                else -> R.string.channel_opt_in
            },
        )
        optIn.setOnClickAndDismissListener {
            val target = if (parentOptedIn) {
                StoreStream.getChannels().getChannel(channel.parentId) ?: channel
            } else {
                channel
            }
            StoreStream.getUserGuildSettings()
                .setChannelOptedIn(requireContext(), target, !(optedIn || parentOptedIn))
        }
    }

    private fun configureUI(model: Model?) {
        if (model == null) {
            dismiss()
            return
        }
        val channel = model.channel

        if (model.guild != null) {
            IconUtils.setIcon(icon, model.guild)
        } else {
            IconUtils.setIcon(icon, channel)
        }
        title.text = channel.getDisplayNameOrDefault(requireContext())

        val dmRecipient = channel.getDMRecipient()
        profile.visibility = if (dmRecipient != null) View.VISIBLE else View.GONE
        profile.setOnClickListener {
            dmRecipient?.let { WidgetUserSheet.show(it.id, parentFragmentManager) }
        }

        // A group DM's owner manages its links instead; everyone else there has no invite row.
        val gdmInviteLinks = channel.isMultiUserDM() && model.isOwner
        invite.visibility = if (model.canCreateInstantInvite || gdmInviteLinks) View.VISIBLE else View.GONE
        invite.setText(if (gdmInviteLinks) R.string.invite_links else R.string.instant_invite)
        invite.setOnClickAndDismissListener {
            if (gdmInviteLinks) {
                WidgetServerSettingsInstantInvites.launchForChannel(requireContext(), 0L, channel.id)
            } else {
                ChannelListNavigation.shareGuildInvite(this, channel.guildId, channel.id)
            }
        }

        // Only things that hold messages, and categories (which mute their children), can be muted.
        mute.visibility =
            if (channel.isTextChannel() || channel.isCategory()) View.VISIBLE else View.GONE
        mute.setOnClickAndDismissListener {
            if (model.isMuted) {
                StoreStream.getUserGuildSettings()
                    .setChannelMuted(requireContext(), channel.id, muted = false)
            } else {
                // Muting offers durations, so it goes through the picker rather than toggling.
                WidgetMuteSettingsSheet.showForChannel(channel.id, parentFragmentManager)
            }
        }
        mute.setCompoundDrawablesWithIntrinsicBounds(model.muteIconResId(requireContext()), 0, 0, 0)
        mute.text = model.muteChannelText(requireContext(), channel)

        // Forums have their own browser surface, and an NSFW channel's threads are not previewed.
        threadBrowser.visibility = if (
            channel.isThreaded() && !channel.isThreadOnlyChannel() && !channel.nsfw && model.hasThreads
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        threadBrowser.setOnClickAndDismissListener {
            ChannelListNavigation.showThreadBrowser(this, channel.guildId, channel.id)
        }

        channelSettings.visibility = if (model.canManageChannel) View.VISIBLE else View.GONE
        channelSettings.setOnClickAndDismissListener {
            when {
                channel.isMultiUserDM() ->
                    ChannelListNavigation.showGroupDmSettings(this, channel.id)
                channel.isGuildVocalChannel() ->
                    ChannelListNavigation.showVoiceChannelSettings(this, channel.id)
                else -> ChannelListNavigation.showTextChannelSettings(this, channel.id)
            }
        }
        channelSettings.text = settingsText(channel, model.canManageChannel)

        // Per-channel notification overrides exist for guild text channels and categories, and for
        // stage channels only in a community guild.
        // Per-channel notification overrides exist for guild text channels and categories, and for
        // stage channels only in a community guild. A plain voice channel has no override.
        notifications.visibility = if (
            channel.isGuildTextChannel() ||
            channel.isCategory() ||
            (channel.isStageVoiceChannel() && model.guild?.hasFeature(GuildFeature.COMMUNITY) == true)
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        notifications.setOnClickListener {
            ChannelListNavigation.showChannelNotificationSettings(this, channel.id)
        }

        // A vocal channel's chat is reachable without joining the call, which is the only way in
        // for someone who wants to read it without connecting.
        openChat.visibility = if (channel.isGuildVocalChannel()) View.VISIBLE else View.GONE
        openChat.setOnClickAndDismissListener {
            ChannelSelector.getInstance().openVocalChannelChat(
                channel,
                SelectedChannelAnalyticsLocation.TEXT_IN_VOICE,
            )
        }

        markAsRead.visibility =
            if (channel.hasMessages() || channel.isCategory()) View.VISIBLE else View.GONE
        markAsRead.setOnClickAndDismissListener {
            StoreStream.getMessageAck().ack(channel.id, isLockedAck = false, clearLock = true)
        }

        configureOptIn(channel)
        configurePinChannel(channel)
        configureCopyLink(channel)
        configureCloneChannel(model)
        configureVoice(model)
        configureCloseChannel(channel)

        val spoilerAgree = StoreStream.getChannelSpoilerAgree()
        rehideSpoiler.visibility =
            if (channel.isSpoilerChannel() && spoilerAgree.didAgree(channel.id)) View.VISIBLE else View.GONE
        rehideSpoiler.setOnClickAndDismissListener { spoilerAgree.clearAgreement(channel.id) }

        developerDivider.visibility = if (model.isDeveloper) View.VISIBLE else View.GONE
        copyId.visibility = if (model.isDeveloper) View.VISIBLE else View.GONE
        copyId.setOnClickAndDismissListener {
            ChannelListNavigation.copyToClipboard(requireContext(), channel.id.toString())
        }
    }

    private fun settingsText(channel: Channel, canManageChannel: Boolean): String = getString(
        when {
            channel.isCategory() -> R.string.edit_category
            channel.isMultiUserDM() -> R.string.customize_group
            canManageChannel -> R.string.edit_channel
            else -> R.string.channel_settings
        },
    )

    private fun configureCopyLink(channel: Channel) {
        copyLink.visibility = if (channel.isGuildTextyChannel() || channel.isGuildVocalChannel()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        copyLink.setOnClickAndDismissListener {
            AppToast.copyToClipboard(
                requireContext(),
                "https://discord.com/channels/${channel.guildId}/${channel.id}",
                getString(R.string.link_copied),
            )
        }
    }

    /** A category clones into a new channel inside it; anything else clones its own type. */
    private fun configureCloneChannel(model: Model) {
        val channel = model.channel
        val supported = channel.isCategory() || channel.type == Channel.GUILD_TEXT ||
            channel.isAnnouncementChannel() || channel.isGuildForumChannel() ||
            channel.type == Channel.GUILD_VOICE
        cloneChannel.visibility = if (model.canManageChannel && supported) View.VISIBLE else View.GONE
        cloneChannel.setText(if (channel.isCategory()) R.string.create_channel else R.string.clone_channel)
        cloneChannel.setOnClickAndDismissListener {
            WidgetCreateChannel.show(
                requireContext(),
                channel.guildId,
                if (channel.isCategory()) Channel.GUILD_TEXT else channel.type,
                if (channel.isCategory()) channel.id else channel.parentId.takeIf { it != 0L },
            )
        }
    }

    private fun configureVoice(model: Model) {
        val channel = model.channel
        val isStage = channel.isStageVoiceChannel()

        joinStage.visibility = if (isStage) View.VISIBLE else View.GONE
        joinStage.setOnClickAndDismissListener {
            StageChannelJoinHelper.connectToStage(requireContext(), parentFragmentManager, channel.id)
        }

        hideNames.visibility = if (channel.type == Channel.GUILD_VOICE) View.VISIBLE else View.GONE
        hideNames.setText(
            if (model.isVoiceCollapsed) R.string.voice_channel_show_names else R.string.voice_channel_hide_names,
        )
        hideNames.setOnClickAndDismissListener {
            StoreStream.getCollapsedVoiceChannels().toggle(channel.id)
        }

        endStage.visibility = if (isStage && model.isStageLive && model.canModerateStage) {
            View.VISIBLE
        } else {
            View.GONE
        }
        endStage.setOnClickAndDismissListener {
            WidgetEndStageBottomSheet.show(parentFragmentManager, END_STAGE_REQUEST_KEY, channel.id)
        }
    }

    /**
     * Closing a one-to-one DM only hides it, so it goes through unconfirmed; leaving a group needs a
     * re-invite to undo and does not.
     */
    private fun configureCloseChannel(channel: Channel) {
        val isGroup = channel.isMultiUserDM()
        closeChannel.visibility = if (channel.isPrivate()) View.VISIBLE else View.GONE
        closeChannel.setText(if (isGroup) R.string.leave_group_dm else R.string.close_dm)
        closeChannel.setOnClickAndDismissListener {
            if (!isGroup) {
                closePrivateChannel(channel.id, silent = false)
                return@setOnClickAndDismissListener
            }
            GroupDmLeave.confirm(
                requireContext(),
                parentFragmentManager,
                channel.getDisplayNameOrDefault(requireContext()),
            ) { silent -> closePrivateChannel(channel.id, silent) }
        }
    }

    private fun closePrivateChannel(channelId: Long, silent: Boolean) {
        RestAPI.api.deleteChannel(channelId, silent)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetChannelsListItemChannelActions::class.java, context) { }
    }

    private data class Model(
        val channel: Channel,
        val guild: Guild?,
        val permissions: Long?,
        val isMuted: Boolean,
        val hasThreads: Boolean,
        val isVoiceCollapsed: Boolean,
        val isStageLive: Boolean,
    ) {
        val canModerateStage: Boolean =
            PermissionUtils.can(Permission.MODERATE_STAGE_CHANNEL, permissions)

        /** Developer mode adds the "Copy ID" row and its divider. */
        val isDeveloper: Boolean = StoreStream.getUserSettings().getIsDeveloperMode()

        /** A group DM has no permission bits, but its owner can always rename it. */
        val canManageChannel: Boolean =
            PermissionUtils.can(Permission.MANAGE_CHANNELS, permissions) || channel.isMultiUserDM()

        val canCreateInstantInvite: Boolean =
            PermissionUtils.can(Permission.CREATE_INSTANT_INVITE, permissions)

        val isOwner: Boolean = channel.ownerId == StoreStream.getUsers().getMe().id

        fun muteChannelText(context: Context, channel: Channel): CharSequence = context.getString(
            when {
                isMuted && channel.isCategory() -> R.string.unmute_category
                channel.isCategory() -> R.string.mute_category
                isMuted -> R.string.unmute_channel_generic
                else -> R.string.mute_channel_generic
            },
        )

        @DrawableRes
        fun muteIconResId(context: Context): Int = context.getThemedDrawableRes(
            if (isMuted) R.attr.ic_channel_muted else R.attr.ic_channel_mute,
        )

        companion object {
            fun get(channelId: Long): Observable<Model?> = StoreStream.getChannels()
                .observeChannel(channelId)
                .switchMap { channel ->
                    val source: Observable<Model?> = if (channel == null) {
                        Observable.just(null)
                    } else {
                        Observable.combineLatest(
                            StoreStream.getGuilds().observeFromChannelId(channelId),
                            StoreStream.getPermissions().observePermissionsForChannel(channelId),
                            StoreStream.getUserGuildSettings().observeGuildSettings(),
                            StoreStream.getThreadsActive()
                                .observeChannelsWithActiveThreadsByGuild(channel.guildId),
                            StoreStream.getCollapsedVoiceChannels().observeCollapsedVoiceChannels(),
                            StoreStream.getStageInstances().observeStageInstanceForChannel(channelId),
                        ) { guild, permissions, guildSettings, threadedChannels, collapsedVoice, stage ->
                            val isMuted = guildSettings[channel.guildId]
                                ?.getChannelOverride(channel.id)
                                ?.isMuted == true
                            Model(
                                channel,
                                guild,
                                permissions,
                                isMuted,
                                hasThreads = channel.id in threadedChannels,
                                isVoiceCollapsed = channel.id in collapsedVoice,
                                isStageLive = stage != null,
                            )
                        }
                    }
                    source
                }
                .computationLatest()
                .distinctUntilChanged()
        }
    }

    companion object {
        private const val INTENT_EXTRA_CHANNEL_ID = "INTENT_EXTRA_CHANNEL_ID"
        private const val INVALID_CHANNEL_ID = -1L
        private const val END_STAGE_REQUEST_KEY = "CHANNEL_ACTIONS_END_STAGE"


        @JvmStatic
        fun show(fragmentManager: FragmentManager, channelId: Long) {
            WidgetChannelsListItemChannelActions()
                .apply {
                    arguments = Bundle().apply { putLong(INTENT_EXTRA_CHANNEL_ID, channelId) }
                }
                .show(fragmentManager, WidgetChannelsListItemChannelActions::class.java.name)
        }
    }
}
