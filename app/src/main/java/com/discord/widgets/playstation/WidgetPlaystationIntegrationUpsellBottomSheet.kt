package com.discord.widgets.playstation

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.databinding.WidgetPlaystationUpsellBottomSheetBinding
import com.discord.i18n.i18nFormat
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsUtils
import com.discord.utilities.platform.Platform
import com.google.android.material.bottomsheet.BottomSheetBehavior

/**
 * "Connect PlayStation Network" upsell.
 *
 * Confirming hands off to [com.discord.stores.StoreUserConnections.authorizeConnection], which
 * opens the OAuth page in a browser; the sheet closes immediately either way. Dismissal is
 * reported whichever route it takes: the scrim, the cancel button or a swipe.
 */
class WidgetPlaystationIntegrationUpsellBottomSheet : AppBottomSheet() {

    private val binding: WidgetPlaystationUpsellBottomSheetBinding
        get() = WidgetPlaystationUpsellBottomSheetBinding.bind(requireView())

    override fun getContentViewResId(): Int = R.layout.widget_playstation_upsell_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.upsellHeader.text = requireContext().i18nFormat(
            R.string.user_activity_connect_platform,
            "platform" to Platform.PLAYSTATION.properName,
        )

        binding.confirmButton.setOnClickListener {
            logConfirm()
            StoreStream.getUserConnections()
                .authorizeConnection(Platform.PLAYSTATION.platformId, requireActivity())
            dismiss()
        }
        binding.cancelButton.setOnClickListener { checkNotNull(dialog).cancel() }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        logDismiss()
    }

    override fun onStateChanged(state: Int) {
        super.onStateChanged(state)
        // A swipe-away never reaches onCancel, so the hidden state has to report it too.
        if (state == BottomSheetBehavior.STATE_HIDDEN) logDismiss()
    }

    private fun logConfirm() {
        AnalyticsUtils.Tracker.instance.track(
            "account_link_step",
            mapOf(
                "previous_step" to "playstation_connect_upsell",
                "platform_type" to Platform.PLAYSTATION.platformId,
            ),
        )
    }

    private fun logDismiss() {
        AnalyticsUtils.Tracker.instance.track(
            "dismissible_content_dismissed",
            mapOf("type" to "PLAYSTATION_CONNECT_UPSELL"),
        )
    }

    companion object {
        fun show(fragmentManager: FragmentManager) {
            WidgetPlaystationIntegrationUpsellBottomSheet()
                .show(
                    fragmentManager,
                    WidgetPlaystationIntegrationUpsellBottomSheet::class.java.simpleName,
                )
        }
    }
}
