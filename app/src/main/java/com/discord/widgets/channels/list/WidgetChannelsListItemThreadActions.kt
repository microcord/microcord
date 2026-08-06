package com.discord.widgets.channels.list

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
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
import com.facebook.drawee.view.SimpleDraweeView
import java.util.Locale
import rx.Observable
import rx.subscriptions.CompositeSubscription

/**
 * The long-press sheet for a thread row in the channels sidebar.
 *
 * The three destructive/structural actions — edit, archive settings, unarchive — are gated
 * separately: editing needs `MANAGE_THREADS` or thread ownership, the archive-duration row only
 * makes sense while the thread is still active, and unarchiving is offered only once it is not.
 */
class WidgetChannelsListItemThreadActions : AppBottomSheet() {

    override fun getContentViewResId(): Int = R.layout.widget_channels_list_item_thread_actions

    private lateinit var icon: SimpleDraweeView
    private lateinit var title: TextView
    private lateinit var markAsRead: TextView
    private lateinit var mute: TextView
    private lateinit var notifications: TextView
    private lateinit var divider: View
    private lateinit var edit: TextView
    private lateinit var archiveSettings: LinearLayout
    private lateinit var archiveSettingsSubheader: TextView
    private lateinit var join: TextView
    private lateinit var archive: TextView
    private lateinit var lock: TextView
    private lateinit var copyLink: TextView
    private lateinit var unarchive: TextView
    private lateinit var leave: TextView
    private lateinit var developerDivider: View
    private lateinit var copyId: TextView
    private lateinit var editTags: TextView
    private lateinit var pin: TextView
    private lateinit var delete: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        icon = view.findViewById(R.id.channels_list_item_thread_actions_icon)
        title = view.findViewById(R.id.channels_list_item_thread_actions_title)
        markAsRead = view.findViewById(R.id.channels_list_item_thread_actions_mark_as_read)
        mute = view.findViewById(R.id.channels_list_item_thread_actions_mute)
        notifications = view.findViewById(R.id.channels_list_item_thread_actions_channel_notifications)
        divider = view.findViewById(R.id.channels_list_item_thread_actions_divider)
        edit = view.findViewById(R.id.channels_list_item_thread_actions_edit)
        archiveSettings = view.findViewById(R.id.channels_list_item_thread_actions_archive_settings)
        archiveSettingsSubheader =
            view.findViewById(R.id.channels_list_item_thread_actions_archive_settings_subheader)
        join = view.findViewById(R.id.channels_list_item_thread_actions_join)
        archive = view.findViewById(R.id.channels_list_item_thread_actions_archive)
        lock = view.findViewById(R.id.channels_list_item_thread_actions_lock)
        editTags = view.findViewById(R.id.channels_list_item_thread_actions_edit_tags)
        pin = view.findViewById(R.id.channels_list_item_thread_actions_pin)
        delete = view.findViewById(R.id.channels_list_item_thread_actions_delete)
        copyLink = view.findViewById(R.id.channels_list_item_thread_actions_copy_link)
        unarchive = view.findViewById(R.id.channels_list_item_thread_actions_unarchive)
        leave = view.findViewById(R.id.channels_list_item_thread_actions_leave)
        developerDivider = view.findViewById(R.id.channels_list_item_thread_actions_developer_divider)
        copyId = view.findViewById(R.id.channels_list_item_thread_actions_copy_id)
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
        val metadata = channel.threadMetadata

        if (model.guild != null) IconUtils.setIcon(icon, model.guild) else IconUtils.setIcon(icon, channel)
        title.text = channel.getDisplayNameOrDefault(requireContext())
        configureForumPostLabels(model)
        configureForumPostActions(model)

        markAsRead.setOnClickAndDismissListener {
            StoreStream.getMessageAck().ack(channel.id, isLockedAck = false, clearLock = true)
        }

        mute.text = muteThreadText(channel, model.isMuted, requireContext())
        mute.setCompoundDrawablesWithIntrinsicBounds(
            muteIconResId(model.isMuted, requireContext()),
            0,
            0,
            0,
        )
        mute.setOnClickAndDismissListener {
            if (model.isMuted) {
                StoreStream.getUserGuildSettings()
                    .setChannelMuted(requireContext(), channel.id, muted = false)
            } else {
                WidgetMuteSettingsSheet.showForChannel(channel.id, parentFragmentManager)
            }
        }

        notifications.setOnClickListener {
            ChannelListNavigation.showChannelNotificationSettings(this, channel.id)
        }

        // The divider only earns its space when the structural actions below it can appear.
        divider.visibility = if (model.isActiveThread) View.VISIBLE else View.GONE

