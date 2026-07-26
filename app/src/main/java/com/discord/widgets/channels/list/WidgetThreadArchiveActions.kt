package com.discord.widgets.channels.list

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.app.AppBottomSheet
import com.discord.i18n.i18nFormat
import com.discord.models.guild.Guild
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.time.TimeUtils
import com.google.android.material.radiobutton.MaterialRadioButton
import rx.Observable
import rx.subscriptions.CompositeSubscription

/**
 * The thread auto-archive sheet: pick how long the thread stays active without a message, or
 * archive it immediately.
 *
 * Selecting a duration or archiving dismisses the sheet as soon as the request succeeds — there is
 * no local optimism, because the gateway echoes the new metadata back into `StoreChannels`.
 */
class WidgetThreadArchiveActions : AppBottomSheet() {

    override fun getContentViewResId(): Int = R.layout.widget_thread_archive_actions_sheet

    private lateinit var autoArchiveHeader: LinearLayout
    private lateinit var subtitle: TextView
    private lateinit var archiveNow: TextView
    private lateinit var optionOneHour: View
    private lateinit var optionOneHourRadio: MaterialRadioButton
    private lateinit var optionTwentyFourHours: View
    private lateinit var optionTwentyFourHoursRadio: MaterialRadioButton
    private lateinit var optionThreeDays: View
    private lateinit var optionThreeDaysRadio: MaterialRadioButton
    private lateinit var optionSevenDays: View
    private lateinit var optionSevenDaysRadio: MaterialRadioButton

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        autoArchiveHeader = view.findViewById(R.id.auto_archive_header)
        subtitle = view.findViewById(R.id.subtitle)
        archiveNow = view.findViewById(R.id.archive_now)
        optionOneHour = view.findViewById(R.id.option_one_hour)
        optionOneHourRadio = view.findViewById(R.id.option_one_hour_radio)
        optionTwentyFourHours = view.findViewById(R.id.option_twenty_four_hours)
        optionTwentyFourHoursRadio = view.findViewById(R.id.option_twenty_four_hours_radio)
        optionThreeDays = view.findViewById(R.id.option_three_days)
        optionThreeDaysRadio = view.findViewById(R.id.option_three_days_radio)
        optionSevenDays = view.findViewById(R.id.option_seven_days)
        optionSevenDaysRadio = view.findViewById(R.id.option_seven_days_radio)
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        super.bindSubscriptions(compositeSubscription)
        val channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: return
        val guildId = arguments?.getLong(ARG_GUILD_ID) ?: return
        Model.get(channelId, guildId)
            .ui(this)
            .appSubscribe(WidgetThreadArchiveActions::class.java, onNext = ::configureUI)
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
        val duration = channel.threadMetadata?.autoArchiveDuration

        autoArchiveHeader.visibility = View.VISIBLE
        subtitle.text = subtitle.i18nFormat(
            R.string.auto_archive_thread_at_long,
            "time" to TimeUtils.toReadableTimeString(
                requireContext(),
                computeThreadAutoArchiveTimeMs(channel),
            ),
        )

        archiveNow.visibility = View.VISIBLE
        archiveNow.setOnClickListener { archiveThread(channel, lockThread = model.isModerator) }

        bindOption(optionOneHour, optionOneHourRadio, channel, duration, ONE_HOUR_MINUTES)
        bindOption(
            optionTwentyFourHours,
            optionTwentyFourHoursRadio,
            channel,
            duration,
            ONE_DAY_MINUTES,
        )
        bindOption(optionThreeDays, optionThreeDaysRadio, channel, duration, THREE_DAYS_MINUTES)
        bindOption(optionSevenDays, optionSevenDaysRadio, channel, duration, ONE_WEEK_MINUTES)
    }

    private fun bindOption(
        row: View,
        radio: MaterialRadioButton,
        channel: Channel,
        currentDuration: Int?,
        minutes: Int,
    ) {
        row.setOnClickListener { setAutoArchiveDuration(channel, minutes) }
        radio.isChecked = currentDuration == minutes
    }

    /**
     * [lockThread] is sent only by a moderator: a non-moderator archiving their own thread must
     * leave the lock flag alone, or they would lock themselves out of reopening it.
     */
    private fun archiveThread(channel: Channel, lockThread: Boolean) {
        RestAPI.api
            .editThread(
                channel.id,
                RestAPIParams.ThreadSettings(
                    archived = true,
                    locked = if (lockThread) true else null,
                ),
            )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetThreadArchiveActions::class.java) { dismiss() }
    }

    private fun setAutoArchiveDuration(channel: Channel, minutes: Int) {
        RestAPI.api
            .editThread(channel.id, RestAPIParams.ThreadSettings(autoArchiveDuration = minutes))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetThreadArchiveActions::class.java) { dismiss() }
    }

    private data class Model(
        val channel: Channel,
        val guild: Guild?,
        val isModerator: Boolean,
    ) {
        companion object {
            fun get(channelId: Long, guildId: Long): Observable<Model?> = Observable
                .combineLatest(
                    StoreStream.getChannels().observeChannel(channelId),
                    StoreStream.getGuilds().observeGuild(guildId),
                    StoreStream.getPermissions().observePermissionsForChannel(channelId),
                ) { channel, guild, permissions ->
                    channel?.let {
                        Model(
                            it,
                            guild,
                            PermissionUtils.can(Permission.MANAGE_THREADS, permissions),
                        )
                    }
                }
                .computationLatest()
                .distinctUntilChanged()
        }
    }

    companion object {
        private const val ARG_CHANNEL_ID = "INTENT_EXTRA_CHANNEL_ID"
        private const val ARG_GUILD_ID = "INTENT_EXTRA_GUILD_ID"

        const val ONE_HOUR_MINUTES = 60
        const val ONE_DAY_MINUTES = 1440
        const val THREE_DAYS_MINUTES = 4320
        const val ONE_WEEK_MINUTES = 10080

        @JvmStatic
        fun show(fragmentManager: FragmentManager, channelId: Long, guildId: Long) {
            WidgetThreadArchiveActions()
                .apply {
                    arguments = Bundle().apply {
                        putLong(ARG_CHANNEL_ID, channelId)
                        putLong(ARG_GUILD_ID, guildId)
                    }
                }
                .show(fragmentManager, WidgetThreadArchiveActions::class.java.name)
        }

        /**
         * When the thread will archive if nothing else is said in it.
         *
         * Reference: `ThreadUtils.computeThreadAutoArchiveTimeMs`, which is not reconstructed. The
         * clock starts at the metadata's archive timestamp — the API refreshes that on every
         * message, so it is the last-activity time, not the time the thread was created.
         */
        @JvmStatic
        fun computeThreadAutoArchiveTimeMs(channel: Channel): Long {
            val metadata = channel.threadMetadata ?: return 0L
            val lastActivityMs = TimeUtils.parseUTCDate(metadata.archiveTimestamp)
            return lastActivityMs + metadata.autoArchiveDuration * MILLIS_PER_MINUTE
        }

        private const val MILLIS_PER_MINUTE = 60_000L
    }
}
