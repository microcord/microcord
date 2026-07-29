package com.discord.widgets.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.discord.R
import com.discord.api.connectedaccounts.ConnectedAccount
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.app.AppViewModel
import com.discord.app.appViewModels
import com.discord.models.domain.Consents
import com.discord.models.domain.Harvest
import com.discord.models.domain.ModelUserSettings
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserConnections
import com.discord.utilities.accessibility.AccessibilityUtils
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.analytics.Traits
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.rx.withDimmer
import com.discord.utilities.time.TimeUtils
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.user.UserUtils.isStaff
import com.discord.views.CheckedSetting
import com.discord.views.RadioManager
import com.discord.widgets.contact_sync.ContactSyncFlowAnalytics
import com.discord.widgets.contact_sync.WidgetContactSync
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.settings.account.WidgetSettingsAccount
import com.google.android.material.button.MaterialButton
import rx.Observable
import rx.functions.Action1

/**
 * "Privacy & Safety" user settings.
 *
 * Reference: `com.discord.widgets.settings.WidgetSettingsPrivacy`. Five independent groups share one
 * screen:
 *
 * 1. **Safe direct messaging** — a three-way explicit-content filter, written through
 *    `StoreUserSettings.setExplicitContentFilter`.
 * 2. **Server DM defaults** — one switch that also asks whether to apply the change to servers the
 *    user has *already* joined, because the server-side setting is a default plus an explicit
 *    per-guild list.
 * 3. **Who can add you as a friend** — three *switches* that behave as a hierarchy rather than a
 *    radio group: "everyone" implies the other two.
 * 4. **Contact sync** — experiment-gated; toggling it on with no `contacts` connection opens the
 *    contact-sync flow instead of writing anything.
 * 5. **Data privacy controls** — the two consents (which round-trip through REST, with a
 *    confirmation dialog when revoking), screen-reader detection, and the data-download request.
 *
 * Note the friend-source switches are deliberately **not** a [RadioManager] group: the reference
 * lets several be checked at once and derives `all` from the combination. Using a radio manager here
 * would break the feature. The explicit-content rows *are* a radio group and do use one.
 */
class WidgetSettingsPrivacy : AppFragment(R.layout.widget_settings_privacy) {

    private val viewModel: SettingsPrivacyViewModel by appViewModels { SettingsPrivacyViewModel() }

    private var existingServersDialog: AlertDialog? = null

    private lateinit var dimmer: DimmerView
    private lateinit var explicitContentRadios: List<CheckedSetting>
    private lateinit var explicitContentRadioManager: RadioManager
    private lateinit var friendSourceSwitches: List<CheckedSetting>
    private lateinit var defaultRestrictedGuilds: CheckedSetting

    private lateinit var contactSyncContainer: LinearLayout
    private lateinit var contactSyncInfo: TextView
    private lateinit var contactSyncEnabled: CheckedSetting
    private lateinit var contactSyncPhone: CheckedSetting
    private lateinit var contactSyncEmail: CheckedSetting
    private lateinit var contactSyncStaffOnly: CheckedSetting

