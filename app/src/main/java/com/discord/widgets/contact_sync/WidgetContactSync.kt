package com.discord.widgets.contact_sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.analytics.generated.traits.TrackImpressionMetadata
import com.discord.analytics.generated.traits.TrackImpressionMetadataReceiver
import com.discord.app.AppActivity
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppViewFlipper
import com.discord.app.LoggingConfig
import com.discord.app.appActivityViewModels
import com.discord.i18n.FormatUtils
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.analytics.Traits
import com.discord.utilities.captcha.CaptchaHelper
import com.discord.utilities.error.RestApiError
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.ToastManager
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.views.CheckedSetting
import com.discord.views.CodeVerificationView
import com.discord.views.LoadingButton
import com.discord.views.phone.PhoneOrEmailInputView
import com.discord.widgets.contact_sync.WidgetContactSyncViewModel.Event
import com.discord.widgets.contact_sync.WidgetContactSyncViewModel.ToolbarConfig
import com.discord.widgets.contact_sync.WidgetContactSyncViewModel.ViewState
import com.discord.widgets.contact_sync.WidgetContactSyncViewModel.Views
import com.discord.widgets.captcha.WidgetCaptcha
import com.discord.widgets.phone.WidgetPhoneCountryCodeBottomSheet
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout

/**
 * The contact-sync flow: a single screen with six steps behind an [AppViewFlipper].
 *
 * The order is landing → (add phone → verify phone, only if the account has no phone number yet) →
 * name → suggestions. Which step is shown is entirely the view model's decision; this class renders
 * it, animates the transition in the right direction, and reports user input back.
 *
 * PRIVACY. This screen is the only place in the app that asks for `READ_CONTACTS`, and the consent
 * it presents is load-bearing, not decoration:
 *  - the landing step states, before anything is requested, that contacts will be sent and
 *    re-sent, with a "Learn More" link into [ContactSyncPermissionsSheet];
 *  - the discovery toggle is answered *before* the permission dialog appears, and its answer is
 *    carried into the settings call;
 *  - the runtime permission is only requested at the point the user presses the CTA, and a denial
 *    is respected — the screen switches to explaining how to grant it in Settings rather than
 *    re-prompting;
 *  - nothing read from the address book is logged, kept in the view state, or written to disk.
 */
class WidgetContactSync : AppFragment(R.layout.widget_contact_sync) {

    private val contactSyncFlowMetadata = TrackImpressionMetadata(impressionGroup = "contact_sync_flow")
    override val loggingConfig = LoggingConfig(
        impressionSchemaProvider = {
            val type = when (displayedChildIndex) {
                Views.VIEW_LANDING.ordinal -> "impression_contact_sync_start"
                Views.VIEW_ADD_PHONE.ordinal -> "impression_user_add_phone"
                Views.VIEW_NAME_INPUT.ordinal -> "impression_contact_sync_input_name"
                Views.VIEW_SUGGESTIONS.ordinal,
                Views.VIEW_SUGGESTIONS_EMPTY.ordinal,
                -> "impression_contact_sync_suggestions"
                else -> null
            }
            type?.let { ContactSyncImpression(it, contactSyncFlowMetadata) }
        },
    )

    private val viewModel: WidgetContactSyncViewModel by appActivityViewModels {
        WidgetContactSyncViewModel(
            mode = getContactSyncModeFromIntent(requireAppActivity()),
            allowPhone = getPhoneDiscoverableFromIntent(requireAppActivity()),
            allowEmail = getEmailDiscoverableFromIntent(requireAppActivity()),
            captchaLauncher = { error -> launchCaptchaFlow(error) },
        )
    }

    private val captchaLauncher = WidgetCaptcha.registerForResult(this, ::submitPhoneNumber)

    private val toastManager = ToastManager()

    private var displayedChildIndex: Int = 0
    private var friendSuggestionsAdapter: ContactSyncFriendSuggestionListAdapter? = null

    /** The account's verified phone number, used only to look the user's own name up locally. */
    private var phoneNumber: String? = null

    // region views

    private lateinit var viewFlipper: AppViewFlipper

