package com.discord.widgets.notice

import android.content.Context
import android.view.View
import com.discord.R
import com.discord.app.AppDialog
import com.discord.stores.StoreStream
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
            openOverlaySetting?.invoke(it.context)
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

        /**
         * Opens voice settings scrolled to the overlay toggle.
         *
         * The reference calls `WidgetSettingsVoice.launch(context, R.id.settings_voice_overlay_toggle,
         * scrollTo = true)`. That screen does not exist in this project yet, so the navigation is
         * injected; while it is unset the enable button only closes the dialog.
         */
        @JvmStatic
        var openOverlaySetting: ((Context) -> Unit)? = null
    }
}
