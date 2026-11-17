package com.discord.widgets.channels.list

import com.discord.utilities.rest.ClientBackend
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import androidx.core.view.isVisible
import com.discord.databinding.WidgetChannelsListItemThreadActionsBinding
import com.discord.utilities.feature.FeatureFlags
import com.discord.widgets.chat.list.actions.MessageMenuGroup
import androidx.annotation.DrawableRes
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.isThreadOnlyChannel
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.permission.Permission
import com.discord.app.AppBottomSheet
import com.discord.app.AppToast
import com.discord.i18n.i18nFormat
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.widgets.channels.settings.ThreadDeleteDialog
import com.discord.widgets.forums.WidgetForumPostTagsSheet
import com.discord.stores.StoreThreadsJoined.JoinedThread
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rest.RestAPIAbortMessages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.threads.ThreadUtils
import com.discord.widgets.settings.SettingsStrings
import com.discord.widgets.settings.WidgetMuteSettingsSheet
import java.util.Locale
import rx.Observable
import rx.subscriptions.CompositeSubscription
import com.discord.plugins.PluginContextMenuHost
import dev.mantikafasi.manticord.plugins.api.PluginContextMenuSurface
import dev.mantikafasi.manticord.plugins.api.pluginContextMenuContext

/**
 * The long-press sheet for a thread row in the channels sidebar.
 *
 * The three destructive/structural actions — edit, archive settings, unarchive — are gated
 * separately: editing needs `MANAGE_THREADS` or thread ownership, the archive-duration row only
 * makes sense while the thread is still active, and unarchiving is offered only once it is not.
 */
class WidgetChannelsListItemThreadActions : AppBottomSheet() {

    override fun getContentViewResId(): Int =
        if (modern) R.layout.widget_channels_list_item_thread_actions_modern else R.layout.widget_channels_list_item_thread_actions

    /** The modern sheet is the default; the legacy one follows the legacy UI flag. Both share view ids. */
    private val modern by lazy { !FeatureFlags.legacyUiEnabled() }

