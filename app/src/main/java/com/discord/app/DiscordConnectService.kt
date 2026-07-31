package com.discord.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.stores.StoreStream
import com.discord.utilities.fcm.NotificationClient
import com.discord.utilities.intent.DeepLinkParser
import com.discord.utilities.intent.DeepLinkRoute
import com.discord.utilities.intent.LinkParseResult
import com.discord.utilities.rx.takeSingleUntilTimeout
import com.discord.utilities.voice.DiscordOverlayService

/** Handles short-lived voice-connect intents from notifications and app-owned integrations. */
class DiscordConnectService : Service() {
    override fun onBind(intent: Intent?): IBinder = error("All my bases are belong to me!")

    override fun onCreate() {
        super.onCreate()
        AppLog.i(TAG, "onCreate")
        ensureNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, NotificationClient.NOTIF_CHANNEL_SOCIAL)
                .setOnlyAlertOnce(true)
                .setLocalOnly(true)
                .setSmallIcon(R.drawable.ic_notification_24dp)
                .setContentTitle(getString(R.string.connecting))
                .setContentText(getString(R.string.connection_status_awaiting_endpoint))
                .build(),
        )
        StoreStream.initialize(application)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i(TAG, "onStartCommand: $startId")
        val route = (DeepLinkParser.parse(intent?.dataString) as? LinkParseResult.Internal)?.route as? DeepLinkRoute.Voice
        if (route == null || !StoreStream.getAuthentication().isAuthed()) {
            finish(startId, "Invalid or unauthenticated request")
            return START_NOT_STICKY
        }
        val channelId = route.channelId
        if (channelId == null) {
            DiscordOverlayService.launchForConnect(this)
            finish(startId, "Opened overlay")
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finish(startId, "Missing microphone permission")
            return START_NOT_STICKY
        }

        StoreStream.getVoiceChannelSelected().selectVoiceChannel(channelId)
            .flatMap {
                StoreStream.getConnectionOpen().observeConnectionOpen(delayForUI = false)
                    .filter { it }
                    .takeSingleUntilTimeout(CONNECTION_TIMEOUT_MILLIS)
            }
            .subscribe(
                { finish(startId, "Success") },
                { failure ->
                    AppLog.w(TAG, "Request failed[$startId]", failure)
                    finish(startId, "Request failed")
                },
            )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AppLog.i(TAG, "onDestroy")
        super.onDestroy()
    }

    private fun finish(startId: Int, result: String) {
        AppLog.i(TAG, "$result[$startId]")
        stopForeground(true)
        stopSelf(startId)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                NotificationClient.NOTIF_CHANNEL_SOCIAL,
                NotificationClient.NOTIF_CHANNEL_SOCIAL,
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val TAG = "DiscordConnectService"
        private const val NOTIFICATION_ID = 100
        private const val CONNECTION_TIMEOUT_MILLIS = 10_000L

        fun launch(context: Context, channelId: Long? = null) {
            val uri = Uri.parse("manticord://app/connect${channelId?.let { "/$it" }.orEmpty()}")
            val intent = Intent(context, DiscordConnectService::class.java).setData(uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