        edit.visibility = if (model.canManageThread) View.VISIBLE else View.GONE
        edit.setOnClickAndDismissListener {
            ChannelListNavigation.showThreadSettings(this, channel.id)
        }

        val canChangeArchiveDuration = model.canManageThread && metadata?.archived == false
        archiveSettings.visibility = if (canChangeArchiveDuration) View.VISIBLE else View.GONE
        if (canChangeArchiveDuration && metadata != null) {
            archiveSettingsSubheader.text = archiveSettingsSubheader.i18nFormat(
                R.string.auto_archive_thread_after,
                "duration" to autoArchiveDurationName(requireContext(), metadata.autoArchiveDuration)
                    .lowercase(Locale.getDefault()),
            )
        }
        archiveSettings.setOnClickAndDismissListener {
            WidgetThreadArchiveActions.show(parentFragmentManager, channel.id, channel.guildId)
        }

        join.visibility = if (model.joinedThread == null) View.VISIBLE else View.GONE
        join.setOnClickListener { joinThread(channel) }

        unarchive.visibility =
            if (model.canUnarchiveThread && metadata?.archived == true) View.VISIBLE else View.GONE
        unarchive.setOnClickListener { unarchiveThread(channel, model.isModerator) }

        // The reference offers closing only while the thread is still open, and only to a manager.
        archive.visibility =
            if (model.canManageThread && metadata?.archived == false) View.VISIBLE else View.GONE
        archive.setOnClickListener { setThreadSettings(channel, RestAPIParams.ThreadSettings(archived = true)) }

        lock.visibility = if (model.isModerator) View.VISIBLE else View.GONE
        lock.setText(
            if (isForumPost(model)) {
                if (metadata?.locked == true) R.string.unlock_forum_post else R.string.lock_forum_post
            } else {
                if (metadata?.locked == true) R.string.unlock_thread else R.string.lock_thread
            },
        )
        lock.setOnClickListener {
            setThreadSettings(channel, RestAPIParams.ThreadSettings(locked = metadata?.locked != true))
        }

        copyLink.setOnClickAndDismissListener {
            val guildSegment = channel.guildId.takeIf { it != 0L }?.toString() ?: "@me"
            AppToast.copyToClipboard(
                requireContext(),
                "https://discord.com/channels/$guildSegment/${channel.id}",
                getString(R.string.link_copied),
            )
        }

        leave.visibility = if (model.joinedThread != null) View.VISIBLE else View.GONE
        leave.setOnClickListener { leaveThread(channel) }

        developerDivider.visibility = if (model.isDeveloper) View.VISIBLE else View.GONE
        copyId.visibility = if (model.isDeveloper) View.VISIBLE else View.GONE
        copyId.setOnClickAndDismissListener {
            ChannelListNavigation.copyToClipboard(requireContext(), channel.id.toString())
        }
    }

    /**
     * A thread inside a forum is a "post" everywhere in the product, so the same sheet relabels
     * itself rather than there being a near-identical forum copy of it.
     */
    private fun configureForumPostLabels(model: Model) {
        if (!isForumPost(model)) return
        edit.setText(R.string.edit_forum_post)
        join.setText(R.string.join_forum_post)
        leave.setText(R.string.leave_forum_post)
        archive.setText(R.string.archive_forum_post)
        unarchive.setText(R.string.unarchive_forum_post)
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

    @DrawableRes
    private fun muteIconResId(isMuted: Boolean, context: Context): Int = context.getThemedDrawableRes(
        if (isMuted) R.attr.ic_channel_muted else R.attr.ic_channel_mute,
    )

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

        editTags.visibility = if (isPost && model.canManageThread && hasTags) View.VISIBLE else View.GONE
        editTags.setOnClickListener {
            WidgetForumPostTagsSheet.show(
                parentFragmentManager,
                channel.parentId,
                channel.appliedTags.orEmpty(),
                threadId = channel.id,
                canManageThreads = model.canManageThread,
            )
            dismiss()
        }

        val isPinned = channel.flags?.and(Channel.FLAG_PINNED) == Channel.FLAG_PINNED
        pin.visibility = if (isPost && model.isModerator) View.VISIBLE else View.GONE
        pin.setText(if (isPinned) R.string.unpin_post else R.string.pin_post)
        pin.setOnClickListener {
            val flags = channel.flags ?: 0L
            setThreadSettings(
                channel,
                RestAPIParams.ThreadSettings(
                    flags = if (isPinned) flags and Channel.FLAG_PINNED.inv() else flags or Channel.FLAG_PINNED,
                ),
            )
        }

        delete.visibility = if (isPost && model.canManageThread) View.VISIBLE else View.GONE
        delete.setOnClickListener {
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