    private var _binding: WidgetChannelsListItemThreadActionsBinding? = null
    private val binding get() = checkNotNull(_binding)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = WidgetChannelsListItemThreadActionsBinding.bind(view)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        super.bindSubscriptions(compositeSubscription)
        val channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: return
        Model.get(channelId)
            .ui(this)
            .appSubscribe(WidgetChannelsListItemThreadActions::class.java, onNext = ::configureUI)
    }

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
        PluginContextMenuHost.bind(
            binding.pluginContextMenuItems,
            pluginContextMenuContext(
                PluginContextMenuSurface.THREAD,
                channel.guildId.takeIf { it > 0L },
                channel.id,
                targetId = channel.id,
            ),
            ::dismiss,
            modern,
        )
        // An empty container would otherwise keep its card on screen with nothing in it.
        binding.pluginContextMenuItems.isVisible = binding.pluginContextMenuItems.childCount > 0
        val metadata = channel.threadMetadata

        if (model.guild != null) IconUtils.setIcon(binding.channelsListItemThreadActionsIcon, model.guild) else IconUtils.setIcon(binding.channelsListItemThreadActionsIcon, channel)
        binding.channelsListItemThreadActionsTitle.text = channel.getDisplayNameOrDefault(requireContext())
        configureForumPostLabels(model)
        configureForumPostActions(model)

        binding.channelsListItemThreadActionsMarkAsRead.setOnClickAndDismissListener {
            StoreStream.getMessageAck().ack(channel.id, isLockedAck = false, clearLock = true)
        }

        binding.channelsListItemThreadActionsMute.text = muteThreadText(channel, model.isMuted, requireContext())
        binding.channelsListItemThreadActionsMute.setCompoundDrawablesWithIntrinsicBounds(
            muteIconResId(model.isMuted, requireContext()),
            0,
            0,
            0,
        )
        binding.channelsListItemThreadActionsMute.setOnClickAndDismissListener {
            if (model.isMuted) {
                StoreStream.getUserGuildSettings()
                    .setChannelMuted(requireContext(), channel.id, muted = false)
            } else {
                WidgetMuteSettingsSheet.showForChannel(channel.id, parentFragmentManager)
            }
        }

        binding.channelsListItemThreadActionsChannelNotifications.setOnClickListener {
            ChannelListNavigation.showChannelNotificationSettings(this, channel.id)
        }

        // The divider only earns its space when the structural actions below it can appear.
        binding.channelsListItemThreadActionsDivider.visibility = if (model.isActiveThread) View.VISIBLE else View.GONE

        binding.channelsListItemThreadActionsEdit.visibility = if (model.canManageThread) View.VISIBLE else View.GONE
        binding.channelsListItemThreadActionsEdit.setOnClickAndDismissListener {
            ChannelListNavigation.showThreadSettings(this, channel.id)
        }

        val canChangeArchiveDuration = model.canManageThread && metadata?.archived == false
        binding.channelsListItemThreadActionsArchiveSettings.visibility = if (canChangeArchiveDuration) View.VISIBLE else View.GONE
        if (canChangeArchiveDuration && metadata != null) {
            binding.channelsListItemThreadActionsArchiveSettingsSubheader.text = binding.channelsListItemThreadActionsArchiveSettingsSubheader.i18nFormat(
                R.string.auto_archive_thread_after,
                "duration" to autoArchiveDurationName(requireContext(), metadata.autoArchiveDuration)
                    .lowercase(Locale.getDefault()),
            )
        }
        binding.channelsListItemThreadActionsArchiveSettings.setOnClickAndDismissListener {
            WidgetThreadArchiveActions.show(parentFragmentManager, channel.id, channel.guildId)
        }

        binding.channelsListItemThreadActionsJoin.visibility = if (model.joinedThread == null) View.VISIBLE else View.GONE
        binding.channelsListItemThreadActionsJoin.setOnClickListener { joinThread(channel) }

        binding.channelsListItemThreadActionsUnarchive.visibility =
            if (model.canUnarchiveThread && metadata?.archived == true) View.VISIBLE else View.GONE
        binding.channelsListItemThreadActionsUnarchive.setOnClickListener { unarchiveThread(channel, model.isModerator) }

        // The reference offers closing only while the thread is still open, and only to a manager.
        binding.channelsListItemThreadActionsArchive.visibility =
            if (model.canManageThread && metadata?.archived == false) View.VISIBLE else View.GONE
        binding.channelsListItemThreadActionsArchive.setOnClickListener { setThreadSettings(channel, RestAPIParams.ThreadSettings(archived = true)) }

        binding.channelsListItemThreadActionsLock.visibility = if (model.isModerator) View.VISIBLE else View.GONE
        binding.channelsListItemThreadActionsLock.setText(
            if (isForumPost(model)) {
                if (metadata?.locked == true) R.string.unlock_forum_post else R.string.lock_forum_post
            } else {
                if (metadata?.locked == true) R.string.unlock_thread else R.string.lock_thread
            },
        )
        binding.channelsListItemThreadActionsLock.setOnClickListener {
            setThreadSettings(channel, RestAPIParams.ThreadSettings(locked = metadata?.locked != true))
        }

        binding.channelsListItemThreadActionsCopyLink.setOnClickAndDismissListener {
            val guildSegment = channel.guildId.takeIf { it != 0L }?.toString() ?: "@me"
            AppToast.copyToClipboard(
                requireContext(),
                "${ClientBackend.active.web}/channels/$guildSegment/${channel.id}",
                getString(R.string.link_copied),
            )
        }

        binding.channelsListItemThreadActionsLeave.visibility = if (model.joinedThread != null) View.VISIBLE else View.GONE
        binding.channelsListItemThreadActionsLeave.setOnClickListener { leaveThread(channel) }

        binding.channelsListItemThreadActionsDeveloperDivider.visibility = if (model.isDeveloper) View.VISIBLE else View.GONE
        binding.channelsListItemThreadActionsCopyId.visibility = if (model.isDeveloper) View.VISIBLE else View.GONE
        binding.channelsListItemThreadActionsCopyId.setOnClickAndDismissListener {
            ChannelListNavigation.copyToClipboard(requireContext(), channel.id.toString())
        }
        // Each modern card hides itself once none of its rows apply.
        (binding.root.getChildAt(0) as ViewGroup).children
            .filterIsInstance<MessageMenuGroup>()
            .forEach(MessageMenuGroup::refresh)
    }

    /**
     * A thread inside a forum is a "post" everywhere in the product, so the same sheet relabels
     * itself rather than there being a near-identical forum copy of it.
     */
    private fun configureForumPostLabels(model: Model) {
        if (!isForumPost(model)) return
        binding.channelsListItemThreadActionsEdit.setText(R.string.edit_forum_post)
        binding.channelsListItemThreadActionsJoin.setText(R.string.join_forum_post)
        binding.channelsListItemThreadActionsLeave.setText(R.string.leave_forum_post)
        binding.channelsListItemThreadActionsArchive.setText(R.string.archive_forum_post)
        binding.channelsListItemThreadActionsUnarchive.setText(R.string.unarchive_forum_post)
    }

    private fun isForumPost(model: Model): Boolean =
        StoreStream.getChannels().getChannel(model.channel.parentId)?.isThreadOnlyChannel() == true

    /** `mute_channel` emphasises the name, so it needs the span renderer rather than plain fill-in. */
    private fun muteThreadText(channel: Channel, isMuted: Boolean, context: Context): CharSequence =
        SettingsStrings.render(
            context,
            if (isMuted) R.string.unmute_channel else R.string.mute_channel,
            "name" to channel.getDisplayNameOrDefault(context),
        )

    /** The themed icons carry a 60% alpha that tinting keeps, so the modern rows use opaque copies. */
    @DrawableRes
    private fun muteIconResId(isMuted: Boolean, context: Context): Int = when {
        !modern -> context.getThemedDrawableRes(if (isMuted) R.attr.ic_channel_muted else R.attr.ic_channel_mute)
        isMuted -> R.drawable.ic_menu_muted
        else -> R.drawable.ic_menu_mute
    }

    /**
     * [unlockThread] is only sent by a moderator; a thread owner reopening their own thread must
     * leave the lock untouched, because clearing it is a moderation action.
     */
    private fun unarchiveThread(channel: Channel, unlockThread: Boolean) {
        RestAPI.api
            .editThread(
                channel.id,
                RestAPIParams.ThreadSettings(
                    archived = false,
                    locked = if (unlockThread) false else null,
                ),
            )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                WidgetChannelsListItemThreadActions::class.java,
                context = context,
                errorHandler = { error ->
                    AppToast.show(
                        context,
                        RestAPIAbortMessages.getAbortCodeMessageResId(error.code)
                            ?: R.string.cannot_unarchive_thread,
                    )
                    error.showErrorToasts = false
                },
            ) { dismiss() }
    }

    private fun joinThread(channel: Channel) {
        RestAPI.api
            .joinThread(channel.id, JOIN_LOCATION, RestAPIParams.EmptyBody())
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                WidgetChannelsListItemThreadActions::class.java,
                context = context,
            ) { dismiss() }
    }

    /**
     * The three actions that only exist on a forum post: its tags, its pinned slot, and deleting it.
     *
     * A forum only keeps one pinned post, so pinning over an existing one replaces it — the
     * reference asks first.
     */
    private fun configureForumPostActions(model: Model) {
        val channel = model.channel
        val isPost = isForumPost(model)
        val parent = StoreStream.getChannels().getChannel(channel.parentId)
        val hasTags = !parent?.availableTags.isNullOrEmpty()

        binding.channelsListItemThreadActionsEditTags.visibility = if (isPost && model.canManageThread && hasTags) View.VISIBLE else View.GONE
        binding.channelsListItemThreadActionsEditTags.setOnClickListener {
            WidgetForumPostTagsSheet.show(
                parentFragmentManager,
                channel.parentId,
                channel.appliedTags.orEmpty(),
                threadId = channel.id,
                // The author may retag their post too, but only moderators apply moderated tags.
                canManageThreads = model.isModerator,
            )
            dismiss()
        }

        val isPinned = channel.flags?.and(Channel.FLAG_PINNED) == Channel.FLAG_PINNED
        binding.channelsListItemThreadActionsPin.visibility = if (isPost && model.isModerator) View.VISIBLE else View.GONE
        binding.channelsListItemThreadActionsPin.setText(if (isPinned) R.string.unpin_post else R.string.pin_post)
        binding.channelsListItemThreadActionsPin.setOnClickListener {
            val flags = channel.flags ?: 0L
            setThreadSettings(
                channel,
                RestAPIParams.ThreadSettings(
                    flags = if (isPinned) flags and Channel.FLAG_PINNED.inv() else flags or Channel.FLAG_PINNED,
                ),
            )
        }

        binding.channelsListItemThreadActionsDelete.visibility = if (isPost && model.canManageThread) View.VISIBLE else View.GONE
        binding.channelsListItemThreadActionsDelete.setOnClickListener {
            ThreadDeleteDialog.show(
                parentFragmentManager,
                channel.id,
                channel.getDisplayNameOrDefault(requireContext()),
            )
            dismiss()
        }
    }

    /** Closing and locking share one PATCH; the caller decides which field it is changing. */
    private fun setThreadSettings(channel: Channel, settings: RestAPIParams.ThreadSettings) {
        RestAPI.api
            .editThread(channel.id, settings)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                WidgetChannelsListItemThreadActions::class.java,
                context = context,
            ) { dismiss() }
    }

    private fun leaveThread(channel: Channel) {
        RestAPI.api
            .leaveThread(channel.id, LEAVE_LOCATION)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                WidgetChannelsListItemThreadActions::class.java,
                context = context,
            ) { dismiss() }
    }

    private data class Model(
        val meUser: MeUser,
        val channel: Channel,
        val guild: Guild?,
        val parentChannel: Channel?,
        val notificationSetting: Int,
        val joinedThread: JoinedThread?,
        val isActiveThread: Boolean,
        val isModerator: Boolean,
        val canManageThread: Boolean,
        val canUnarchiveThread: Boolean,
        val isMuted: Boolean,
    ) {
        val isDeveloper: Boolean = StoreStream.getUserSettings().getIsDeveloperMode()

        companion object {
            fun get(channelId: Long): Observable<Model?> = StoreStream.getChannels()
                .observeChannel(channelId)
                .switchMap { channel ->
                    val source: Observable<Model?> =
                        if (channel == null) Observable.just(null) else build(channel)
                    source
                }
                .computationLatest()
                .distinctUntilChanged()

            private fun build(channel: Channel): Observable<Model?> = Observable.combineLatest(
                StoreStream.getUsers().observeMe(),
                StoreStream.getChannels().observeChannel(channel.parentId),
                StoreStream.getGuilds().observeGuild(channel.guildId),
                StoreStream.getPermissions().observePermissionsForChannel(channel.parentId),
                StoreStream.getUserGuildSettings().observeGuildSettings(),
                StoreStream.getThreadsJoined().observeJoinedThread(channel.id),
                StoreStream.getThreadsActive().observeActiveThreadsForGuild(channel.guildId)
                    .map { channel.id in it }
                    .distinctUntilChanged(),
            ) { me, parentChannel, guild, permissions, guildSettings, joinedThread, isActiveThread ->
                val isModerator = PermissionUtils.can(Permission.MANAGE_THREADS, permissions)
                val settings = guildSettings[channel.guildId]
                Model(
                    meUser = me,
                    channel = channel,
                    guild = guild,
                    parentChannel = parentChannel,
                    notificationSetting = computeThreadNotificationSetting(
                        joinedThread,
                        isMuted(settings, channel),
                        notificationLevel(settings, channel, guild),
                    ),
                    joinedThread = joinedThread,
                    isMuted = joinedThread?.muted == true,
                    isActiveThread = isActiveThread,
                    isModerator = isModerator,
                    canManageThread = ThreadUtils.canManageThread(me, channel, permissions),
                    canUnarchiveThread = ThreadUtils.canUnarchiveThread(channel, permissions),
                )
            }

            private fun isMuted(settings: ModelNotificationSettings?, channel: Channel): Boolean =
                settings?.isMuted == true ||
                    settings?.getChannelOverride(channel.parentId)?.isMuted == true ||
                    settings?.getChannelOverride(channel.id)?.isMuted == true

            private fun notificationLevel(
                settings: ModelNotificationSettings?,
                channel: Channel,
                guild: Guild?,
            ): Int? = settings?.getChannelOverride(channel.id)?.messageNotifications
                ?.takeUnless { it == ModelNotificationSettings.FREQUENCY_UNSET }
                ?: settings?.getChannelOverride(channel.parentId)?.messageNotifications
                    ?.takeUnless { it == ModelNotificationSettings.FREQUENCY_UNSET }
                ?: settings?.messageNotifications
                    ?.takeUnless { it == ModelNotificationSettings.FREQUENCY_UNSET }
                ?: guild?.defaultMessageNotifications

            private fun computeThreadNotificationSetting(
                joinedThread: JoinedThread?,
                muted: Boolean,
                parentSetting: Int?,
            ): Int {
                val flags = joinedThread?.flags ?: return THREAD_NOTIFICATION_NOTHING
                if (flags and THREAD_NOTIFICATION_ALL != 0) return THREAD_NOTIFICATION_ALL
                if (flags and THREAD_NOTIFICATION_MENTIONS != 0) return THREAD_NOTIFICATION_MENTIONS
                if (flags and THREAD_NOTIFICATION_NOTHING != 0 || muted) {
                    return THREAD_NOTIFICATION_NOTHING
                }
                return when (parentSetting) {
                    ModelNotificationSettings.FREQUENCY_MENTIONS -> THREAD_NOTIFICATION_MENTIONS
                    ModelNotificationSettings.FREQUENCY_NOTHING -> THREAD_NOTIFICATION_NOTHING
                    else -> THREAD_NOTIFICATION_ALL
                }
            }

            private const val THREAD_NOTIFICATION_ALL = 2
            private const val THREAD_NOTIFICATION_MENTIONS = 4
            private const val THREAD_NOTIFICATION_NOTHING = 8
        }
    }

    companion object {
        private const val ARG_CHANNEL_ID = "INTENT_EXTRA_CHANNEL_ID"

        /** Analytics location reported when the user leaves the thread from this sheet. */
        private const val LEAVE_LOCATION = "Context Menu"

        /** Same analytics location the reference reports when joining from this sheet. */
        private const val JOIN_LOCATION = "Context Menu"

        @JvmStatic
        fun show(fragmentManager: FragmentManager, channelId: Long) {
            WidgetChannelsListItemThreadActions()
                .apply {
                    arguments = Bundle().apply { putLong(ARG_CHANNEL_ID, channelId) }
                }
                .show(fragmentManager, WidgetChannelsListItemThreadActions::class.java.name)
        }

        /**
         * The translated name of an auto-archive duration, which the API states in minutes.
         *
         * Reference: `ThreadUtils.autoArchiveDurationName`, not reconstructed. The same table is
         * duplicated in `WidgetChatListAdapterItemThreadDraftForm`; converge when that utility
         * lands.
         */
        @JvmStatic
        fun autoArchiveDurationName(context: Context, minutes: Int): String = context.getString(
            when (minutes) {
                WidgetThreadArchiveActions.ONE_HOUR_MINUTES -> R.string.auto_archive_duration_1_hour
                WidgetThreadArchiveActions.THREE_DAYS_MINUTES ->
                    R.string.auto_archive_duration_3_days
                WidgetThreadArchiveActions.ONE_WEEK_MINUTES -> R.string.auto_archive_duration_1_week
                else -> R.string.auto_archive_duration_24_hours
            },
        )
    }
}