    private lateinit var landingNextButton: LoadingButton
    private lateinit var landingNeedsPermissions: TextView
    private lateinit var landingPermissionsDivider: View
    private lateinit var landingDiscoveryToggle: CheckedSetting
    private lateinit var landingDiscoveryInfo: LinkifiedTextView

    private lateinit var addPhoneInput: PhoneOrEmailInputView
    private lateinit var addPhoneNext: MaterialButton

    private lateinit var verifyPhoneCode: CodeVerificationView

    private lateinit var nameInput: TextInputLayout
    private lateinit var namePrefillHint: TextView
    private lateinit var nameNextButton: LoadingButton

    private lateinit var suggestionsRecycler: RecyclerView
    private lateinit var suggestionsSubmitButton: LoadingButton

    private lateinit var suggestionsInviteButton: MaterialButton
    private lateinit var suggestionsSkipButton: MaterialButton

    // endregion

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        bindViews(view)

        friendSuggestionsAdapter = MGRecyclerAdapter.configure(
            ContactSyncFriendSuggestionListAdapter(suggestionsRecycler),
        ).apply {
            onClickFriendSuggestion = { userId, selected ->
                viewModel.handleToggleFriendSuggestionSelected(userId, selected)
            }
        }

        // The country field is inert until something can open the picker; wiring it here is what
        // unpins phone entry from "+1".
        addPhoneInput.bind(
            fragment = this,
            onAfterTextChanged = ::handlePhoneNumberTextChanged,
            onCountryCodeClick = {
                WidgetPhoneCountryCodeBottomSheet.show(parentFragmentManager) { countryCode ->
                    addPhoneInput.setCountryCode(countryCode)
                    handlePhoneNumberTextChanged()
                }
            },
        )
        addPhoneNext.setOnClickListener { submitPhoneNumber() }
        verifyPhoneCode.onCodeEntered = { code -> viewModel.onVerifyPhone(code) }
        landingDiscoveryInfo.setContactSyncLearnMoreText(
            R.string.contact_sync_permissions_description_android,
        ) {
            AnalyticsTracker.openPopout("Contact Sync", Traits.Location(page = "Contact Sync Learn More"))
            ContactSyncPermissionsSheet.show(parentFragmentManager)
        }
    }

    private fun bindViews(view: View) {
        viewFlipper = view.findViewById(R.id.contact_sync_view_flipper)

        val landing = view.findViewById<View>(R.id.contact_sync_landing)
        landingNextButton = landing.findViewById(R.id.contact_sync_landing_next_button)
        landingNeedsPermissions = landing.findViewById(R.id.contact_sync_landing_needs_permissions)
        landingPermissionsDivider = landing.findViewById(R.id.contact_sync_landing_permissions_divider)
        landingDiscoveryToggle = landing.findViewById(R.id.contact_sync_discovery_toggle)
        landingDiscoveryInfo = landing.findViewById(R.id.contact_sync_discovery_info_2)

        val addPhone = view.findViewById<View>(R.id.contact_sync_add_phone)
        addPhoneInput = addPhone.findViewById(R.id.contact_sync_add_phone_input)
        addPhoneNext = addPhone.findViewById(R.id.contact_sync_add_phone_next)

        verifyPhoneCode = view.findViewById<View>(R.id.contact_sync_verify_phone)
            .findViewById(R.id.contact_sync_verify_phone_code)

        val name = view.findViewById<View>(R.id.contact_sync_name)
        nameInput = name.findViewById(R.id.contact_sync_name_input_wrap)
        namePrefillHint = name.findViewById(R.id.contact_sync_name_prefill_hint)
        nameNextButton = name.findViewById(R.id.contact_sync_name_next_button)

        val suggestions = view.findViewById<View>(R.id.contact_sync_friend_suggestions)
        suggestionsRecycler = suggestions.findViewById(R.id.contact_sync_suggestions_list_recycler)
        suggestionsSubmitButton = suggestions.findViewById(R.id.contact_sync_suggestions_submit_button)

        val suggestionsEmpty = view.findViewById<View>(R.id.contact_sync_friend_suggestions_empty)
        suggestionsInviteButton = suggestionsEmpty.findViewById(R.id.contact_sync_suggestions_invite_button)
        suggestionsSkipButton = suggestionsEmpty.findViewById(R.id.contact_sync_suggestions_skip_button)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetContactSync::class.java) { configureUI(it) }
        viewModel.observeEvents()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetContactSync::class.java) { handleEvent(it) }
    }

    override fun onResume() {
        super.onResume()
        // The user may have granted the permission from the system settings screen the "needs
        // permission" CTA sends them to.
        if (ContactSyncDependencies.contactsProvider.hasContactPermissions(requireContext())) {
            viewModel.onPermissionsBecameAvailable()
        }
    }

    override fun onDestroyView() {
        toastManager.close()
        friendSuggestionsAdapter?.dispose()
        friendSuggestionsAdapter = null
        super.onDestroyView()
    }

    // region rendering

    private fun configureUI(viewState: ViewState) {
        phoneNumber = viewState.phoneNumber
        configureViewFlipper(viewState.displayedChild)
        configureToolbar(viewState.toolbarConfig)

        landingNextButton.isEnabled = viewState.landingNextEnabled
        landingNextButton.setIsLoading(viewState.isSubmitting)
        nameNextButton.setIsLoading(viewState.isSubmitting)
        suggestionsSubmitButton.setIsLoading(viewState.isSubmitting)

        configureLandingCta(viewState)

        val discoverable = viewState.allowPhone || viewState.allowEmail
        landingDiscoveryToggle.setChecked(discoverable, false)
        landingDiscoveryToggle.setContainerClickListener {
            val checked = landingDiscoveryToggle.isChecked
            viewModel.onPermissionsToggle(!checked, !checked)
        }

        nameNextButton.setOnClickListener { viewModel.onNameSubmitted(nameInput.getTextOrEmpty()) }

        friendSuggestionsAdapter?.setData(viewState.friendSuggestions)
        suggestionsSubmitButton.setText(getString(R.string.next))
        suggestionsSubmitButton.setOnClickListener { viewModel.onBulkAddFriends() }

        addPhoneInput.setCountryCode(viewState.countryCode)

        suggestionsInviteButton.setOnClickListener { button ->
            AnalyticsTracker.friendAddViewed("Invite")
            shareInviteText(button.context, viewState.username)
        }
        suggestionsSkipButton.setOnClickListener { viewModel.skip() }
    }

    /**
     * The landing CTA doubles as the "permission denied" recovery path: once the user has refused
     * `READ_CONTACTS`, re-prompting is a no-op on Android, so the button opens the app's settings
     * page instead and the explanatory text and divider appear above it.
     */
    private fun configureLandingCta(viewState: ViewState) {
        val denied = viewState.permissionsDenied
        landingNeedsPermissions.visibility = if (denied) View.VISIBLE else View.GONE
        landingPermissionsDivider.visibility = if (denied) View.VISIBLE else View.GONE
        if (denied) {
            landingNextButton.setText(getString(R.string.password_manager_open_settings))
            landingNextButton.setOnClickListener { view -> openApplicationSettings(view.context) }
        } else {
            landingNextButton.setText(getString(R.string.get_started))
            landingNextButton.setOnClickListener { viewModel.onLandingNext() }
        }
    }

    private fun configureToolbar(toolbarConfig: ToolbarConfig) {
        setActionBarDisplayHomeAsUpEnabled(toolbarConfig.showBackButton)
        setActionBarOptionsMenu(
            R.menu.menu_contact_sync,
            onSelected = { menuItem ->
                if (menuItem.itemId == R.id.menu_contact_sync_skip) viewModel.skip()
            },
            onConfigured = { menu ->
                menu.findItem(R.id.menu_contact_sync_skip).isVisible = toolbarConfig.showSkip
            },
        )
        // Back only means "step backwards" while a back arrow is offered; otherwise the press falls
        // through and leaves the flow.
        setOnBackPressed({
            if (toolbarConfig.showBackButton) {
                viewModel.skip()
                true
            } else {
                false
            }
        })
    }

    /**
     * Animates in the direction the user is travelling.
     *
     * Landing is both the first step and the step the flow returns to after phone verification, so
     * arriving there from the two phone steps is a *forward* move even though its index is lower —
     * that is what [returningFromPhoneVerification] captures.
     */
    private fun configureViewFlipper(displayedChild: Views) {
        val targetIndex = displayedChild.ordinal
        if (targetIndex != displayedChildIndex) hideKeyboard()

        val returningFromPhoneVerification = displayedChild == Views.VIEW_LANDING &&
            (displayedChildIndex == Views.VIEW_VERIFY_PHONE.ordinal ||
                displayedChildIndex == Views.VIEW_NAME_INPUT.ordinal)

        when {
            StoreStream.getAccessibility().isReducedMotionEnabled() -> {
                viewFlipper.inAnimation = AnimationUtils.loadAnimation(context, R.anim.anim_fade_in_fast)
                viewFlipper.outAnimation = AnimationUtils.loadAnimation(context, R.anim.anim_fade_out_fast)
            }
            targetIndex > displayedChildIndex || returningFromPhoneVerification -> {
                viewFlipper.inAnimation =
                    AnimationUtils.loadAnimation(context, R.anim.activity_slide_horizontal_open_in)
                viewFlipper.outAnimation =
                    AnimationUtils.loadAnimation(context, R.anim.activity_slide_horizontal_open_out)
            }
            targetIndex < displayedChildIndex -> {
                viewFlipper.inAnimation =
                    AnimationUtils.loadAnimation(context, R.anim.activity_slide_horizontal_close_in)
                viewFlipper.outAnimation =
                    AnimationUtils.loadAnimation(context, R.anim.activity_slide_horizontal_close_out)
            }
        }

        viewFlipper.setDisplayedChild(targetIndex)
        displayedChildIndex = targetIndex
        appLogger.logImpression()
    }

    // endregion

    // region events

    private fun handleEvent(event: Event) {
        when (event) {
            Event.MaybeProceedFromLanding ->
                if (mostRecentIntent.getBooleanExtra(INTENT_EXTRA_IMMEDIATELY_PROCEED, false)) {
                    viewModel.onLandingNext()
                }
            Event.PermissionsNeeded -> requestContactsPermissions()
            Event.ContactsEnabled -> viewModel.onContactsFetched(
                ContactSyncDependencies.contactsProvider.allContactPhoneNumbers(requireContext()),
            )
            Event.RateLimited -> longToast(R.string.contact_sync_failed_alert_title)
            Event.UploadFailed -> longToast(R.string.contact_sync_failed_alert_message)
            Event.ContactsEnableFailed -> longToast(R.string.contact_sync_failed_alert_title)
            Event.AddFriendsFailed,
            Event.AddFriendsFailedPartial,
            -> AddFriendsFailed.show(parentFragmentManager).onClose = { viewModel.skip() }
            Event.PhoneInvalid -> shortToast(R.string.phone_invalid)
            Event.VerificationCodeInvalid ->
                shortToast(R.string.application_entitlement_code_redemption_invalid)
            Event.VerificationFailed -> shortToast(R.string.phone_failed_to_add)
            Event.Completed -> requireAppActivity().finish()
        }
    }

    /**
     * The phone step's CTA only lights up for an international number.
     *
     * `getTextOrEmpty` returns the country code concatenated with the typed digits, so the leading
     * `+` is present exactly when a country code has been resolved.
     */
    private fun handlePhoneNumberTextChanged() {
        val text = addPhoneInput.getTextOrEmpty()
        addPhoneNext.isEnabled = text.isNotEmpty() && text.startsWith("+")
    }

    private fun submitPhoneNumber(captchaPayload: CaptchaHelper.CaptchaPayload? = null) {
        viewModel.onPhoneNumberSubmitted(addPhoneInput.getTextOrEmpty(), captchaPayload)
    }

    private fun launchCaptchaFlow(error: RestApiError) {
        WidgetCaptcha.processErrorsForCaptcha(
            requireContext(),
            captchaLauncher,
            error.messages.keys.toMutableList(),
            error,
        )
    }

    private fun requestContactsPermissions() {
        if (!ContactSyncDependencies.contactsProvider.hasContactPermissions(requireContext())) {
            ContactSyncDependencies.analytics.permissionsRequested(CONTACTS_PERMISSION)
            viewModel.requestingPermissions()
        }
        requestContacts(
            onSuccess = { onPermissionsGranted() },
            onFailure = { viewModel.onPermissionsDenied() },
        )
    }

    /**
     * Prefills the name field from the address book's own entry for the account's phone number.
     *
     * This reads exactly one contact, chosen by a number the account already owns, and puts it in
     * an editable field the user must confirm — it is never submitted silently.
     */
    private fun onPermissionsGranted() {
        viewModel.onPermissionsGranted()
        val phoneNumber = this.phoneNumber ?: return
        val ownName = ContactSyncDependencies.contactsProvider.ownName(requireContext(), phoneNumber)
            ?: return
        nameInput.editText?.setText(ownName)
        namePrefillHint.visibility = View.VISIBLE
    }

    // endregion

    private fun shareInviteText(context: Context, username: String) {
        val message = FormatUtils.format(
            getString(R.string.friends_share_tabbar_title),
            "url" to DISCORD_URL,
            "username" to username,
        )
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, message)
        context.startActivity(
            Intent.createChooser(send, getString(R.string.tip_instant_invite_title3)),
        )
    }

    private fun openApplicationSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null)),
        )
    }

    private fun longToast(stringRes: Int) =
        toastManager.show(requireContext(), stringRes, android.widget.Toast.LENGTH_LONG)

    private fun shortToast(stringRes: Int) =
        toastManager.show(requireContext(), stringRes, android.widget.Toast.LENGTH_SHORT)

    companion object {
        private const val INTENT_EXTRA_MODE = "INTENT_EXTRA_CONTACT_SYNC_MODE"
        private const val INTENT_EXTRA_IMMEDIATELY_PROCEED = "INTENT_EXTRA_CONTACT_SYNC_IMMEDIATELY_PROCEED"
        private const val INTENT_EXTRA_ALLOW_PHONE = "INTENT_EXTRA_CONTACT_SYNC_ALLOW_PHONE"
        private const val INTENT_EXTRA_ALLOW_EMAIL = "INTENT_EXTRA_CONTACT_SYNC_ALLOW_EMAIL"
        private const val CONTACTS_PERMISSION = "contacts"
        private const val DISCORD_URL = "https://discord.com"

        @Suppress("DEPRECATION")
        @JvmStatic
        fun getContactSyncModeFromIntent(appActivity: AppActivity): ContactSyncMode =
            appActivity.mostRecentIntent.getSerializableExtra(INTENT_EXTRA_MODE) as ContactSyncMode

        @JvmStatic
        fun getPhoneDiscoverableFromIntent(appActivity: AppActivity): Boolean =
            appActivity.mostRecentIntent.getBooleanExtra(INTENT_EXTRA_ALLOW_PHONE, true)

        @JvmStatic
        fun getEmailDiscoverableFromIntent(appActivity: AppActivity): Boolean =
            appActivity.mostRecentIntent.getBooleanExtra(INTENT_EXTRA_ALLOW_EMAIL, true)

        @JvmStatic
        @JvmOverloads
        fun launch(
            context: Context,
            mode: ContactSyncMode = ContactSyncMode.DEFAULT,
            immediatelyProceed: Boolean = false,
            discoverByPhone: Boolean = true,
            discoverByEmail: Boolean = true,
        ) {
            val extras = Intent()
                .putExtra(INTENT_EXTRA_MODE, mode)
                .putExtra(INTENT_EXTRA_IMMEDIATELY_PROCEED, immediatelyProceed)
                .putExtra(INTENT_EXTRA_ALLOW_PHONE, discoverByPhone)
                .putExtra(INTENT_EXTRA_ALLOW_EMAIL, discoverByEmail)
            AppScreenRouter.launch(context, WidgetContactSync::class.java, extras)
        }
    }

    private data class ContactSyncImpression(
        @Transient override val analyticsSchemaTypeName: String,
        override val trackImpressionMetadata: TrackImpressionMetadata,
    ) : TrackImpressionMetadataReceiver
}
