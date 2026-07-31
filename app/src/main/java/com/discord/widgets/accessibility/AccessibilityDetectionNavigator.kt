package com.discord.widgets.accessibility

import com.discord.app.AppComponent
import com.discord.dialogs.WidgetAccessibilityDetectionDialog
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import com.discord.utilities.accessibility.AccessibilityFeatureFlags
import com.discord.utilities.accessibility.AccessibilityMonitor
import com.discord.utilities.accessibility.AccessibilityState
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui

/**
 * Asks the user, once, whether Discord may report that a screen reader is in use.
 *
 * `AccessibilityFeatureFlags.SCREENREADER` is only ever sent to the backend with consent, so until
 * this prompt is answered `StoreAccessibility.updateDetectionAllowed` stays at its synced default
 * and the flag is stripped. That is why the navigator exists at all: without it the detection
 * setting can never be turned on from the device.
 *
 * The prompt is enqueued rather than shown, because a screen reader can be enabled at any moment —
 * including mid-animation or while a dialog is already up. The notice is **persistent**: it must
 * survive a process death and be asked exactly once, not once per launch.
 */
object AccessibilityDetectionNavigator {

    private const val NOTICE_NAME = "ALLOW_ACCESSIBILITY_DETECTION_DIALOG"

    /** Watches [AccessibilityMonitor] for as long as [appComponent] lives. */
    fun enqueueNoticeWhenEnabled(appComponent: AppComponent) {
        AccessibilityMonitor.INSTANCE.observeAccessibilityState()
            .ui(appComponent)
            .appSubscribe(errorClass = AccessibilityDetectionNavigator::class.java) {
                showAccessibilityDetectionDialog(it)
            }
    }

    private fun showAccessibilityDetectionDialog(accessibilityState: AccessibilityState) {
        if (!accessibilityState.features.contains(AccessibilityFeatureFlags.SCREENREADER)) return
        StoreStream.getNotices().requestToShow(
            StoreNotices.Notice(
                name = NOTICE_NAME,
                priority = StoreNotices.PRIORITY_HIGH,
                persist = true,
            ) { activity ->
                WidgetAccessibilityDetectionDialog.show(activity.supportFragmentManager)
                true
            },
        )
    }
}
