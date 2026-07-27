package com.discord.utilities.accessibility

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.annotation.StringRes
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.discord.stores.StoreStream

/**
 * The three accessibility affordances the reference uses from ordinary UI code.
 *
 * Reference: `com.discord.utilities.accessibility.AccessibilityUtils`.
 */
object AccessibilityUtils {

    /** Whether the user (or the system) asked for reduced motion; gates every decorative animation. */
    fun isReducedMotionEnabled(): Boolean =
        StoreStream.getAccessibility().isReducedMotionEnabled()

    /** [sendAnnouncement] with a string resource. */
    fun sendAnnouncement(context: Context, @StringRes message: Int) {
        sendAnnouncement(context, context.getString(message))
    }

    /**
     * Speak [message] through the screen reader without moving focus.
     *
     * Used for outcomes that have no visible focus target — "this channel is not accessible",
     * "message deleted" — which a screen-reader user would otherwise never hear.
     *
     * `sendAccessibilityEvent` throws `IllegalStateException` when accessibility is switched off
     * between the enabled check and the send; the reference swallows it, and so does this.
     */
    fun sendAnnouncement(context: Context, message: String) {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return
        @Suppress("DEPRECATION")
        val event = AccessibilityEvent.obtain()
        event.eventType = AccessibilityEvent.TYPE_ANNOUNCEMENT
        event.text.add(message)
        try {
            manager.sendAccessibilityEvent(event)
        } catch (_: IllegalStateException) {
            // Accessibility was disabled while the event was being built.
        }
    }

    /** Mark [view] as a heading so screen readers can navigate by heading. */
    fun setViewIsHeading(view: View) {
        ViewCompat.setAccessibilityDelegate(
            view,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.isHeading = true
                }
            },
        )
    }
}
