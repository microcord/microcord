package com.discord.widgets.notice

import android.view.View
import com.discord.R
import com.discord.app.AppDialog
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import com.discord.widgets.settings.WidgetSettingsVoice
import com.google.android.material.button.MaterialButton

/**
 * One-time "you can put Discord on top of your game" prompt for the voice overlay.
 *
 * Declining is a real setting change, not just a dismissal: the reference turns the mobile overlay
 * off so the prompt's own feature stays disabled, and the notice queue never offers it again.
 */
class WidgetNoticeNuxOverlay : AppDialog(R.layout.widget_notice_nux_overlay) {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        StoreStream.getNotices().markDialogSeen(NOTICE_NAME)

        view.findViewById<MaterialButton>(R.id.enable_button).setOnClickListener {
            WidgetSettingsVoice.launch(
                it.context,
                R.id.settings_voice_overlay_toggle,
                isTargetAutoToggle = true,
            )
            dismiss()
        }
        view.findViewById<MaterialButton>(R.id.cancel_button).setOnClickListener {
            StoreStream.getUserSettings().setIsMobileOverlayEnabled(false)
            dismiss()
        }
    }

    companion object {
        /** Notice-queue identity; must match the name the store dequeues. */
        const val NOTICE_NAME = "NUX/Overlay"

        @JvmStatic
        fun enqueue() {
            StoreStream.getNotices().requestToShow(
                StoreNotices.Notice(
                    name = NOTICE_NAME,
                    priority = 0,
                    persist = true,
                    show = { activity ->
                        WidgetNoticeNuxOverlay().show(activity.supportFragmentManager, NOTICE_NAME)
                        true
                    },
                ),
            )
        }
    }
}