    private lateinit var privacyControls: LinearLayout
    private lateinit var usageStatistics: CheckedSetting
    private lateinit var personalization: CheckedSetting
    private lateinit var screenReaderDetection: CheckedSetting
    private lateinit var basicService: CheckedSetting
    private lateinit var requestData: MaterialButton
    private lateinit var requestDataLink: TextView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.privacy_and_safety)
        setActionBarSubtitle(R.string.user_settings)
        SettingsNavigation.bindBack(this)

        dimmer = view.findViewById(R.id.dimmer_view)
        defaultRestrictedGuilds = view.findViewById(R.id.settings_privacy_default_restricted_guilds)

        // Index is the filter value: 0 = off, 1 = non-friends only, 2 = everyone.
        explicitContentRadios = listOf(
            view.findViewById(R.id.settings_privacy_explicit_0),
            view.findViewById(R.id.settings_privacy_explicit_1),
            view.findViewById(R.id.settings_privacy_explicit_2),
        )
        explicitContentRadioManager = RadioManager(explicitContentRadios)

        friendSourceSwitches = listOf(
            view.findViewById(R.id.settings_privacy_friend_source_0),
            view.findViewById(R.id.settings_privacy_friend_source_1),
            view.findViewById(R.id.settings_privacy_friend_source_2),
        )

        contactSyncContainer = view.findViewById(R.id.settings_privacy_contact_sync)
        contactSyncInfo = view.findViewById(R.id.contact_sync_setting_info)
        contactSyncEnabled = view.findViewById(R.id.contact_sync_setting_enabled)
        contactSyncPhone = view.findViewById(R.id.contact_sync_setting_phone)
        contactSyncEmail = view.findViewById(R.id.contact_sync_setting_email)
        contactSyncStaffOnly = view.findViewById(R.id.contact_sync_setting_staff_only)

        privacyControls = view.findViewById(R.id.settings_privacy_controls)
        usageStatistics = view.findViewById(R.id.settings_privacy_statistics)
        personalization = view.findViewById(R.id.settings_privacy_personalization)
        screenReaderDetection = view.findViewById(R.id.settings_privacy_screenreader_detection)
        basicService = view.findViewById(R.id.settings_privacy_basic_service)
        requestData = view.findViewById(R.id.settings_privacy_request_data)
        requestDataLink = view.findViewById(R.id.request_data_link)

        configureStaticSubtexts(view.context)

        screenReaderDetection.setOnCheckedListener(
            Action1 { allowed -> setAccessibilityDetectionAllowed(allowed) },
        )

        // The four section headers are announced as headings so a screen reader can jump between
        // them; the contact-sync title deliberately is not, matching the reference.
        listOf(
            view.findViewById<View>(R.id.settings_privacy_safe_messaging_header),
            view.findViewById(R.id.settings_privacy_defaults_header),
            view.findViewById(R.id.settings_privacy_friend_source_header),
            view.findViewById(R.id.settings_privacy_data_header),
        ).forEach(AccessibilityUtils::setViewIsHeading)
    }

    private fun configureStaticSubtexts(context: Context) {
        personalization.setSubtext(
            SettingsStrings.render(
                context,
                R.string.data_privacy_controls_personalization_note_learn_more,
                "helpdeskArticle" to PERSONALIZATION_HELP_URL,
            ),
            true,
        )
        screenReaderDetection.setSubtext(
            SettingsStrings.render(
                context,
                R.string.data_privacy_controls_allow_accessibility_detection_note,
                "helpdeskArticle" to ACCESSIBILITY_DETECTION_HELP_URL,
            ),
            true,
        )
        usageStatistics.setSubtext(
            SettingsStrings.render(
                context,
                R.string.data_privacy_controls_usage_statistics_note,
                "helpdeskArticle" to PERSONALIZATION_HELP_URL,
            ),
            true,
        )

        // "Basic service" is a statement, not a toggle, so it has no button — only an explanation
        // whose link leads to account disable/delete.
        basicService.setButtonVisibility(false)
        basicService.setSubtext(
            SettingsStrings.render(
                context,
                R.string.data_privacy_controls_basic_service_note,
                // The recovered string's link destination is the literal token `onClick`; it names a
                // handler, not a URL, so it must never be handed to UriHandler.
                onLink = { openAccountSettings() },
            ),
            true,
        )
        basicService.setContainerClickListener { openAccountSettings() }

        // A bare "What's this?" link rendered as markdown, since the layout has no text for it.
        requestDataLink.text = SettingsStrings.render(
            context,
            "[${getString(R.string.notice_whats_this)}]($DATA_REQUEST_HELP_URL)",
        )
        requestDataLink.setOnClickListener { UriHandler.handle(it.context, DATA_REQUEST_HELP_URL) }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).subscribe(::configureUI)
        // The screen-reader consent lives outside the REST-backed model because it is a plain user
        // setting, and it is written optimistically by its own observable.
        StoreStream.getUserSettings().observeIsAccessibilityDetectionAllowed()
            .ui(this)
            .subscribe { screenReaderDetection.setChecked(it, false) }
    }

    override fun onPause() {
        super.onPause()
        existingServersDialog?.dismiss()
    }

    private fun configureUI(viewState: SettingsPrivacyViewModel.ViewState) {
        when (viewState) {
            // Consents arrive over REST, so the screen genuinely has nothing to show until then.
            is SettingsPrivacyViewModel.ViewState.Loading -> dimmer.setDimmed(true)
            is SettingsPrivacyViewModel.ViewState.Loaded -> {
                dimmer.setDimmed(false)
                configureDefaultGuildsRestricted(viewState)
                configureFriendSourceSwitches(viewState)
                configureContactSync(viewState)
                explicitContentRadios.forEachIndexed { filter, radio ->
                    configureExplicitContentRadio(viewState, radio, filter)
                }
                configurePrivacyControls(viewState)
                configureRequestDataButton(viewState)
            }
        }
    }

    // region safe direct messaging

    private fun configureExplicitContentRadio(
        viewState: SettingsPrivacyViewModel.ViewState.Loaded,
        radio: CheckedSetting,
        explicitContentFilter: Int,
    ) {
        radio.setContainerClickListener {
            StoreStream.getUserSettings().setExplicitContentFilter(appActivity, explicitContentFilter)
        }
        // The selection follows the store, not the tap: a rejected write leaves the old row checked.
        if (viewState.explicitContentFilter == explicitContentFilter) {
            explicitContentRadioManager.setChecked(radio)
        }
    }

    // endregion

    // region server DM defaults

    private fun configureDefaultGuildsRestricted(viewState: SettingsPrivacyViewModel.ViewState.Loaded) {
        // The switch reads "allow DMs", the setting stores "restrict DMs" — they are inverses.
        defaultRestrictedGuilds.setChecked(!viewState.defaultRestrictedGuilds, false)
        defaultRestrictedGuilds.setContainerClickListener {
            showDefaultGuildsRestrictedExistingServers(!viewState.defaultRestrictedGuilds)
        }
    }

    /**
     * Asks whether the new default should also be applied to servers already joined.
     *
     * The server keeps a default *and* an explicit per-guild list, so changing the default alone
     * leaves existing servers as they were — which is why this question has to be asked rather than
     * assumed.
     */
    private fun showDefaultGuildsRestrictedExistingServers(defaultGuildsRestricted: Boolean) {
        val view = layoutInflater.inflate(R.layout.widget_settings_privacy_defaults, null, false)
        view.findViewById<MaterialButton>(R.id.settings_privacy_defaults_existing_no)
            .setOnClickListener {
                updateDefaultGuildsRestricted(defaultGuildsRestricted, applyToExistingGuilds = false)
            }
        view.findViewById<MaterialButton>(R.id.settings_privacy_defaults_existing_yes)
            .setOnClickListener {
                updateDefaultGuildsRestricted(defaultGuildsRestricted, applyToExistingGuilds = true)
            }
        existingServersDialog?.dismiss()
        existingServersDialog = AlertDialog.Builder(view.context).setView(view).create().also { it.show() }
    }

    private fun updateDefaultGuildsRestricted(
        defaultGuildsRestricted: Boolean,
        applyToExistingGuilds: Boolean,
    ) {
        val restrictedGuilds: Observable<Set<Long>?> = when {
            // Leave the explicit list alone; only the default changes.
            !applyToExistingGuilds -> Observable.just<Set<Long>?>(null)
            // Allowing DMs everywhere means clearing the list rather than listing every guild.
            !defaultGuildsRestricted -> Observable.just<Set<Long>?>(emptySet())
            else -> StoreStream.getGuilds().observeGuilds().map<Set<Long>?> { it.keys }
        }
        restrictedGuilds.take(1)
            .computationLatest()
            .ui(this)
            .appSubscribe(WidgetSettingsPrivacy::class.java) { guildIds ->
                StoreStream.getUserSettings()
                    .setDefaultGuildsRestricted(appActivity, defaultGuildsRestricted, guildIds)
            }
        existingServersDialog?.dismiss()
    }

    // endregion

    // region friend sources

    private fun configureFriendSourceSwitches(viewState: SettingsPrivacyViewModel.ViewState.Loaded) {
        friendSourceSwitches.forEachIndexed { index, switch ->
            switch.setOnCheckedListener(Action1 { checked -> updateFriendSourceFlags(index, checked) })
        }
        val flags = viewState.friendSourceFlags
        val all = flags?.all == true
        friendSourceSwitches[SOURCE_EVERYONE].setChecked(all, false)
        // "Everyone" subsumes the narrower two, so it shows them as checked as well.
        friendSourceSwitches[SOURCE_MUTUAL_FRIENDS].setChecked(flags?.mutualFriends == true || all, false)
        friendSourceSwitches[SOURCE_MUTUAL_GUILDS].setChecked(flags?.mutualGuilds == true || all, false)
    }

    /**
     * Derives the three server-side flags from the switches' post-tap state.
     *
     * `all` is set either when every switch ended up checked, or when the user checked the
     * "everyone" switch itself — the second case is what lets one tap turn all three on before the
     * store has echoed the change back.
     */
    private fun updateFriendSourceFlags(index: Int, checked: Boolean) {
        val everyone = friendSourceSwitches[SOURCE_EVERYONE].isChecked()
        val mutualFriends = friendSourceSwitches[SOURCE_MUTUAL_FRIENDS].isChecked()
        val mutualGuilds = friendSourceSwitches[SOURCE_MUTUAL_GUILDS].isChecked()
        val all = (everyone && mutualFriends && mutualGuilds) ||
            (index == SOURCE_EVERYONE && checked)
        StoreStream.getUserSettings().setFriendSourceFlags(
            appActivity,
            all,
            mutualGuilds,
            mutualFriends,
        )
    }

    // endregion

    // region contact sync

    private fun configureContactSync(viewState: SettingsPrivacyViewModel.ViewState.Loaded) {
        contactSyncContainer.visibility =
            if (viewState.showContactSync) View.VISIBLE else View.GONE
        if (!viewState.showContactSync) return

        contactSyncInfo.text = SettingsStrings.render(
            requireContext(),
            R.string.contact_sync_info_settings_2,
            // The recovered string's destination is the literal token `onClick`, not a URL.
            onLink = { UriHandler.handle(requireContext(), CONTACT_SYNC_HELP_URL) },
        )
        contactSyncInfo.movementMethod = android.text.method.LinkMovementMethod.getInstance()

        val connection = viewState.contactSyncConnection
        contactSyncEnabled.setChecked(connection?.friendSync == true, false)
        contactSyncPhone.setChecked(
            viewState.userDiscoveryFlags and DISCOVERY_FLAG_PHONE == DISCOVERY_FLAG_PHONE,
            false,
        )
        contactSyncEmail.setChecked(
            viewState.userDiscoveryFlags and DISCOVERY_FLAG_EMAIL == DISCOVERY_FLAG_EMAIL,
            false,
        )

        contactSyncEnabled.setContainerClickListener {
            val enabling = !contactSyncEnabled.isChecked()
            if (connection != null) {
                toggleContactSync(connection, enabling)
            } else if (enabling) {
                // Nothing to toggle yet — the user has to complete the sync flow first.
                AnalyticsTracker.openModal(
                    "Contact Sync",
                    Traits.Location(page = "User Settings"),
                )
                ContactSyncFlowAnalytics.trackStart(
                    additionalProps = mapOf("location_page" to "User Settings"),
                )
                WidgetContactSync.launch(requireContext())
            }
        }
        contactSyncPhone.setContainerClickListener {
            updateFriendDiscoveryFlags(!contactSyncPhone.isChecked(), contactSyncEmail.isChecked())
        }
        contactSyncEmail.setContainerClickListener {
            updateFriendDiscoveryFlags(contactSyncPhone.isChecked(), !contactSyncEmail.isChecked())
        }

        // A staff-only escape hatch for clearing the connection while testing the flow.
        contactSyncStaffOnly.visibility = if (viewState.isMeStaff) View.VISIBLE else View.GONE
        contactSyncStaffOnly.setChecked(true, false)
        contactSyncStaffOnly.setContainerClickListener {
            if (viewState.isMeStaff) deleteContactSync()
        }
    }

    private fun toggleContactSync(contactsAccount: ConnectedAccount, syncFriends: Boolean) {
        StoreStream.getUserConnections().updateUserConnection(
            contactsAccount,
            syncFriends,
            contactsAccount.showActivity,
            contactsAccount.visibility == ConnectedAccount.VISIBLE,
        )
        // The reference also kicks off `StoreContactSync.backgroundUploadContacts()` here.
        // `StoreContactSync` exists but `StoreStream` exposes no accessor for it, so the first
        // upload waits for the next scheduled sync instead of happening immediately.
    }

    private fun deleteContactSync() {
        // The reference also calls `StoreContactSync.clearDismissStates()`; see above for why that
        // half is missing. Dropping the connection is the part that matters.
        StoreStream.getUserConnections().deleteUserConnection("contacts", "@me")
    }

    private fun updateFriendDiscoveryFlags(allowPhone: Boolean, allowEmail: Boolean) {
        var flags = if (allowPhone) DISCOVERY_FLAG_PHONE else 0
        if (allowEmail) flags = flags or DISCOVERY_FLAG_EMAIL
        StoreStream.getUserSettings().setFriendDiscoveryFlags(appActivity, flags)
    }

    // endregion

    // region data privacy controls

    private fun configurePrivacyControls(viewState: SettingsPrivacyViewModel.ViewState.Loaded) {
        val context = context ?: return
        privacyControls.visibility = View.VISIBLE

        usageStatistics.setChecked(viewState.consents.usageStatistics.consented, false)
        usageStatistics.setOnCheckedListener(
            Action1 { consented ->
                usageStatistics.isEnabled = false
                if (consented) {
                    toggleConsent(consented, RestAPIParams.Consents.Type.USAGE_STATS, usageStatistics)
                } else {
                    // Revoking is the direction Discord asks about, so a mis-tap cannot silently
                    // opt the user out of the thing they were told about.
                    confirmConsent(
                        context = context,
                        titleRes = R.string.usage_statistics_disable_modal_title,
                        bodyRes = R.string.usage_statistics_disable_modal_body,
                        cancelRes = R.string.usage_statistics_disable_modal_cancel,
                        confirmRes = R.string.usage_statistics_disable_modal_confirm,
                        toggle = usageStatistics,
                        consentType = RestAPIParams.Consents.Type.USAGE_STATS,
                    )
                }
            },
        )

        personalization.setChecked(viewState.consents.personalization.consented, false)
        personalization.setOnCheckedListener(
            Action1 { consented ->
                personalization.isEnabled = false
                if (consented) {
                    toggleConsent(consented, RestAPIParams.Consents.Type.PERSONALIZATION, personalization)
                } else {
                    confirmConsent(
                        context = context,
                        titleRes = R.string.personalization_disable_modal_title,
                        bodyRes = R.string.personalization_disable_modal_body,
                        cancelRes = R.string.personalization_disable_modal_cancel,
                        confirmRes = R.string.personalization_disable_modal_confirm,
                        toggle = personalization,
                        consentType = RestAPIParams.Consents.Type.PERSONALIZATION,
                    )
                }
            },
        )
    }

    /**
     * Shows the "are you sure you want to opt out" dialog.
     *
     * Cancelling restores the switch, because it was already moved by the time the listener ran.
     */
    private fun confirmConsent(
        context: Context,
        titleRes: Int,
        bodyRes: Int,
        cancelRes: Int,
        confirmRes: Int,
        toggle: CheckedSetting,
        consentType: String,
    ) {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.view_dialog_confirmation, null, false)
        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(false)
            .create()
        view.findViewById<TextView>(R.id.view_dialog_confirmation_header).setText(titleRes)
        view.findViewById<TextView>(R.id.view_dialog_confirmation_text).setText(bodyRes)
        view.findViewById<MaterialButton>(R.id.view_dialog_confirmation_cancel).apply {
            setText(cancelRes)
            setOnClickListener {
                dialog.dismiss()
                toggle.setChecked(true, false)
                toggle.isEnabled = true
            }
        }
        view.findViewById<MaterialButton>(R.id.view_dialog_confirmation_confirm).apply {
            setText(confirmRes)
            setOnClickListener {
                dialog.dismiss()
                toggleConsent(false, consentType, toggle)
            }
        }
        dialog.show()
    }

    private fun toggleConsent(consented: Boolean, consentType: String, toggle: CheckedSetting) {
        val api = RestAPI.apiOrNull ?: return
        val body = if (consented) {
            RestAPIParams.Consents(grant = listOf(consentType))
        } else {
            RestAPIParams.Consents(revoke = listOf(consentType))
        }
        api.setConsents(body)
            .restSubscribeOn()
            .ui(this)
            .withDimmer(dimmer, CONSENT_DIMMER_DELAY_MS)
            .appSubscribe(
                errorClass = WidgetSettingsPrivacy::class.java,
                context = context,
                errorHandler = {
                    toggle.isEnabled = true
                    // Put the switch back where the server still has it.
                    toggle.setChecked(!consented, false)
                },
            ) { toggle.isEnabled = true }
    }

    private fun setAccessibilityDetectionAllowed(allowed: Boolean) {
        StoreStream.getUserSettings().setIsAccessibilityDetectionAllowed(allowed)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetSettingsPrivacy::class.java, context) {}
    }

    private fun openAccountSettings() {
        WidgetSettingsAccount.launch(requireContext(), hintDataManagement = true)
    }

    // endregion

    // region data download

    private fun configureRequestDataButton(viewState: SettingsPrivacyViewModel.ViewState.Loaded) {
        requestData.setOnClickListener {
            val context = context ?: return@setOnClickListener
            val harvestState = viewState.harvestState
            // An unverified account cannot request its data, and neither can one whose harvest
            // status has not been read yet.
            if (!viewState.isMeVerified || harvestState == null) {
                AppToast.show(this, R.string.data_privacy_controls_request_data_tooltip)
                return@setOnClickListener
            }
            onRequestDataClick(context, harvestState)
        }
    }

    private fun onRequestDataClick(
        context: Context,
        harvestState: SettingsPrivacyViewModel.HarvestState,
    ) {
        when (harvestState) {
            is SettingsPrivacyViewModel.HarvestState.NeverRequested ->
                showRequestDataConfirmation(context)

            is SettingsPrivacyViewModel.HarvestState.LastRequested ->
                if (harvestState.harvest.canRequest()) {
                    showRequestDataConfirmation(context)
                } else {
                    // Discord only accepts one request per 30 days; say when the next one is due
                    // rather than letting the call fail.
                    showNextAvailableRequestAlert(
                        context,
                        harvestState.harvest.nextAvailableRequestInMillis(),
                    )
                }
        }
    }

    private fun showRequestDataConfirmation(context: Context) {
        WidgetNoticeDialog.Builder(context)
            .setTitle(R.string.data_privacy_controls_request_data_download)
            .setMessage(R.string.data_privacy_controls_request_data_modal_note)
            .setPositiveButton(R.string.okay) { viewModel.requestHarvest() }
            .setNegativeButton(R.string.cancel)
            .show(parentFragmentManager)
    }

    private fun showNextAvailableRequestAlert(context: Context, nextAvailableRequestMillis: Long) {
        WidgetNoticeDialog.Builder(context)
            .setTitle(R.string.data_privacy_rate_limit_title)
            .setMessage(
                SettingsStrings.render(
                    context,
                    R.string.data_download_requested_status_note,
                    "date" to TimeUtils.renderUtcDate(nextAvailableRequestMillis, context),
                ),
            )
            .setPositiveButton(R.string.okay)
            .show(parentFragmentManager)
    }

    // endregion

    companion object {
        private const val SOURCE_EVERYONE = 0
        private const val SOURCE_MUTUAL_FRIENDS = 1
        private const val SOURCE_MUTUAL_GUILDS = 2

        /** Bits of `friend_discovery_flags`. */
        private const val DISCOVERY_FLAG_PHONE = 2
        private const val DISCOVERY_FLAG_EMAIL = 4

        /** Long enough that a fast round trip does not flash the dimmer. */
        private const val CONSENT_DIMMER_DELAY_MS = 100L

        private const val PERSONALIZATION_HELP_URL =
            "https://support.discord.com/hc/en-us/articles/360004109911"
        private const val ACCESSIBILITY_DETECTION_HELP_URL =
            "https://support.discord.com/hc/en-us/articles/360035966492"
        private const val DATA_REQUEST_HELP_URL =
            "https://support.discord.com/hc/en-us/articles/360004027692"
        private const val CONTACT_SYNC_HELP_URL =
            "https://support.discord.com/hc/en-us/articles/360061878534"

        fun launch(context: Context) {
            SettingsNavigation.launch(context, WidgetSettingsPrivacy::class.java)
        }
    }
}

