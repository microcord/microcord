package com.discord.utilities.voice

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.discord.R
import com.discord.app.AppActivity
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.extensions.immutablePendingIntentFlag

object VoiceEngineNotificationBuilder {
    private fun Context.getServiceActionIntent(clazz: Class<*>, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            0,
            Intent(this, clazz).setAction(action),
            immutablePendingIntentFlag(),
        )

    fun buildNotification(
        context: Context,
        actionMain: String,
        actionDisconnect: String,
        actionStopStream: String,
        actionToggleMuted: String?,
        actionToggleDeafened: String,
        notificationServiceClass: Class<*>,
        notificationChannel: String,
        notificationChannelId: Long,
        notificationTitle: CharSequence?,
        notificationSubtitle: CharSequence?,
        notificationClass: Class<*>,
        isStreaming: Boolean,
        isMuted: Boolean,
        isDeafened: Boolean,
    ): Notification {
        val builder = NotificationCompat.Builder(context, notificationChannel)
            .setContentTitle(notificationTitle)
            .setContentText(notificationSubtitle)
            .setColor(ColorCompat.getThemedColor(context, R.attr.color_brand))
            .setColorized(true)
            .setSmallIcon(R.drawable.ic_notification_24dp)
            .setContentIntent(
                context.getCallScreenNavigationIntent(notificationChannelId, actionMain, notificationClass),
            )
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

        notificationActions(
            actionDisconnect,
            actionStopStream,
            actionToggleMuted,
            actionToggleDeafened,
            isStreaming,
            isMuted,
            isDeafened,
        ).forEach { action ->
            builder.addAction(
                NotificationCompat.Action(
                    action.icon,
                    context.getString(action.label),
                    context.getServiceActionIntent(notificationServiceClass, action.action),
                ),
            )
        }
        return builder.build()
    }

    fun Context.getCallScreenNavigationIntent(
        voiceChannelId: Long,
        action: String = "com.discord.utilities.voice.action.main",
        fullscreenClass: Class<*> = AppActivity.Main::class.java,
    ): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(
            "com.discord.intent.action.CONNECT",
            Uri.parse("discord://app/connect/$voiceChannelId"),
        ).setClass(this, fullscreenClass)
            .setAction(action)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        immutablePendingIntentFlag(),
    )

    internal data class Action(
        @DrawableRes val icon: Int,
        @StringRes val label: Int,
        val action: String,
    )

    internal fun notificationActions(
        disconnect: String,
        stopStream: String,
        toggleMuted: String?,
        toggleDeafened: String,
        isStreaming: Boolean,
        isMuted: Boolean,
        isDeafened: Boolean,
    ): List<Action> = buildList {
        add(Action(R.drawable.ic_call_disconnect_24dp, R.string.disconnect_self, disconnect))
        if (isStreaming) {
            add(Action(R.drawable.ic_mobile_screenshare_end_24dp, R.string.stop_streaming, stopStream))
        } else {
            toggleMuted?.let {
                add(
                    Action(
                        if (isMuted) R.drawable.ic_mic_white_18dp_muted else R.drawable.ic_mic_white_18dp,
                        if (isMuted) R.string.unmute else R.string.mute,
                        it,
                    ),
                )
            }
            add(
                Action(
                    if (isDeafened) R.drawable.ic_headset_white_18dp_deafened else R.drawable.ic_headset_white_18dp,
                    if (isDeafened) R.string.undeafen else R.string.deafen,
                    toggleDeafened,
                ),
            )
        }
    }
}
