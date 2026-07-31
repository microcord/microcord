package com.discord.utilities.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.IntentService
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.discord.app.AppActivity
import com.discord.app.AppComponent
import com.discord.app.AppLog
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.extensions.immutablePendingIntentFlag
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.stage.StageChannelAPI
import com.discord.widgets.stage.StageChannelNotifications
import rx.subjects.PublishSubject
import rx.subjects.Subject

@Suppress("DEPRECATION")
class VoiceEngineForegroundService : IntentService("VoiceEngineForegroundService"), AppComponent {
    override val unsubscribeSignal: Subject<Void, Void> = PublishSubject.create()

    private val binder = LocalBinder(this)
    private val ringManager = CallSoundManager(this)
    private var screenShareManager: ScreenShareManager? = null
    private var wakeLockPartial: PowerManager.WakeLock? = null
    private var wakeLockProximity: PowerManager.WakeLock? = null
    private var wakeLockWifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        AppLog.v(LOG_TAG, "Service created.")

        wakeLockWifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WAKE_LOCK_TAG)
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
        wakeLockPartial = createWakeLock(PowerManager.PARTIAL_WAKE_LOCK)?.apply {
            acquire(WAKELOCK_TIMEOUT)
        }
        wakeLockProximity = createWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)?.apply {
            acquire(WAKELOCK_TIMEOUT)
        }
    }

    override fun onDestroy() {
        AppLog.v(LOG_TAG, "Service destroyed.")
        StageChannelNotifications.INSTANCE.onInviteToSpeakRescinded()
        unsubscribeSignal.onNext(null)
        screenShareManager?.release()
        screenShareManager = null
        release(wakeLockWifi)
        release(wakeLockPartial)
        release(wakeLockProximity)
        wakeLockWifi = null
        wakeLockPartial = null
        wakeLockProximity = null
        super.onDestroy()
    }

    @SuppressLint("ThreadConstraint", "WrongThread")
    override fun onHandleIntent(intent: Intent?) {
        val action = intent?.action ?: return
        AppLog.v(LOG_TAG, "Received action: $action")
        when (action) {
            ACTION.TOGGLE_MUTED -> onToggleSelfMute()
            ACTION.TOGGLE_DEAFENED -> onToggleSelfDeafen()
            ACTION.STOP_SERVICE -> {
                stopForeground(true)
                stopSelf()
            }
            ACTION.DISCONNECT -> onDisconnect()
            ACTION.START_FOREGROUND -> handleStartForeground(intent)
            ACTION.START_STREAM -> {
                val permissionIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                if (permissionIntent != null) screenShareManager?.startStream(permissionIntent)
            }
            ACTION.STOP_STREAM -> screenShareManager?.stopStream()
            ACTION.STAGE_INVITE_ACCEPT -> ackStageInvite(intent, true)
            ACTION.STAGE_INVITE_DECLINE -> ackStageInvite(intent, false)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        unsubscribeSignal.onNext(null)
        return super.onUnbind(intent)
    }

    // IntentService invokes this on its worker; the reference promotes the service on that thread.
    @SuppressLint("ThreadConstraint")
    private fun handleStartForeground(intent: Intent) {
        setProximityLock(intent.getBooleanExtra(EXTRA.PROXIMITY_LOCK_ENABLED, false))

        val channelId = intent.getLongExtra(EXTRA.CHANNEL_ID, 0L)
        val guildId = intent.extras?.get(EXTRA.GUILD_ID) as? Long
        ringManager.subscribeToStoreState(channelId)
        if (screenShareManager?.channelId != channelId || screenShareManager?.guildId != guildId) {
            screenShareManager?.release()
            screenShareManager = ScreenShareManager(this, channelId, guildId)
        }

        startForeground(
            NOTIFICATION_ID,
            VoiceEngineNotificationBuilder.buildNotification(
                context = this,
                actionMain = ACTION.MAIN_ACTION,
                actionDisconnect = ACTION.DISCONNECT,
                actionStopStream = ACTION.STOP_STREAM,
                actionToggleMuted = ACTION.TOGGLE_MUTED.takeIf {
                    intent.getBooleanExtra(EXTRA.ITEM_CAN_SPEAK, true)
                },
                actionToggleDeafened = ACTION.TOGGLE_DEAFENED,
                notificationServiceClass = VoiceEngineForegroundService::class.java,
                notificationChannel = NOTIFICATION_CHANNEL,
                notificationChannelId = channelId,
                notificationTitle = intent.getCharSequenceExtra(EXTRA.TITLE),
                notificationSubtitle = intent.getCharSequenceExtra(EXTRA.TITLE_SUBTEXT),
                notificationClass = AppActivity.Call::class.java,
                isStreaming = intent.getBooleanExtra(EXTRA.ITEM_STREAMING, false),
                isMuted = intent.getBooleanExtra(EXTRA.ITEM_MUTED, false),
                isDeafened = intent.getBooleanExtra(EXTRA.ITEM_DEAFENED, false),
            ),
        )
    }

    private fun ackStageInvite(intent: Intent, accept: Boolean) {
        val channelId = intent.getLongExtra(EXTRA.CHANNEL_ID, 0L)
        val request = StageChannelAPI.ackInvitationToSpeak(channelId, accept)
        if (request == null) {
            AppLog.w(LOG_TAG, "Unable to ack stage invite for unknown channel $channelId")
            return
        }
        request.restSubscribeOn().ui().appSubscribe(
            errorClass = VoiceEngineForegroundService::class.java,
            errorHandler = { StageChannelNotifications.INSTANCE.onInvitedToSpeakAckFailed(channelId) },
            onCompleted = {
                if (accept) {
                    AnalyticsTracker.tracker.track(
                        "promoted_to_speaker",
                        mapOf("channel_id" to channelId),
                    )
                }
                StageChannelNotifications.Notifications.InvitedToSpeak.cancel(this)
            },
            onNext = {},
        )
    }

    private fun createWakeLock(level: Int): PowerManager.WakeLock? {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        if (!powerManager.isWakeLockLevelSupported(level)) return null
        return powerManager.newWakeLock(level, WAKE_LOCK_TAG).apply { setReferenceCounted(false) }
    }

    private fun setProximityLock(enabled: Boolean) {
        wakeLockProximity?.let { lock ->
            if (enabled && !lock.isHeld) lock.acquire(WAKELOCK_TIMEOUT)
            if (!enabled && lock.isHeld) lock.release()
        }
    }

    private fun release(lock: PowerManager.WakeLock?) {
        if (lock?.isHeld == true) lock.release()
    }

    private fun release(lock: WifiManager.WifiLock?) {
        if (lock?.isHeld == true) lock.release()
    }

    class Connection(val context: Context) {
        val connection: ServiceConnection = object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, binder: IBinder) {
                service = (binder as LocalBinder).service
                isUnbinding = false
            }

            override fun onServiceDisconnected(className: ComponentName) {
                isUnbinding = false
                service = null
            }
        }

        @get:Synchronized
        @set:Synchronized
        var isUnbinding: Boolean = false

        var service: VoiceEngineForegroundService? = null
            private set
    }

    private class LocalBinder(val service: VoiceEngineForegroundService) : Binder()

    interface ACTION {
        companion object {
            const val DISCONNECT = "com.discord.utilities.voice.action.disconnect"
            const val MAIN_ACTION = "com.discord.utilities.voice.action.main"
            const val STAGE_INVITE_ACCEPT = "com.discord.utilities.voice.action.stage_invite_accept"
            const val STAGE_INVITE_DECLINE = "com.discord.utilities.voice.action.stage_invite_decline"
            const val START_FOREGROUND = "com.discord.utilities.voice.action.start_foreground"
            const val START_STREAM = "com.discord.utilities.voice.action.start_stream"
            const val STOP_SERVICE = "com.discord.utilities.voice.action.stop"
            const val STOP_STREAM = "com.discord.utilities.voice.action.stop_stream"
            const val TOGGLE_DEAFENED = "com.discord.utilities.voice.action.toggle_deafened"
            const val TOGGLE_MUTED = "com.discord.utilities.voice.action.toggle_muted"
        }
    }

    private interface EXTRA {
        companion object {
            const val CHANNEL_ID = "com.discord.utilities.voice.extra.channel_id"
            const val GUILD_ID = "com.discord.utilities.voice.extra.guild_id"
            const val ITEM_CAN_SPEAK = "com.discord.utilities.voice.extra.item_can_speak"
            const val ITEM_DEAFENED = "com.discord.utilities.voice.extra.item_deafened"
            const val ITEM_MUTED = "com.discord.utilities.voice.extra.item_muted"
            const val ITEM_STREAMING = "com.discord.utilities.voice.extra.item_streaming"
            const val PROXIMITY_LOCK_ENABLED = "com.discord.utilities.voice.extra.proximity_lock_enabled"
            const val TITLE = "com.discord.utilities.voice.extra.title"
            const val TITLE_SUBTEXT = "com.discord.utilities.voice.extra.title_subtext"
        }
    }

    companion object {
        private const val LOG_TAG = "DiscordVoiceService"
        private const val NOTIFICATION_CHANNEL = "Media Connections"
        private const val NOTIFICATION_ID = 101
        private const val WAKELOCK_TIMEOUT = 7_200_000L
        private const val WAKE_LOCK_TAG = "discord:com.discord.utils"

        var onDisconnect: () -> Unit = { VoiceEngineServiceController.INSTANCE.onDisconnect() }
        var onToggleSelfDeafen: () -> Unit = { VoiceEngineServiceController.INSTANCE.onToggleSelfDeafen() }
        var onToggleSelfMute: () -> Unit = { VoiceEngineServiceController.INSTANCE.onToggleSelfMute() }

        fun stageInviteAckIntent(context: Context, channelId: Long, accept: Boolean): Intent {
            val canRecord = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            return if (accept && !canRecord) {
                Intent(context, AppActivity.Call::class.java)
                    .putExtra("com.discord.intent.extra.EXTRA_CHANNEL_ID", channelId)
            } else {
                Intent(context, VoiceEngineForegroundService::class.java)
                    .setAction(if (accept) ACTION.STAGE_INVITE_ACCEPT else ACTION.STAGE_INVITE_DECLINE)
                    .putExtra(EXTRA.CHANNEL_ID, channelId)
            }
        }

        fun stageInviteAckPendingIntent(context: Context, channelId: Long, accept: Boolean): PendingIntent {
            val intent = stageInviteAckIntent(context, channelId, accept)
            val flags = immutablePendingIntentFlag(PendingIntent.FLAG_UPDATE_CURRENT)
            return if (intent.component?.className == AppActivity.Call::class.java.name) {
                PendingIntent.getActivity(context, 0, intent, flags)
            } else {
                PendingIntent.getService(context, 0, intent, flags)
            }
        }

        fun startForegroundAndBind(
            connection: Connection,
            title: CharSequence,
            subtitle: CharSequence,
            selfMute: Boolean,
            selfDeafen: Boolean,
            selfStream: Boolean,
            channelId: Long,
            guildId: Long?,
            isProximityLockEnabled: Boolean,
            canSpeak: Boolean,
        ) {
            try {
                AppLog.v(LOG_TAG, "Bind service connection.")
                val intent = Intent(connection.context, VoiceEngineForegroundService::class.java)
                    .setAction(ACTION.START_FOREGROUND)
                    .putExtra(EXTRA.TITLE, title)
                    .putExtra(EXTRA.TITLE_SUBTEXT, subtitle)
                    .putExtra(EXTRA.ITEM_MUTED, selfMute)
                    .putExtra(EXTRA.ITEM_DEAFENED, selfDeafen)
                    .putExtra(EXTRA.ITEM_STREAMING, selfStream)
                    .putExtra(EXTRA.ITEM_CAN_SPEAK, canSpeak)
                    .putExtra(EXTRA.PROXIMITY_LOCK_ENABLED, isProximityLockEnabled)
                    .putExtra(EXTRA.CHANNEL_ID, channelId)
                    .putExtra(EXTRA.GUILD_ID, guildId)
                ContextCompat.startForegroundService(connection.context, intent)
                connection.context.bindService(
                    Intent(connection.context, VoiceEngineForegroundService::class.java),
                    connection.connection,
                    Context.BIND_AUTO_CREATE,
                )
            } catch (exception: Exception) {
                AppLog.v(LOG_TAG, "Unable to bind service connection.", exception)
            }
        }

        fun startStream(connection: Connection, permissionIntent: Intent) {
            connection.context.startService(
                Intent(connection.context, VoiceEngineForegroundService::class.java)
                    .setAction(ACTION.START_STREAM)
                    .putExtra(Intent.EXTRA_INTENT, permissionIntent),
            )
        }

        fun stopForegroundAndUnbind(connection: Connection) {
            try {
                if (connection.service == null || connection.isUnbinding) return
                AppLog.v(LOG_TAG, "Unbind service connection.")
                connection.isUnbinding = true
                connection.context.startService(
                    Intent(connection.context, VoiceEngineForegroundService::class.java)
                        .setAction(ACTION.STOP_SERVICE),
                )
                connection.context.unbindService(connection.connection)
            } catch (exception: Exception) {
                AppLog.v(LOG_TAG, "Unable to unbind service connection.", exception)
            }
        }

        fun stopStream(connection: Connection) {
            connection.context.startService(
                Intent(connection.context, VoiceEngineForegroundService::class.java)
                    .setAction(ACTION.STOP_STREAM),
            )
        }
    }
}
