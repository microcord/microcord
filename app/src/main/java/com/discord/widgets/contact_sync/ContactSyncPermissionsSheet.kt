package com.discord.widgets.contact_sync

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppLog
import com.discord.app.appActivityViewModels
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.views.CheckedSetting
import com.discord.widgets.contact_sync.WidgetContactSyncViewModel.ViewState

/**
 * The "Learn More" sheet behind every contact-sync consent string.
 *
 * This is the screen that explains, in the user's own words, what allowing contact discovery means,
 * and it is where the phone/e-mail discovery permissions can be set individually. It shares the
 * activity-scoped [WidgetContactSyncViewModel] with whatever opened it, so a toggle flipped here is
 * immediately reflected on the landing step or the upsell sheet underneath.
 */
class ContactSyncPermissionsSheet : AppBottomSheet() {

    private val viewModel: WidgetContactSyncViewModel by appActivityViewModels {
        WidgetContactSyncViewModel(
            mode = WidgetContactSync.getContactSyncModeFromIntent(requireAppActivity()),
            allowPhone = false,
            allowEmail = false,
            captchaLauncher = {
                // Nothing on this sheet submits a phone number, so a captcha here means the shared
                // view model was driven from somewhere unexpected.
                AppLog.e("Invalid CAPTCHA: We shouldn't be interacting with CAPTCHA on this screen.")
            },
        )
    }

    private lateinit var discoverableToggle: CheckedSetting
    private lateinit var phoneToggle: CheckedSetting
    private lateinit var emailToggle: CheckedSetting
    private lateinit var learnMore: LinkifiedTextView

    override fun getContentViewResId(): Int = R.layout.contact_sync_permissions_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        discoverableToggle = view.findViewById(R.id.contact_sync_permissions_discoverable_toggle)
        phoneToggle = view.findViewById(R.id.contact_sync_permissions_phone_toggle)
        emailToggle = view.findViewById(R.id.contact_sync_permissions_email_toggle)
        learnMore = view.findViewById(R.id.contact_sync_permissions_learn_more)
        learnMore.setContactSyncLearnMoreText(R.string.contact_sync_learn_more) {
            UriHandler.handle(requireContext(), CONTACT_SYNC_SUPPORT_URL)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(ContactSyncPermissionsSheet::class.java) { configureUI(it) }
    }

    private fun configureUI(viewState: ViewState) {
        // The master toggle is on when *either* discovery method is on, so turning it off clears
        // both and turning it on enables both.
        val discoverable = viewState.allowPhone || viewState.allowEmail
        // `notifyListener = false`: the state is being rendered, not changed by the user.
        discoverableToggle.setChecked(discoverable, false)
        phoneToggle.setChecked(viewState.allowPhone, false)
        emailToggle.setChecked(viewState.allowEmail, false)

        discoverableToggle.setContainerClickListener {
            viewModel.onPermissionsToggle(!discoverable, !discoverable)
        }
        phoneToggle.setContainerClickListener {
            viewModel.onPermissionsToggle(!viewState.allowPhone, viewState.allowEmail)
        }
        emailToggle.setContainerClickListener {
            viewModel.onPermissionsToggle(viewState.allowPhone, !viewState.allowEmail)
        }
    }

    companion object {
        /** Reference: help-centre article `360061878534`. */
        private const val CONTACT_SYNC_SUPPORT_URL =
            "https://support.discord.com/hc/en-us/articles/360061878534"

        @JvmStatic
        fun show(fragmentManager: FragmentManager): ContactSyncPermissionsSheet =
            ContactSyncPermissionsSheet().apply {
                show(fragmentManager, ContactSyncPermissionsSheet::class.java.simpleName)
            }
    }
}
