package com.discord.widgets.stage

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.stores.StoreChannels
import com.discord.stores.StoreStageChannels
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.fcm.NotificationClient
import com.discord.utilities.lifecycle.ApplicationProvider
import com.discord.utilities.lifecycle.Backgrounded

/**
 * Posts the system notification that tells the user a stage moderator has invited them to speak.
 *
 * Only fires while the app is backgrounded — in the foreground the stage screen shows the same
 * prompt inline, and two prompts for one invitation is worse than none.
 *
 * Install with `StoreStageChannels(..., notifications = StageChannelNotifications.INSTANCE)`;
 * the store calls [onInvitedToSpeak] the moment the user's role gains
 * `isInvitedToSpeak`, and [onInviteToSpeakRescinded] when it is withdrawn.
 */
class StageChannelNotifications(
    private val channelsStore: StoreChannels = StoreStream.getChannels(),
) : StoreStageChannels.StageChannelNotifications {

    @Volatile
    private var isBackgrounded: Boolean = true

    init {
        // The reference reads a plain boolean off its lifecycle observer. This project's
        // `Backgrounded` only exposes the stream, so the latest value is cached here instead.
        Backgrounded.observe().subscribe { isBackgrounded = it }
    }

    override fun onInvitedToSpeak(channelId: Long) {
        if (!isBackgrounded) return
        val channel = channelsStore.getChannel(channelId) ?: return
        val context = ApplicationProvider.get()
        val notification = createChannelNotificationBuilder(context, channel)
            .setContentText(context.getString(R.string.stage_speak_invite_header))
            // The reference attaches Accept and Decline actions here, built from
            // `VoiceEngineForegroundService.stageInviteAckPendingIntent`. Neither
            // `VoiceEngineForegroundService` nor `VoiceEngineNotificationBuilder` exists in this
            // reconstruction, so the notification currently informs without acting — tapping it
            // is the only way to respond. Add the two actions when `utilities/voice` lands; the
            // strings `stage_speak_invite_accept` and `stage_speak_invite_decline` are already in
            // res and `StageChannelAPI.ackInvitationToSpeak` is the call they must make.
            .build()
        Notifications.InvitedToSpeak.notify(context, notification)
    }

    override fun onInviteToSpeakRescinded() {
        Notifications.InvitedToSpeak.cancel(ApplicationProvider.get())
    }

    /** Replaces the invitation prompt with a generic failure when acking it did not go through. */
    fun onInvitedToSpeakAckFailed(channelId: Long) {
        val channel = channelsStore.getChannel(channelId) ?: return
        val context = ApplicationProvider.get()
        val notification = createChannelNotificationBuilder(context, channel)
            .setContentText(context.getString(R.string.error_generic_title))
            .build()
        Notifications.InvitedToSpeak.notify(context, notification)
    }

    private fun createChannelNotificationBuilder(
        context: Context,
        channel: Channel,
    ): NotificationCompat.Builder =
        NotificationCompat.Builder(context, NotificationClient.NOTIF_CHANNEL_CALLS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setColor(ColorCompat.getThemedColor(context, R.attr.color_brand_500))
            // A running stage shows its topic; one that has not started yet falls back to the
            // channel name.
            .setContentTitle(channel.topic ?: channel.name)
            .setSmallIcon(R.drawable.ic_channel_stage_24dp_white)

    object Notifications {
        object InvitedToSpeak {
            private const val NOTIFICATION_ID = 102

            fun cancel(context: Context) {
                NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, NOTIFICATION_ID)
            }

            fun notify(context: Context, notification: Notification) {
                NotificationManagerCompat.from(context)
                    .notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
            }
        }
    }

    companion object {
        const val NOTIFICATION_TAG = "stage-channels"

        val INSTANCE: StageChannelNotifications by lazy { StageChannelNotifications() }
    }
}
