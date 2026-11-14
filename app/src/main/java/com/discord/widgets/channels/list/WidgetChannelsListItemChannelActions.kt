package com.discord.widgets.channels.list

import com.discord.utilities.rest.ClientBackend
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.core.view.children
import androidx.core.view.isVisible
import com.discord.databinding.WidgetChannelsListItemActionsBinding
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.resources.getI18nPluralString
import com.discord.widgets.chat.list.actions.MessageMenuGroup
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
import rx.Observable
import rx.subscriptions.CompositeSubscription
import com.discord.plugins.PluginContextMenuHost
import dev.mantikafasi.manticord.plugins.api.PluginContextMenuSurface
import dev.mantikafasi.manticord.plugins.api.pluginContextMenuContext

/**
 * The long-press sheet for a channel, category or DM in the channels sidebar.
 *
 * Every row is permission- and type-gated, so the sheet shows only the actions that actually
 * apply to the channel that was pressed; a channel with no applicable action still opens the
 * sheet with its header, matching the reference.
 */
class WidgetChannelsListItemChannelActions : AppBottomSheet() {

    /** The modern sheet is the default; the legacy one shares the long-press menu flag. Both share view ids. */
    private val modern by lazy { !FeatureFlags.legacyUiEnabled() }

    override fun getContentViewResId(): Int =
        if (modern) R.layout.widget_channels_list_item_actions_modern else R.layout.widget_channels_list_item_actions

    private var _binding: WidgetChannelsListItemActionsBinding? = null
    private val binding get() = checkNotNull(_binding)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = WidgetChannelsListItemActionsBinding.bind(view)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
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

