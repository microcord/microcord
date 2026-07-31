package com.discord.utilities.voice

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.discord.R
import com.discord.app.AppLog
import com.discord.overlay.OverlayService
import com.discord.overlay.OverlayVoiceBubble
import com.discord.overlay.OverlayVoiceSelectorBubbleDialog
import com.discord.overlay.views.OverlayBubbleWrap
import com.discord.rtcconnection.RtcConnection
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.extensions.immutablePendingIntentFlag
import com.discord.utilities.fcm.NotificationClient
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.takeSingleUntilTimeout
import com.discord.utilities.rx.ui
import com.discord.views.OverlayMenuBubbleDialog
import java.lang.ref.WeakReference

class DiscordOverlayService : OverlayService() {
    private fun createMenu(): OverlayMenuBubbleDialog =
        OverlayMenuBubbleDialog(applicationContext).apply {
            insetMargins.top = resources.getDimensionPixelOffset(R.dimen.vertical_safe_margin)
            onDialogClosed = { overlayManager.removeBubble(it) }
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
        ACTION_OPEN -> voiceBubble()?.let { existing ->
            existing.performClick()
            null
        } ?: createVoiceBubble()
        ACTION_SELECTOR -> createVoiceSelector()
        else -> null
    }

    override fun onCreate() {
        super.onCreate()
        StoreStream.initialize(application)
        overlayManager.onOverlayBubbleAdded = { view ->
            if (view is OverlayVoiceBubble) StoreStream.getAnalytics().onOverlayVoiceEvent(true)
        }
        overlayManager.onOverlayBubbleRemoved = { view ->
            if (view is OverlayVoiceBubble) StoreStream.getAnalytics().onOverlayVoiceEvent(false)
            if (overlayManager.activeBubbles.isEmpty()) launchForClose(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.v(LOG_TAG, "onStartCommand: ${intent?.action}")
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
        } catch (failure: Exception) {
            AppLog.e(LOG_TAG, "Overlay failed to handle a request.", failure)
            launchForClose(this)
        }
        return START_REDELIVER_INTENT
    }

    private fun voiceBubble(): OverlayVoiceBubble? =
        overlayManager.activeBubbles.filterIsInstance<OverlayVoiceBubble>().firstOrNull()

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

        private fun createOverlayIntent(context: Context, action: String) =
            Intent(action, null, context, DiscordOverlayService::class.java)

        private fun tryStartOverlayService(context: Context, action: String, checkEnabled: Boolean = true) {
            if (checkEnabled && !StoreStream.getUserSettings().getIsMobileOverlayEnabled()) return
            try {
                context.startService(createOverlayIntent(context, action))
            } catch (failure: Exception) {
                AppLog.v(LOG_TAG, "Overlay request failed.", failure)
            }
        }

        @JvmStatic
        fun launchForClose(context: Context) =
            tryStartOverlayService(context, ACTION_CLOSE, checkEnabled = false)

        @JvmStatic
        fun launchForConnect(context: Context) {
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

        @JvmStatic
        fun launchForVoice(context: Context) = tryStartOverlayService(context, ACTION_VOICE)

        @JvmStatic
        fun launchForVoiceChannelSelect(context: Context) =
            tryStartOverlayService(context, ACTION_SELECTOR)

        internal fun actionForConnectionState(stateChange: RtcConnection.StateChange): String =
            if (stateChange.state == RtcConnection.State.Disconnected(false)) ACTION_SELECTOR else ACTION_OPEN
    }
}
