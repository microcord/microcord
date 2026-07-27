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
import com.discord.api.channel.isGuildForumChannel
import com.discord.api.channel.isGuildTextChannel
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.channel.isMultiUserDM
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.channel.isTextChannel
import com.discord.api.channel.isThreaded
import com.discord.api.guild.GuildFeature
import com.discord.api.permission.Permission
import com.discord.app.AppBottomSheet
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.widgets.settings.WidgetMuteSettingsSheet
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
    private lateinit var mute: TextView
    private lateinit var threadBrowser: TextView
    private lateinit var channelSettings: TextView
    private lateinit var notifications: TextView
    private lateinit var invite: TextView
    private lateinit var developerDivider: View
    private lateinit var copyId: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        icon = view.findViewById(R.id.channels_list_item_text_actions_icon)
        title = view.findViewById(R.id.channels_list_item_text_actions_title)
        profile = view.findViewById(R.id.dm_action_profile)
        markAsRead = view.findViewById(R.id.text_action_mark_as_read)
        mute = view.findViewById(R.id.text_action_mute)
        threadBrowser = view.findViewById(R.id.text_action_thread_browser)
        channelSettings = view.findViewById(R.id.action_channel_settings)
        notifications = view.findViewById(R.id.action_channel_notifications)
        invite = view.findViewById(R.id.action_invite)
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

        invite.visibility = if (model.canCreateInstantInvite) View.VISIBLE else View.GONE
        invite.setOnClickAndDismissListener {
            ChannelListNavigation.shareGuildInvite(this, channel.guildId)
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
            channel.isThreaded() && !channel.isGuildForumChannel() && !channel.nsfw
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

        markAsRead.visibility =
            if (channel.isTextChannel() || channel.isCategory()) View.VISIBLE else View.GONE
        markAsRead.setOnClickAndDismissListener {
            StoreStream.getReadStates().markAsRead(channel.id)
        }

        developerDivider.visibility = if (model.isDeveloper) View.VISIBLE else View.GONE
        copyId.visibility = if (model.isDeveloper) View.VISIBLE else View.GONE
        copyId.setOnClickAndDismissListener {
            ChannelListNavigation.copyToClipboard(requireContext(), channel.id.toString())
        }
    }

    private fun settingsText(channel: Channel, canManageChannel: Boolean): String = getString(
        when {
            channel.isCategory() -> R.string.edit_category
            canManageChannel -> R.string.edit_channel
            else -> R.string.channel_settings
        },
    )

    private data class Model(
        val channel: Channel,
        val guild: Guild?,
        val permissions: Long?,
        val isMuted: Boolean,
    ) {
        /** Developer mode adds the "Copy ID" row and its divider. */
        val isDeveloper: Boolean = StoreStream.getUserSettings().getIsDeveloperMode()

        /** A group DM has no permission bits, but its owner can always rename it. */
        val canManageChannel: Boolean =
            PermissionUtils.can(Permission.MANAGE_CHANNELS, permissions) || channel.isMultiUserDM()

        val canCreateInstantInvite: Boolean =
            PermissionUtils.can(Permission.CREATE_INSTANT_INVITE, permissions)

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
                        ) { guild, permissions, guildSettings ->
                            val isMuted = guildSettings[channel.guildId]
                                ?.getChannelOverride(channel.id)
                                ?.isMuted == true
                            Model(channel, guild, permissions, isMuted)
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