/**
 * State for [WidgetSettingsPrivacy].
 *
 * Reference: `WidgetSettingsPrivacy.Model`, which composes a seven-source local state with two REST
 * reads — the consents and the data-harvest status.
 *
 * The reference's `RestAPI.HarvestState` does not exist in this project, so [HarvestState] is
 * declared here. Its semantics are the reference's: a failed or 404 status read means "never
 * requested", never an error, because a user who has never requested their data gets a 404 and must
 * still be able to ask.
 */
class SettingsPrivacyViewModel(
    storeStateObservable: Observable<StoreState> = observeStoreState(),
) : AppViewModel<SettingsPrivacyViewModel.ViewState>(ViewState.Loading) {

    sealed class HarvestState {
        object NeverRequested : HarvestState()
        data class LastRequested(val harvest: Harvest) : HarvestState()
    }

    data class StoreState(
        val me: MeUser,
        val explicitContentFilter: Int,
        val defaultRestrictedGuilds: Boolean,
        val friendSourceFlags: ModelUserSettings.FriendSourceFlags?,
        val contactSyncConnection: ConnectedAccount?,
        val showContactSync: Boolean,
        val userDiscoveryFlags: Int,
        val consents: Consents,
        val harvestState: HarvestState?,
    )

    sealed class ViewState {
        /** Consents have not been read yet; the screen dims rather than showing false consent. */
        object Loading : ViewState()

        data class Loaded(
            val isMeVerified: Boolean,
            val isMeStaff: Boolean,
            val explicitContentFilter: Int,
            val defaultRestrictedGuilds: Boolean,
            val friendSourceFlags: ModelUserSettings.FriendSourceFlags?,
            val contactSyncConnection: ConnectedAccount?,
            val showContactSync: Boolean,
            val userDiscoveryFlags: Int,
            val consents: Consents,
            /** `null` while the harvest status is still being read. */
            val harvestState: HarvestState?,
        ) : ViewState()
    }

    init {
        storeStateObservable.computationLatest().distinctUntilChanged().ui(this).subscribe {
            updateViewState(toViewState(it))
        }
    }

    /** Asks Discord to start building the account's data export. */
    fun requestHarvest() {
        val api = RestAPI.apiOrNull ?: return
        api.requestHarvest()
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(SettingsPrivacyViewModel::class.java) { harvest ->
                withViewState { current ->
                    if (current is ViewState.Loaded) {
                        updateViewState(
                            current.copy(harvestState = HarvestState.LastRequested(harvest)),
                        )
                    }
                }
            }
    }

    companion object {
        /** The experiment bucket that turns the contact-sync section on. */
        private const val CONTACT_SYNC_ENABLED_BUCKET = 1
        private const val CONTACT_SYNC_EXPERIMENT = "2021-04_contact_sync_android_main"
        private const val CONTACT_SYNC_CONNECTION_TYPE = "contacts"

        internal fun toViewState(storeState: StoreState): ViewState = ViewState.Loaded(
            isMeVerified = storeState.me.isVerified,
            isMeStaff = storeState.me.isStaff,
            explicitContentFilter = storeState.explicitContentFilter,
            defaultRestrictedGuilds = storeState.defaultRestrictedGuilds,
            friendSourceFlags = storeState.friendSourceFlags,
            contactSyncConnection = storeState.contactSyncConnection,
            showContactSync = storeState.showContactSync,
            userDiscoveryFlags = storeState.userDiscoveryFlags,
            consents = storeState.consents,
            harvestState = storeState.harvestState,
        )

        fun observeStoreState(): Observable<StoreState> {
            val userSettings = StoreStream.getUserSettings()
            val localState = Observable.combineLatest(
                StoreStream.getUsers().observeMe(),
                userSettings.observeExplicitContentFilter(),
                userSettings.observeIsDefaultGuildsRestricted(),
                userSettings.observeFriendSourceFlags(),
                StoreStream.getUserConnections().observeConnectedAccounts()
                    .map(::findContactSyncConnection),
                StoreStream.getExperiments()
                    .observeUserExperiment(CONTACT_SYNC_EXPERIMENT, trackExposure = true),
                userSettings.observeFriendDiscoveryFlags(),
            ) { me, explicitContentFilter, defaultRestrictedGuilds, friendSourceFlags,
                contactSyncConnection, contactSyncExperiment, userDiscoveryFlags ->
                LocalState(
                    me = me,
                    explicitContentFilter = explicitContentFilter,
                    defaultRestrictedGuilds = defaultRestrictedGuilds,
                    friendSourceFlags = friendSourceFlags,
                    contactSyncConnection = contactSyncConnection,
                    showContactSync = contactSyncExperiment?.bucket == CONTACT_SYNC_ENABLED_BUCKET,
                    userDiscoveryFlags = userDiscoveryFlags,
                )
            }

            return localState.switchMap { local ->
                Observable.combineLatest(
                    Observable.just(local),
                    userSettings.observeConsents(),
                    observeHarvestState(local.me),
                ) { localState, consents, harvestState ->
                    StoreState(
                        me = localState.me,
                        explicitContentFilter = localState.explicitContentFilter,
                        defaultRestrictedGuilds = localState.defaultRestrictedGuilds,
                        friendSourceFlags = localState.friendSourceFlags,
                        contactSyncConnection = localState.contactSyncConnection,
                        showContactSync = localState.showContactSync,
                        userDiscoveryFlags = localState.userDiscoveryFlags,
                        consents = consents,
                        harvestState = harvestState,
                    )
                }
            }
        }

        /**
         * `null` first so the rest of the screen renders while the status is still in flight, then
         * the real status — or [HarvestState.NeverRequested] if the read fails, which is what a
         * never-requested account's 404 looks like.
         */
        private fun observeHarvestState(me: MeUser): Observable<HarvestState?> {
            if (!me.isVerified) return Observable.just<HarvestState?>(null)
            val api = RestAPI.apiOrNull ?: return Observable.just<HarvestState?>(null)
            val status: Observable<HarvestState?> = api.getHarvestStatus()
                .restSubscribeOn()
                .map<HarvestState?> { HarvestState.LastRequested(it) }
                .onErrorReturn { HarvestState.NeverRequested }
            return Observable.concat(Observable.just<HarvestState?>(null), status)
        }

        private fun findContactSyncConnection(
            state: StoreUserConnections.State,
        ): ConnectedAccount? =
            state.connectedAccounts.lastOrNull { it.type == CONTACT_SYNC_CONNECTION_TYPE }

        /** The gateway/store half of the state, before the two REST reads are folded in. */
        private data class LocalState(
            val me: MeUser,
            val explicitContentFilter: Int,
            val defaultRestrictedGuilds: Boolean,
            val friendSourceFlags: ModelUserSettings.FriendSourceFlags?,
            val contactSyncConnection: ConnectedAccount?,
            val showContactSync: Boolean,
            val userDiscoveryFlags: Int,
        )
    }
}
