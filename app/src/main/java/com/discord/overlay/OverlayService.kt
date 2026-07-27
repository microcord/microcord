package com.discord.overlay

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.discord.overlay.views.OverlayBubbleWrap
import com.discord.overlay.views.TrashWrap

/**
 * Foreground-service host for an [OverlayManager].
 *
 * Overlay windows survive the app being backgrounded, which on Android means something has to keep
 * the process alive and visible in the shade — hence a foreground service. Subclasses supply the two
 * app-specific pieces: the ongoing [Notification] and the bubble an incoming [Intent] asks for.
 */
abstract class OverlayService : Service() {

    /** Created in [onCreate]; owns every window this service put on screen. */
    lateinit var overlayManager: OverlayManager

    /** The ongoing notification shown while any bubble is up. */
    abstract fun createNotification(intent: Intent): Notification

    /**
     * The bubble [intent] is asking for, or `null` when the request should be ignored — for example
     * because the bubble it names is already on screen.
     */
    abstract fun createOverlayBubble(intent: Intent): OverlayBubbleWrap?

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlayManager = OverlayManager(applicationContext)

        overlayManager.trashEventListener = object : TrashEventListener {
            override fun onBubbleInTrashZoneChanged(bubble: OverlayBubbleWrap?) = Unit

            override fun onBubbleDismissed(bubble: OverlayBubbleWrap) {
                overlayManager.removeBubble(bubble)
            }
        }

        // NOTE: the reference leaves this as an unimplemented stub, so a host that does not replace
        // it crashes the moment its last bubble is dismissed. Every real host overrides this in its
        // own `onCreate` *after* calling `super.onCreate()`; reproduced rather than papered over so
        // the omission fails loudly instead of leaking a foreground service.
        overlayManager.onOverlayBubbleRemoved = {
            if (overlayManager.activeBubbles.isEmpty()) {
                TODO("handle stop service")
            }
        }
    }

    /**
     * Entry point subclasses call from `onStartCommand`: attaches the bubble [intent] asks for and,
     * if one was attached, promotes the service to the foreground.
     *
     * A `null` intent — which is what a restarted sticky service receives — is treated as an empty
     * one so subclasses only have to handle "no action".
     */
    fun handleStart(intent: Intent?) {
        val startIntent = intent ?: Intent()
        if (attachBubbleToWindow(startIntent)) {
            val notification = createNotification(startIntent)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    override fun onDestroy() {
        overlayManager.close()
        super.onDestroy()
    }

    private fun attachBubbleToWindow(intent: Intent): Boolean {
        val bubble = createOverlayBubble(intent) ?: return false
        overlayManager.addBubble(bubble)
        // The bubble has no size until it has been through a layout pass, so the initial snap to
        // the screen edge has to wait for one.
        bubble.post { overlayManager.springBubbleToEdge(bubble) }

        if (overlayManager.trashWrap == null) {
            overlayManager.setTrashWrap(TrashWrap(applicationContext))
        }
        return true
    }

    companion object {
        /** Notification id of the ongoing overlay notification. */
        const val NOTIFICATION_ID = 5858
    }
}