        binding.textActionPinChannel.visibility = if (supported) View.VISIBLE else View.GONE
        binding.textActionPinChannel.setText(
            // A DM is pinned within the DM list, so the reference drops "Channel to Top" there.
            when {
                channel.isPrivate() && pinned -> R.string.unpin
                channel.isPrivate() -> R.string.pin
                pinned -> R.string.unpin_channel
                else -> R.string.pin_channel_to_top
            },
        )
        binding.textActionPinChannel.setOnClickAndDismissListener {
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

        binding.textActionOptIn.visibility = if (enabled && !channel.isThread()) View.VISIBLE else View.GONE
        binding.textActionOptIn.setText(
            when {
                parentOptedIn -> R.string.channel_opt_out_parent_category
                optedIn -> R.string.channel_opt_out
                else -> R.string.channel_opt_in
            },
        )
        binding.textActionOptIn.setOnClickAndDismissListener {
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
        PluginContextMenuHost.bind(
            binding.pluginContextMenuItems,
            pluginContextMenuContext(
                PluginContextMenuSurface.CHANNEL,
                channel.guildId.takeIf { it > 0L },
                channel.id,
                userId = channel.getDMRecipient()?.id,
                targetId = channel.id,
            ),
            ::dismiss,
            modern,
        )
        // An empty container would otherwise keep its card on screen with nothing in it.
        binding.pluginContextMenuItems.isVisible = binding.pluginContextMenuItems.childCount > 0

        if (model.guild != null) {
            IconUtils.setIcon(binding.channelsListItemTextActionsIcon, model.guild)
        } else {
            IconUtils.setIcon(binding.channelsListItemTextActionsIcon, channel)
        }
        binding.channelsListItemTextActionsTitle.text = channel.getDisplayNameOrDefault(requireContext())
        // Like the modern client, a group names its size under the title; the legacy header has no subtitle.
        val members = channel.recipients?.size?.plus(1)
        val subtitle = binding.channelsListItemTextActionsSubtitle
        subtitle.isVisible = modern && channel.isMultiUserDM() && members != null
        if (members != null) {
            subtitle.text = requireContext().getI18nPluralString(
                R.plurals.private_channel_access_info_members_count,
                members,
                "count" to members,
            )
        }

        val dmRecipient = channel.getDMRecipient()
        binding.dmActionProfile.visibility = if (dmRecipient != null) View.VISIBLE else View.GONE
        binding.dmActionProfile.setOnClickListener {
            dmRecipient?.let { WidgetUserSheet.show(it.id, parentFragmentManager) }
        }

        // A group DM's owner manages its links instead; everyone else there has no invite row.
        val gdmInviteLinks = channel.isMultiUserDM() && model.isOwner
        binding.actionInvite.visibility = if (model.canCreateInstantInvite || gdmInviteLinks) View.VISIBLE else View.GONE
        binding.actionInvite.setText(if (gdmInviteLinks) R.string.invite_links else R.string.instant_invite)
        binding.actionInvite.setOnClickAndDismissListener {
            if (gdmInviteLinks) {
                WidgetServerSettingsInstantInvites.launchForChannel(requireContext(), 0L, channel.id)
            } else {
                ChannelListNavigation.shareGuildInvite(this, channel.guildId, channel.id)
            }
        }

        // Only things that hold messages, and categories (which mute their children), can be muted.
        binding.textActionMute.visibility =
            if (channel.isTextChannel() || channel.isCategory()) View.VISIBLE else View.GONE
        binding.textActionMute.setOnClickAndDismissListener {
            if (model.isMuted) {
                StoreStream.getUserGuildSettings()
                    .setChannelMuted(requireContext(), channel.id, muted = false)
            } else {
                // Muting offers durations, so it goes through the picker rather than toggling.
                WidgetMuteSettingsSheet.showForChannel(channel.id, parentFragmentManager)
            }
        }
        // The themed icons carry a 60% alpha that tinting keeps, so the modern rows use opaque copies.
        val muteIcon = when {
            !modern -> model.muteIconResId(requireContext())
            model.isMuted -> R.drawable.ic_menu_muted
            else -> R.drawable.ic_menu_mute
        }
        binding.textActionMute.setCompoundDrawablesWithIntrinsicBounds(muteIcon, 0, 0, 0)
        binding.textActionMute.text = model.muteChannelText(requireContext(), channel)

        // Forums have their own browser surface, and an NSFW channel's threads are not previewed.
        binding.textActionThreadBrowser.visibility = if (
            channel.isThreaded() && !channel.isThreadOnlyChannel() && !channel.nsfw && model.hasThreads
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.textActionThreadBrowser.setOnClickAndDismissListener {
            ChannelListNavigation.showThreadBrowser(this, channel.guildId, channel.id)
        }

        binding.actionChannelSettings.visibility = if (model.canManageChannel) View.VISIBLE else View.GONE
        binding.actionChannelSettings.setOnClickAndDismissListener {
            when {
                channel.isMultiUserDM() ->
                    ChannelListNavigation.showGroupDmSettings(this, channel.id)
                channel.isGuildVocalChannel() ->
                    ChannelListNavigation.showVoiceChannelSettings(this, channel.id)
                else -> ChannelListNavigation.showTextChannelSettings(this, channel.id)
            }
        }
        binding.actionChannelSettings.text = settingsText(channel, model.canManageChannel)

        // Per-channel notification overrides exist for guild text channels and categories, and for
        // stage channels only in a community guild.
        // Per-channel notification overrides exist for guild text channels and categories, and for
        // stage channels only in a community guild. A plain voice channel has no override.
        binding.actionChannelNotifications.visibility = if (
            channel.isGuildTextChannel() ||
            channel.isCategory() ||
            (channel.isStageVoiceChannel() && model.guild?.hasFeature(GuildFeature.COMMUNITY) == true)
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.actionChannelNotifications.setOnClickListener {
            ChannelListNavigation.showChannelNotificationSettings(this, channel.id)
        }

        // A vocal channel's chat is reachable without joining the call, which is the only way in
        // for someone who wants to read it without connecting.
        binding.actionOpenChat.visibility = if (channel.isGuildVocalChannel()) View.VISIBLE else View.GONE
        binding.actionOpenChat.setOnClickAndDismissListener {
            ChannelSelector.getInstance().openVocalChannelChat(
                channel,
                SelectedChannelAnalyticsLocation.TEXT_IN_VOICE,
            )
        }

        binding.textActionMarkAsRead.visibility =
            if (channel.hasMessages() || channel.isCategory()) View.VISIBLE else View.GONE
        binding.textActionMarkAsRead.setOnClickAndDismissListener {
            StoreStream.getMessageAck().ack(channel.id, isLockedAck = false, clearLock = true)
        }

        configureOptIn(channel)
        configurePinChannel(channel)
        configureCopyLink(channel)
        configureCloneChannel(model)
        configureVoice(model)
        configureCloseChannel(channel)

        val spoilerAgree = StoreStream.getChannelSpoilerAgree()
        binding.actionRehideSpoiler.visibility =
            if (channel.isSpoilerChannel() && spoilerAgree.didAgree(channel.id)) View.VISIBLE else View.GONE
        binding.actionRehideSpoiler.setOnClickAndDismissListener { spoilerAgree.clearAgreement(channel.id) }

        binding.developerDivider.visibility = if (model.isDeveloper) View.VISIBLE else View.GONE
        binding.actionCopyId.visibility = if (model.isDeveloper) View.VISIBLE else View.GONE
        binding.actionCopyId.setOnClickAndDismissListener {
            ChannelListNavigation.copyToClipboard(requireContext(), channel.id.toString())
        }
        // Each modern card hides itself once none of its rows apply.
        (binding.root.getChildAt(0) as ViewGroup).children
            .filterIsInstance<MessageMenuGroup>()
            .forEach(MessageMenuGroup::refresh)
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
        binding.actionCopyLink.visibility = if (channel.isGuildTextyChannel() || channel.isGuildVocalChannel()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.actionCopyLink.setOnClickAndDismissListener {
            AppToast.copyToClipboard(
                requireContext(),
                "${ClientBackend.active.web}/channels/${channel.guildId}/${channel.id}",
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
        binding.actionCloneChannel.visibility = if (model.canManageChannel && supported) View.VISIBLE else View.GONE
        binding.actionCloneChannel.setText(if (channel.isCategory()) R.string.create_channel else R.string.clone_channel)
        binding.actionCloneChannel.setOnClickAndDismissListener {
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

        binding.actionJoinStage.visibility = if (isStage) View.VISIBLE else View.GONE
        binding.actionJoinStage.setOnClickAndDismissListener {
            StageChannelJoinHelper.connectToStage(requireContext(), parentFragmentManager, channel.id)
        }

        binding.actionHideNames.visibility = if (channel.type == Channel.GUILD_VOICE) View.VISIBLE else View.GONE
        binding.actionHideNames.setText(
            if (model.isVoiceCollapsed) R.string.voice_channel_show_names else R.string.voice_channel_hide_names,
        )
        binding.actionHideNames.setOnClickAndDismissListener {
            StoreStream.getCollapsedVoiceChannels().toggle(channel.id)
        }

        binding.actionEndStage.visibility = if (isStage && model.isStageLive && model.canModerateStage) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.actionEndStage.setOnClickAndDismissListener {
            WidgetEndStageBottomSheet.show(parentFragmentManager, END_STAGE_REQUEST_KEY, channel.id)
        }
    }

    /**
     * Closing a one-to-one DM only hides it, so it goes through unconfirmed; leaving a group needs a
     * re-invite to undo and does not.
     */
    private fun configureCloseChannel(channel: Channel) {
        val isGroup = channel.isMultiUserDM()
        binding.actionCloseDm.visibility = if (channel.isPrivate()) View.VISIBLE else View.GONE
        binding.actionCloseDm.setText(if (isGroup) R.string.leave_group_dm else R.string.close_dm)
        binding.actionCloseDm.setOnClickAndDismissListener {
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
