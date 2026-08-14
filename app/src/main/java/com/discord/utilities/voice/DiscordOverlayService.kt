package com.discord.utilities.voice

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.app.AppLog
import com.discord.overlay.OverlayService
import com.discord.overlay.OverlayVoiceBubble
import com.discord.overlay.OverlayVoiceSelectorBubbleDialog
import com.discord.overlay.views.OverlayBubbleWrap
import com.discord.rtcconnection.RtcConnection
import com.discord.app.AppToast
import com.discord.utilities.intent.IntentUtils
import com.discord.models.invite.InviteUtils
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.extensions.immutablePendingIntentFlag
import com.discord.utilities.fcm.NotificationClient
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.takeSingleUntilTimeout
import com.discord.utilities.rx.ui
import com.discord.rtcconnection.audio.DiscordAudioManager
import com.discord.views.OverlayMenuView
import rx.Observable
import com.discord.views.OverlayMenuBubbleDialog
import java.lang.ref.WeakReference

class DiscordOverlayService : OverlayService() {
    private fun createMenu(): OverlayMenuBubbleDialog =
        OverlayMenuBubbleDialog(applicationContext).apply {
            insetMargins.top = resources.getDimensionPixelOffset(R.dimen.vertical_safe_margin)
            onDialogClosed = { overlayManager.removeBubble(it) }
            overlayMenu.viewStateSource = overlayMenuState()
            overlayMenu.setOnInviteRequested(::copyInviteLink)
            overlayMenu.setOnSwitchChannelsRequested {
                launchForVoiceChannelSelect(applicationContext)
            }
            overlayMenu.setOnOpenAppRequested(::openApp)
            overlayMenu.setOnSourceToggleRequested(::cycleAudioOutput)
            overlayMenu.setOnMuteToggleRequested { StoreStream.getMediaSettings().toggleSelfMuted() }
            overlayMenu.setOnDisconnectRequested {
                StoreStream.getVoiceChannelSelected().selectVoiceChannel(0L).subscribe({}, {})
            }
        }

    /**
     * Call state for the menu. The view cannot assemble this itself: it has no fragment lifecycle
     * and the voice stores only exist once a call is up, so the service feeds it.
     */
    private fun overlayMenuState(): Observable<OverlayMenuView.ViewState?> =
        StoreStream.getVoiceChannelSelected().observeSelectedVoiceChannelId().switchMap { channelId ->
            if (channelId == 0L) {
                Observable.just(null)
            } else {
                Observable.combineLatest(
                    StoreStream.getChannels().observeChannel(channelId),
                    StoreStream.getPermissions().observePermissionsForChannel(channelId),
                    StoreStream.getAudioManagerV2().observeAudioManagerState(),
                    StoreStream.getMediaSettings().isSelfMuted(),
                    StoreStream.getRtcConnection().observeQuality(),
                ) { channel, permissions, audio, muted, quality ->
                    channel?.let {
                        OverlayMenuView.ViewState(
                            channelId = channelId,
                            channelPermissions = permissions,
                            guildName = StoreStream.getGuilds().getGuild(it.guildId)?.name,
                            channelName = it.name.orEmpty(),
                            quality = when (quality) {
                                RtcConnection.Quality.BAD -> OverlayMenuView.Quality.BAD
                                RtcConnection.Quality.AVERAGE -> OverlayMenuView.Quality.AVERAGE
                                RtcConnection.Quality.FINE -> OverlayMenuView.Quality.FINE
                                else -> OverlayMenuView.Quality.UNKNOWN
                            },
                            speakerphoneActive =
                                audio.activeAudioDevice == DiscordAudioManager.DeviceTypes.SPEAKERPHONE,
                            eligibleAudioDeviceCount = audio.audioDevices.count { device -> device.isAvailable },
                            mutedByAnySource = muted,
                        )
                    }
                }
            }
        }

    /** The overlay has no activity to host the invite sheet, so the link goes to the clipboard. */
    private fun copyInviteLink(channelId: Long) {
        StoreStream.getInviteSettings()
            .generateInvite(channelId)
            .ui()
            .appSubscribe(DiscordOverlayService::class.java) { invite ->
                AppToast.copyToClipboard(
                    applicationContext,
                    invite.toLink(resources, InviteUtils.HOST_INVITE),
                    R.string.invite_link_copied,
                )
            }
    }

