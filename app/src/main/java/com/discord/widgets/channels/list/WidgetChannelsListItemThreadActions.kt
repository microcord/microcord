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
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.permission.Permission
import com.discord.app.AppBottomSheet
import com.discord.i18n.i18nFormat
import com.discord.models.guild.Guild
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.stores.StoreThreadsJoined.JoinedThread
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
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
    private lateinit var unarchive: TextView
    private lateinit var leave: TextView
    private lateinit var developerDivider: View
    private lateinit var copyId: TextView

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

        markAsRead.setOnClickAndDismissListener {
            StoreStream.getReadStates().markAsRead(channel.id)
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

        unarchive.visibility =
            if (model.canUnarchiveThread && metadata?.archived == true) View.VISIBLE else View.GONE
        unarchive.setOnClickListener { unarchiveThread(channel, model.isModerator) }

        leave.visibility = if (model.joinedThread != null) View.VISIBLE else View.GONE
        leave.setOnClickListener { leaveThread(channel) }

        developerDivider.visibility = if (model.isDeveloper) View.VISIBLE else View.GONE
        copyId.visibility = if (model.isDeveloper) View.VISIBLE else View.GONE
        copyId.setOnClickAndDismissListener {
            ChannelListNavigation.copyToClipboard(requireContext(), channel.id.toString())
        }
    }

    private fun muteThreadText(channel: Channel, isMuted: Boolean, context: Context): CharSequence =
        if (isMuted) {
            context.getString(R.string.unmute)
        } else {
            context.i18nFormat(
                R.string.mute_channel,
                "name" to channel.getDisplayNameOrDefault(context),
            )
        }

    @DrawableRes
    private fun muteIconResId(isMuted: Boolean, context: Context): Int = context.themedDrawableRes(
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
        val channel: Channel,
        val guild: Guild?,
        val parentChannel: Channel?,
        val joinedThread: JoinedThread?,
        val isMuted: Boolean,
        val isActiveThread: Boolean,
        val isModerator: Boolean,
        val canManageThread: Boolean,
        val canUnarchiveThread: Boolean,
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
                StoreStream.getGuilds().observeGuild(channel.guildId),
                StoreStream.getChannels().observeChannel(channel.parentId),
                StoreStream.getPermissions().observePermissionsForChannel(channel.id),
                StoreStream.getUsers().observeMeId(),
                ChannelListSources.joinedThreads(),
            ) { guild, parentChannel, permissions, meId, joinedThreads ->
                val joinedThread = joinedThreads[channel.id]
                val isModerator = PermissionUtils.can(Permission.MANAGE_THREADS, permissions)
                val isOwner = channel.ownerId == meId
                val locked = channel.threadMetadata?.locked == true
                Model(
                    channel = channel,
                    guild = guild,
                    parentChannel = parentChannel,
                    joinedThread = joinedThread,
                    isMuted = joinedThread?.muted == true,
                    isActiveThread = channel.threadMetadata?.archived == false,
                    isModerator = isModerator,
                    canManageThread = isModerator || isOwner,
                    // A locked thread can only be reopened by someone who can clear the lock.
                    canUnarchiveThread = isModerator || (isOwner && !locked),
                )
            }
        }
    }

    companion object {
        private const val ARG_CHANNEL_ID = "INTENT_EXTRA_CHANNEL_ID"

        /** Analytics location reported when the user leaves the thread from this sheet. */
        private const val LEAVE_LOCATION = "Context Menu"

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
