package com.discord.widgets.contact_sync

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppLog
import com.discord.app.appActivityViewModels
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.views.CheckedSetting
import com.discord.widgets.contact_sync.WidgetContactSyncViewModel.ViewState
import com.google.android.material.button.MaterialButton

/**
 * The "Find your friends" prompt shown once to eligible accounts.
 *
 * It is the entry point into the full flow, not a substitute for it: the discovery toggle set here
 * is carried into [WidgetContactSync] through the launch intent, and nothing is uploaded until the
 * user works through the real flow and grants the contacts permission there.
 *
 * Dismissing it — by button, by back press, or by swiping it away — records the dismissal
 * permanently, which is why [onDismiss] and not the skip button owns that call.
 */
class ContactSyncUpsellSheet : AppBottomSheet() {

    private val viewModel: WidgetContactSyncViewModel by appActivityViewModels {
        WidgetContactSyncViewModel(
            mode = WidgetContactSync.getContactSyncModeFromIntent(requireAppActivity()),
            captchaLauncher = {
                AppLog.e("Invalid CAPTCHA: We shouldn't be interacting with CAPTCHA on this screen.")
            },
        )
    }

    private lateinit var discoveryToggle: CheckedSetting
    private lateinit var discoveryInfo: LinkifiedTextView
    private lateinit var nextButton: MaterialButton
    private lateinit var skipButton: MaterialButton

    override fun getContentViewResId(): Int = R.layout.contact_sync_upsell_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The sheet is a consent prompt; collapsing it half-way would hide the disclosure text.
        setBottomSheetCollapsedStateDisabled()
        val toggleInfo = view.findViewById<View>(R.id.contact_sync_upsell_toggle_info)
        discoveryToggle = toggleInfo.findViewById(R.id.contact_sync_discovery_toggle)
        discoveryInfo = toggleInfo.findViewById(R.id.contact_sync_discovery_info_2)
        nextButton = view.findViewById(R.id.contact_sync_upsell_next_button)
        skipButton = view.findViewById(R.id.contact_sync_upsell_skip_button)
        discoveryInfo.setContactSyncLearnMoreText(R.string.contact_sync_learn_more) {
            ContactSyncPermissionsSheet.show(parentFragmentManager)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(ContactSyncUpsellSheet::class.java) { configureUI(it) }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        viewModel.dismissUpsell()
    }

    private fun configureUI(viewState: ViewState) {
        val discoverable = viewState.allowPhone || viewState.allowEmail
        discoveryToggle.setChecked(discoverable, false)
        discoveryToggle.setContainerClickListener {
            viewModel.onPermissionsToggle(!discoverable, !discoverable)
        }
        skipButton.setOnClickListener {
            viewModel.skip()
            dismiss()
        }
        nextButton.setOnClickListener { button ->
            dismiss()
            WidgetContactSync.launch(
                context = button.context,
                immediatelyProceed = true,
                discoverByPhone = viewState.allowPhone,
                discoverByEmail = viewState.allowEmail,
            )
        }
    }

    companion object {
        @JvmStatic
        fun show(fragmentManager: FragmentManager): ContactSyncUpsellSheet =
            ContactSyncUpsellSheet().apply {
                show(fragmentManager, ContactSyncUpsellSheet::class.java.simpleName)
            }
    }
}