    private fun openApp(channelId: Long) {
        val channel = StoreStream.getChannels().findChannelById(channelId)
        startActivity(
            IntentUtils.RouteBuilders.selectChannel(channelId, channel?.guildId ?: 0L)
                .setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Steps to the next available output, which is what the single toggle button offers. */
    private fun cycleAudioOutput() {
        val audio = StoreStream.getAudioManagerV2()
        audio.observeAudioManagerState()
            .take(1)
            .appSubscribe(DiscordOverlayService::class.java) { state ->
                val available = state.audioDevices.filter { it.isAvailable }
                if (available.size < 2) return@appSubscribe
                val current = available.indexOfFirst { it.type == state.activeAudioDevice }
                audio.selectOutputDevice(available[(current + 1) % available.size].type)
            }
    }

    private fun createVoiceBubble(): OverlayVoiceBubble =
        OverlayVoiceBubble(applicationContext).apply {
            val margin = resources.getDimensionPixelOffset(R.dimen.overlay_safe_margin)
            insetMargins.set(-margin, margin, -margin, margin)
            overlayManager.setMenuBubbleOnClick(this, VOICE_BUBBLE_ANCHOR_TAG) { source ->
                createMenu().apply {
                    val imageMargins = (source as OverlayVoiceBubble).imageView.layoutParams
                        as? ViewGroup.MarginLayoutParams
                    linkedAnchorView.layoutParams.apply {
                        width = source.width - (imageMargins?.leftMargin ?: 0) -
                            (imageMargins?.rightMargin ?: 0)
                        height = source.height
                    }
                    linkedAnchorView.requestLayout()
                    linkedAnchorView.tag = VOICE_BUBBLE_ANCHOR_TAG
                }
            }
            overlayManager.attachBubbleGestures(this)
        }

    private fun createVoiceSelector(): OverlayVoiceSelectorBubbleDialog =
        OverlayVoiceSelectorBubbleDialog(applicationContext).apply {
            onDialogClosed = { overlayManager.removeBubble(it) }
        }

    override fun createNotification(intent: Intent): Notification {
        val closeIntent = PendingIntent.getService(
            this,
            CLOSE_INTENT_REQ_CODE,
            createOverlayIntent(this, ACTION_CLOSE),
            immutablePendingIntentFlag(PendingIntent.FLAG_CANCEL_CURRENT),
        )
        val settingsIntent = PendingIntent.getActivity(
            this,
            CLOSE_INTENT_REQ_CODE,
            Intent(Intent.ACTION_VIEW, Uri.parse("discord://app/settings/voice")).setPackage(packageName),
            immutablePendingIntentFlag(PendingIntent.FLAG_CANCEL_CURRENT),
        )
        return NotificationCompat.Builder(this, NotificationClient.NOTIF_CHANNEL_MEDIA_CONNECTIONS)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setSmallIcon(R.drawable.ic_notification_24dp)
            .setColor(ColorCompat.getColor(this, R.color.status_green_600))
            .setContentTitle(getString(R.string.overlay))
            .setContentText(getString(R.string.overlay_mobile_toggle_desc))
            .setOngoing(true)
            .addAction(R.drawable.ic_close_grey_24dp, getString(R.string.close), closeIntent)
            .addAction(R.drawable.ic_settings_grey_a60_24dp, getString(R.string.settings), settingsIntent)
            .build()
    }

    override fun createOverlayBubble(intent: Intent): OverlayBubbleWrap? = when (intent.action) {
        ACTION_VOICE -> if (voiceBubble() == null) createVoiceBubble() else null
        // Not `?.let { … ; null } ?: create()`: the let returns null, so the elvis fires even when a
        // bubble was found and the tap both opens the menu and spawns a duplicate.
        ACTION_OPEN -> when (val existing = voiceBubble()) {
            null -> createVoiceBubble()
            else -> {
                existing.performClick()
                null
            }
        }
        ACTION_SELECTOR -> if (selectorBubble() == null) createVoiceSelector() else null
        else -> null
    }

    override fun onCreate() {
        super.onCreate()
        StoreStream.initialize(application)
        overlayManager.onOverlayBubbleAdded = { view ->
            if (view is OverlayVoiceBubble) StoreStream.getAnalytics().onOverlayVoiceEvent(true)
        }
        overlayManager.onOverlayBubbleRemoved = { view ->
            if (view is OverlayVoiceBubble) {
                StoreStream.getAnalytics().onOverlayVoiceEvent(false)
                dismissedThisCall = true
            }
            if (overlayManager.activeBubbles.isEmpty()) launchForClose(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.v(LOG_TAG, "onStartCommand: ${intent?.action}")
        // A startForegroundService start must be promoted before anything else, including every
        // path that bails out below; the system kills the process otherwise.
        promoteToForeground(intent ?: Intent())
        if (intent?.action == ACTION_CLOSE || !canDrawOverlays()) {
            stopForeground(true)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!StoreStream.getUserSettings().getIsMobileOverlayEnabled()) {
            stopForeground(true)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            handleStart(intent)
            if (overlayManager.activeBubbles.isEmpty()) {
                stopForeground(true)
                stopSelf(startId)
            }
        } catch (failure: Exception) {
            AppLog.e(LOG_TAG, "Overlay failed to handle a request.", failure)
            launchForClose(this)
        }
        return START_REDELIVER_INTENT
    }

    private fun promoteToForeground(intent: Intent) {
        val notification = createNotification(intent)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun voiceBubble(): OverlayVoiceBubble? =
        overlayManager.activeBubbles.filterIsInstance<OverlayVoiceBubble>().firstOrNull()

    private fun selectorBubble(): OverlayVoiceSelectorBubbleDialog? =
        overlayManager.activeBubbles.filterIsInstance<OverlayVoiceSelectorBubbleDialog>().firstOrNull()

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    companion object {
        internal const val ACTION_CLOSE = "com.discord.actions.OVERLAY_CLOSE"
        internal const val ACTION_OPEN = "com.discord.actions.OVERLAY_OPEN"
        internal const val ACTION_SELECTOR = "com.discord.actions.OVERLAY_SELECTOR"
        internal const val ACTION_VOICE = "com.discord.actions.OVERLAY_VOICE"
        private const val CLOSE_INTENT_REQ_CODE = 1010
        private const val LOG_TAG = "OverlayService"
        private const val VOICE_BUBBLE_ANCHOR_TAG = "Active Voice Bubble"

        @Volatile
        private var dismissedThisCall = false

        private fun createOverlayIntent(context: Context, action: String) =
            Intent(action, null, context, DiscordOverlayService::class.java)

        private fun tryStartOverlayService(context: Context, action: String, checkEnabled: Boolean = true) {
            if (checkEnabled && !StoreStream.getUserSettings().getIsMobileOverlayEnabled()) return
            try {
                // startService is refused once the app is backgrounded, which is exactly when the
                // bubble is wanted; the service promotes itself in handleStart.
                ContextCompat.startForegroundService(context, createOverlayIntent(context, action))
            } catch (failure: Exception) {
                AppLog.v(LOG_TAG, "Overlay request failed.", failure)
            }
        }

        @JvmStatic
        fun launchForClose(context: Context) {
            try {
                context.stopService(createOverlayIntent(context, ACTION_CLOSE))
            } catch (failure: Exception) {
                AppLog.v(LOG_TAG, "Overlay close failed.", failure)
            }
        }

        @JvmStatic
        fun launchForConnect(context: Context) {
            dismissedThisCall = false
            if (!StoreStream.getUserSettings().getIsMobileOverlayEnabled()) {
                Toast.makeText(context, R.string.overlay_mobile_required, Toast.LENGTH_SHORT).show()
                return
            }
            val contextRef = WeakReference(context)
            StoreStream.getRtcConnection().getConnectionState()
                .takeSingleUntilTimeout(1_000L, emitError = false)
                .map(::actionForConnectionState)
                .ui()
                .appSubscribe(DiscordOverlayService::class.java, context) { action ->
                    contextRef.get()?.let { tryStartOverlayService(it, action) }
                }
        }

        /** Dismissing the bubble applies to the whole call; only [resetDismissal] brings it back. */
        @JvmStatic
        fun launchForVoice(context: Context) {
            if (dismissedThisCall) {
                AppLog.v(LOG_TAG, "Overlay suppressed: dismissed for this call.")
                return
            }
            tryStartOverlayService(context, ACTION_VOICE)
        }

        /** Clears the dismissal, so the next call starts with the bubble available again. */
        @JvmStatic
        fun resetDismissal() {
            dismissedThisCall = false
        }

        @JvmStatic
        fun launchForVoiceChannelSelect(context: Context) =
            tryStartOverlayService(context, ACTION_SELECTOR)

        internal fun actionForConnectionState(stateChange: RtcConnection.StateChange): String =
            if (stateChange.state == RtcConnection.State.Disconnected(false)) ACTION_SELECTOR else ACTION_OPEN
    }
}
