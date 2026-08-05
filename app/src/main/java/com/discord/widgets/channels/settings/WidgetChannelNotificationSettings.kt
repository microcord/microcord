package com.discord.widgets.channels.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.isCategory
import com.discord.api.channel.isThreadOnlyChannel
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.channel.isTextChannel
import com.discord.api.channel.isThread
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.guild.Guild
import com.discord.stores.StoreThreadsJoined
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelNotificationSettingsUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.views.CheckedSetting
import com.discord.views.RadioManager
import com.discord.widgets.servers.NotificationMuteSettingsView
import com.discord.widgets.settings.SettingsStrings
import com.discord.widgets.settings.WidgetMuteSettingsSheet
import rx.Observable

class WidgetChannelNotificationSettings : AppFragment(R.layout.widget_channel_notification_settings) {

    private lateinit var radioManager: RadioManager

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.notification_settings)
        radioManager = RadioManager(
            listOf(
                view.findViewById<CheckedSetting>(R.id.frequency_radio_all),
                view.findViewById<CheckedSetting>(R.id.frequency_radio_mentions),
                view.findViewById<CheckedSetting>(R.id.frequency_radio_nothing),
            ),
        )
        view.findViewById<View>(R.id.channel_notification_settings_system).apply {
            isVisible = arguments?.takeIf { it.containsKey(ARG_SHOW_SYSTEM) }?.getBoolean(ARG_SHOW_SYSTEM)
                ?: mostRecentIntent.getBooleanExtra(ARG_SHOW_SYSTEM, false)
            setOnClickListener { openSystemNotificationSettings(it.context) }
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        Model.observe(requireChannelId()).ui(this)
            .appSubscribe(WidgetChannelNotificationSettings::class.java, requireContext()) { model ->
                if (model == null) activity?.finish() else render(model)
            }
    }

    private fun render(model: Model) {
        val root = view ?: return
        val channel = model.channel
        val isPrivate = channel.isPrivate()
        val isTextChannel = channel.isTextChannel()
        val isCategory = channel.isCategory()
        val supportsMute = isTextChannel || isCategory

        setActionBarSubtitle(channel.getDisplayNameOrDefault(requireContext()))
        root.findViewById<NotificationMuteSettingsView>(R.id.mute_settings).apply {
            isVisible = supportsMute
            updateView(
                NotificationMuteSettingsView.ViewState(
                    model.channelIsMuted,
                    model.channelMuteEndTime,
                    if (isPrivate) {
                        getString(R.string.mute_conversation)
                    } else if (isTextChannel) {
                        SettingsStrings.render(
                            requireContext(),
                            R.string.mute_channel,
                            "name" to channel.getDisplayNameOrDefault(requireContext()),
                        )
                    } else {
                        getString(R.string.mute_category)
                    },
                    if (isPrivate) {
                        getString(R.string.unmute_conversation)
                    } else if (isTextChannel) {
                        SettingsStrings.render(
                            requireContext(),
                            R.string.unmute_channel,
                            "name" to channel.getDisplayNameOrDefault(requireContext()),
                        )
                    } else {
                        getString(R.string.unmute_category)
                    },
                    getString(
                        if (isPrivate) R.string.form_label_mobile_dm_muted
                        else R.string.form_label_mobile_channel_muted,
                    ),
                    if (isPrivate) {
                        R.string.form_label_mobile_dm_muted_until
                    } else if (isTextChannel) {
                        R.string.form_label_mobile_channel_muted_until
                    } else {
                        R.string.form_label_mobile_category_muted_until
                    },
                    if (isPrivate) {
                        null
                    } else {
                        getString(if (isTextChannel) {
                            R.string.form_label_mobile_channel_override_mute
                        } else {
                            R.string.form_label_mobile_category_override_mute
                        })
                    },
                ),
                { WidgetMuteSettingsSheet.showForChannel(channel.id, parentFragmentManager) },
                { StoreStream.getUserGuildSettings().setChannelMuted(requireContext(), channel.id, false) },
            )
        }
        root.findViewById<View>(R.id.frequency_top_divider).isVisible = supportsMute

        root.findViewById<View>(R.id.channel_settings_notifications_frequency_wrap).isVisible = !isPrivate
        root.findViewById<View>(R.id.channel_notification_settings_system).isVisible =
            !isPrivate && root.findViewById<View>(R.id.channel_notification_settings_system).isVisible
        if (isPrivate) {
            root.findViewById<LinearLayout>(R.id.forum_channel_notification_settings).isVisible = false
            return
        }

        configureRadio(root.findViewById(R.id.frequency_radio_all), model, ModelNotificationSettings.FREQUENCY_ALL)
        configureRadio(root.findViewById(R.id.frequency_radio_mentions), model, ModelNotificationSettings.FREQUENCY_MENTIONS)
        configureRadio(root.findViewById(R.id.frequency_radio_nothing), model, ModelNotificationSettings.FREQUENCY_NOTHING)
        root.findViewById<CheckedSetting>(R.id.frequency_radio_all).isVisible = supportsMute
        root.findViewById<CheckedSetting>(R.id.frequency_radio_mentions).setText(
            getString(
                if (channel.isStageVoiceChannel()) {
                    R.string.form_label_live_stages_only
                } else {
                    R.string.form_label_only_mentions
                },
            ),
        )

        if (channel.isThreadOnlyChannel()) {
            configureForum(root, model)
        } else {
            root.findViewById<LinearLayout>(R.id.forum_channel_notification_settings).isVisible = false
            root.findViewById<TextView>(R.id.frequency_subheader).isVisible = false
        }
    }

    private fun configureRadio(radio: CheckedSetting, model: Model, frequency: Int) {
        radio.setContainerClickListener(null)
        radio.setDisabledTooltip(null)
        if (model.isGuildMuted || model.channelIsMuted) {
            radio.setDisabledTooltip(R.string.channel_or_guild_muted)
        } else {
            radio.setContainerClickListener {
                StoreStream.getUserGuildSettings().setChannelFrequency(it.context, model.channel, frequency)
            }
        }
        radio.setSubtext(
            if (model.isAboveNotifyAllSize && frequency == ModelNotificationSettings.FREQUENCY_ALL) {
                getString(R.string.large_guild_notify_all_messages_description)
            } else {
                null
            },
        )
        val selected = model.notificationSetting == frequency
        radio.setButtonAlpha(if (selected && model.notificationSettingIsInherited) RADIO_DIMMED_ALPHA else 1f)
        if (selected) radioManager.setChecked(radio)
    }

    private fun configureForum(root: View, model: Model) {
        root.findViewById<LinearLayout>(R.id.forum_channel_notification_settings).isVisible = true
        root.findViewById<TextView>(R.id.frequency_header).setText(R.string.forum_message_notifications)
        root.findViewById<TextView>(R.id.frequency_subheader).apply {
            isVisible = true
            setText(R.string.forum_message_notifications_description)
        }
        root.findViewById<CheckedSetting>(R.id.frequency_all_posts).apply {
            setOnCheckedListener(null)
            isChecked = when (model.forumPostNotificationSetting) {
                ModelNotificationSettings.FORUM_THREAD_CREATED_ON -> true
                ModelNotificationSettings.FORUM_THREAD_CREATED_OFF -> false
                else -> model.parentNotificationSetting == ModelNotificationSettings.FREQUENCY_ALL
            }
            setOnCheckedListener { checked ->
                val setting = if (checked) {
                    ModelNotificationSettings.FORUM_THREAD_CREATED_ON
                } else {
                    ModelNotificationSettings.FORUM_THREAD_CREATED_OFF
                }
                val flags = setting or
                    ((model.channelOverrideFlags ?: 0) and ModelNotificationSettings.FORUM_THREAD_CREATED_MASK.inv())
                StoreStream.getUserGuildSettings().setChannelNotificationFlags(context, model.channel, flags)
            }
        }
    }

    private fun openSystemNotificationSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }
    }

    private fun requireChannelId() =
        arguments?.takeIf { it.containsKey(ARG_CHANNEL_ID) }?.getLong(ARG_CHANNEL_ID)
            ?: mostRecentIntent.getLongExtra(ARG_CHANNEL_ID, -1L)

    internal data class Model(
        val channel: Channel,
        val channelIsMuted: Boolean,
        val channelMuteEndTime: String?,
        val isGuildMuted: Boolean,
        val notificationSetting: Int,
        val channelOverrideFlags: Int?,
        val forumPostNotificationSetting: Int?,
        val parentNotificationSetting: Int?,
        val notificationSettingIsInherited: Boolean,
        val isAboveNotifyAllSize: Boolean,
    ) {
        companion object {
            fun observe(channelId: Long): Observable<Model?> =
                StoreStream.getChannels().observeChannel(channelId).switchMap { channel ->
                    if (channel == null) return@switchMap Observable.just<Model?>(null)
                    if (channel.isPrivate()) {
                        return@switchMap StoreStream.getUserGuildSettings()
                            .observeGuildSettings(PRIVATE_CHANNELS_GUILD_ID)
                            .map { createPrivate(channel, it) }
                    }
                    Observable.combineLatest(
                        StoreStream.getThreadsJoined().observeJoinedThread(channel.id),
                        StoreStream.getChannels().observeChannel(channel.parentId),
                        StoreStream.getGuilds().observeGuild(channel.guildId),
                        StoreStream.getUserGuildSettings().observeGuildSettings(channel.guildId),
                        StoreStream.getGuildProfiles().observeGuildProfile(channel.guildId),
                    ) { joinedThread, parent, guild, settings, profile ->
                        guild?.let {
                            create(
                                channel,
                                parent,
                                it,
                                settings,
                                joinedThread,
                                profile?.data?.approximateMemberCount,
                            )
                        }
                    }
                }.distinctUntilChanged()

            internal fun createPrivate(
                channel: Channel,
                settings: ModelNotificationSettings,
            ): Model {
                val override = settings.getChannelOverride(channel.id)
                val overrideFrequency = override?.messageNotifications
                    ?.takeUnless { it == ModelNotificationSettings.FREQUENCY_UNSET }
                return Model(
                    channel = channel,
                    channelIsMuted = override?.isMuted == true,
                    channelMuteEndTime = override?.muteEndTime,
                    isGuildMuted = false,
                    notificationSetting = overrideFrequency
                        ?: settings.messageNotifications.takeUnless {
                            it == ModelNotificationSettings.FREQUENCY_UNSET
                        }
                        ?: ModelNotificationSettings.FREQUENCY_MENTIONS,
                    channelOverrideFlags = override?.flags,
                    forumPostNotificationSetting = null,
                    parentNotificationSetting = settings.messageNotifications,
                    notificationSettingIsInherited = overrideFrequency == null,
                    isAboveNotifyAllSize = false,
                )
            }

            internal fun create(
                channel: Channel,
                parentChannel: Channel?,
                guild: Guild,
                guildSettings: ModelNotificationSettings,
                joinedThread: StoreThreadsJoined.JoinedThread?,
                approximateMemberCount: Int?,
            ): Model {
                val override = guildSettings.getChannelOverride(channel.id)
                val parentNotificationSetting = notificationLevel(guildSettings, parentChannel, guild)
                val notificationSetting = if (channel.isThread()) {
                    when (
                        threadNotificationSetting(
                            joinedThread,
                            isMuted(guildSettings, parentChannel),
                            parentNotificationSetting,
                        )
                    ) {
                        THREAD_SETTING_MENTIONS -> ModelNotificationSettings.FREQUENCY_MENTIONS
                        THREAD_SETTING_NOTHING -> ModelNotificationSettings.FREQUENCY_NOTHING
                        else -> ModelNotificationSettings.FREQUENCY_ALL
                    }
                } else {
                    ChannelNotificationSettingsUtils.computeNotificationSetting(guild, channel, guildSettings)
                }
                return Model(
                    channel = channel,
                    channelIsMuted = if (channel.isThread()) joinedThread?.muted == true else override?.isMuted == true,
                    channelMuteEndTime = if (channel.isThread()) {
                        joinedThread?.muteConfig?.endTime
                    } else {
                        override?.muteEndTime
                    },
                    isGuildMuted = guildSettings.isMuted,
                    notificationSetting = notificationSetting,
                    channelOverrideFlags = override?.flags,
                    forumPostNotificationSetting = override?.getForumPostNotificationSetting(),
                    parentNotificationSetting = parentNotificationSetting,
                    notificationSettingIsInherited = notificationSetting == ModelNotificationSettings.FREQUENCY_UNSET,
                    isAboveNotifyAllSize = (approximateMemberCount ?: 0) > 2_500,
                )
            }

            private fun notificationLevel(
                settings: ModelNotificationSettings,
                channel: Channel?,
                guild: Guild,
            ): Int? {
                if (channel == null) return guild.defaultMessageNotifications
                val channelLevel = settings.getChannelOverride(channel.id)?.messageNotifications
                    ?.takeUnless { it == ModelNotificationSettings.FREQUENCY_UNSET }
                val parentLevel = settings.getChannelOverride(channel.parentId)?.messageNotifications
                    ?.takeUnless { it == ModelNotificationSettings.FREQUENCY_UNSET }
                return channelLevel ?: parentLevel
                    ?: settings.messageNotifications.takeUnless { it == ModelNotificationSettings.FREQUENCY_UNSET }
                    ?: guild.defaultMessageNotifications
            }

            private fun isMuted(settings: ModelNotificationSettings, channel: Channel?): Boolean =
                settings.isMuted || channel != null && (
                    settings.getChannelOverride(channel.parentId)?.isMuted == true ||
                        settings.getChannelOverride(channel.id)?.isMuted == true
                    )

            private fun threadNotificationSetting(
                joinedThread: StoreThreadsJoined.JoinedThread?,
                parentMuted: Boolean,
                parentSetting: Int?,
            ): Int = when {
                joinedThread == null -> THREAD_SETTING_NOTHING
                joinedThread.flags and THREAD_FLAG_ALL_MESSAGES != 0 -> THREAD_SETTING_ALL
                joinedThread.flags and THREAD_FLAG_ONLY_MENTIONS != 0 -> THREAD_SETTING_MENTIONS
                joinedThread.flags and THREAD_FLAG_NO_MESSAGES != 0 || parentMuted -> THREAD_SETTING_NOTHING
                parentSetting == ModelNotificationSettings.FREQUENCY_MENTIONS -> THREAD_SETTING_MENTIONS
                parentSetting == ModelNotificationSettings.FREQUENCY_NOTHING -> THREAD_SETTING_NOTHING
                else -> THREAD_SETTING_ALL
            }
        }
    }

    companion object {
        const val ROUTE = "channel/{channelId}/notifications"
        private const val ARG_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val ARG_SHOW_SYSTEM = "SHOW_SYSTEM_SETTING"
        private const val PRIVATE_CHANNELS_GUILD_ID = 0L
        private const val RADIO_DIMMED_ALPHA = 0.3f
        private const val THREAD_FLAG_ALL_MESSAGES = 2
        private const val THREAD_FLAG_ONLY_MENTIONS = 4
        private const val THREAD_FLAG_NO_MESSAGES = 8
        private const val THREAD_SETTING_ALL = 2
        private const val THREAD_SETTING_MENTIONS = 4
        private const val THREAD_SETTING_NOTHING = 8

        fun newInstance(channelId: Long, showSystemSettings: Boolean = false) =
            WidgetChannelNotificationSettings().apply {
                arguments = argumentsOf(channelId, showSystemSettings)
            }

        @JvmStatic
        @JvmOverloads
        fun launch(context: Context, channelId: Long, showSystemSettings: Boolean = false) {
            AppScreenRouter.launch(
                context,
                WidgetChannelNotificationSettings::class.java,
                Intent().putExtras(argumentsOf(channelId, showSystemSettings)),
            )
        }

        private fun argumentsOf(channelId: Long, showSystemSettings: Boolean) = Bundle().apply {
            putLong(ARG_CHANNEL_ID, channelId)
            putBoolean(ARG_SHOW_SYSTEM, showSystemSettings)
        }
    }
}
