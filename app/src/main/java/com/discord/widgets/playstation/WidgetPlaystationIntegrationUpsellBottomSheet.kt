package com.discord.widgets.playstation

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.i18n.i18nFormat
import com.discord.stores.StoreStream
import com.discord.utilities.platform.Platform
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton

/**
 * "Connect PlayStation Network" upsell.
 *
 * Confirming hands off to [com.discord.stores.StoreUserConnections.authorizeConnection], which
 * opens the OAuth page in a browser; the sheet closes immediately either way. Dismissal is
 * reported through [onDismissed] whichever route it takes — the scrim, the cancel button or a
 * swipe — because the reference measures the dismissal rate of this specific prompt.
 */
class WidgetPlaystationIntegrationUpsellBottomSheet : AppBottomSheet() {

    override fun getContentViewResId(): Int = R.layout.widget_playstation_upsell_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val header: TextView = view.findViewById(R.id.upsell_header)
        val confirmButton: MaterialButton = view.findViewById(R.id.confirm_button)
        val cancelButton: MaterialButton = view.findViewById(R.id.cancel_button)

        header.text = requireContext().i18nFormat(
            R.string.user_activity_connect_platform,
            "platform" to Platform.PLAYSTATION.properName,
        )

        confirmButton.setOnClickListener {
            onConfirmed(Platform.PLAYSTATION.platformId, ANALYTICS_LOCATION)
            StoreStream.getUserConnections()
                .authorizeConnection(Platform.PLAYSTATION.platformId, requireActivity())
            dismiss()
        }
        cancelButton.setOnClickListener { dialog?.cancel() }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        onDismissed(DISMISSIBLE_CONTENT_KEY)
    }

    override fun onStateChanged(state: Int) {
        super.onStateChanged(state)
        // A swipe-away never reaches onCancel, so the hidden state has to report it too.
        if (state == BottomSheetBehavior.STATE_HIDDEN) onDismissed(DISMISSIBLE_CONTENT_KEY)
    }

    companion object {
        private const val ANALYTICS_LOCATION = "playstation_connect_upsell"
        private const val DISMISSIBLE_CONTENT_KEY = "PLAYSTATION_CONNECT_UPSELL"

        /**
         * Reference: `AnalyticsTracker.trackAccountLinkStep(platformType, previousStep)`.
         * `AnalyticsTracker` exists in this project but does **not** declare that method, and it is
         * outside this agent's file ownership. **Point this at the tracker when it lands.**
         */
        @JvmStatic
        var onConfirmed: (platformId: String, location: String) -> Unit = { _, _ -> }

        /**
         * Reference: `AnalyticsTracker.trackDismissibleContentDismissed(type)` — also absent from
         * the reconstructed tracker. @see onConfirmed
         */
        @JvmStatic
        var onDismissed: (dismissibleContentKey: String) -> Unit = { }

        fun show(fragmentManager: FragmentManager) {
            WidgetPlaystationIntegrationUpsellBottomSheet()
                .show(
                    fragmentManager,
                    WidgetPlaystationIntegrationUpsellBottomSheet::class.java.simpleName,
                )
        }
    }
}
