package com.discord.widgets.contact_sync

import androidx.annotation.MainThread
import com.discord.api.connectedaccounts.ConnectedAccount
import com.discord.api.friendsuggestions.AllowedInSuggestionsType
import com.discord.api.friendsuggestions.BulkAddFriendsResponse
import com.discord.api.friendsuggestions.BulkFriendSuggestions
import com.discord.api.friendsuggestions.FriendSuggestion
import com.discord.app.AppViewModel
import com.discord.models.phone.PhoneCountryCode
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.error.RestApiError
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.user.UserUtils.getUserNameWithDiscriminator
import com.discord.widgets.user.phone.WidgetUserPhoneManage
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.subjects.PublishSubject

/**
 * `WidgetContactSyncViewModelKt` in the reference: two shared toolbar configurations.
 *
 * Onboarding cannot go back (there is nothing behind it) but can skip; every other entry point can
 * go back but has no skip affordance on the landing step.
 */
private val TOOLBAR_CONFIG_DEFAULT = WidgetContactSyncViewModel.ToolbarConfig(
    showBackButton = true,
    showSkip = false,
)
private val TOOLBAR_CONFIG_ONBOARDING = WidgetContactSyncViewModel.ToolbarConfig(
    showBackButton = false,
    showSkip = true,
)

/**
 * The state the flow opens on: the landing step, nothing submitted, the CTA disabled until the
 * stores answer, and the toolbar the entry point implies.
 */
private fun initialContactSyncViewState(
    mode: ContactSyncMode,
    allowPhone: Boolean,
    allowEmail: Boolean,
) = WidgetContactSyncViewModel.ViewState(
    landingNextEnabled = false,
    mode = mode,
    phoneNumber = null,
    existingConnection = null,
    countryCode = PhoneCountryCode.DEFAULT_COUNTRY_CODE,
    username = "",
    name = null,
    isSubmitting = false,
    permissionsDenied = false,
    displayedChild = WidgetContactSyncViewModel.Views.VIEW_LANDING,
    allowPhone = allowPhone,
    allowEmail = allowEmail,
    bulkAddToken = null,
    friendSuggestions = emptyList(),
    selectedFriendIds = emptyList(),
    toolbarConfig = when (mode) {
        ContactSyncMode.ONBOARDING -> TOOLBAR_CONFIG_ONBOARDING
        ContactSyncMode.DEFAULT -> TOOLBAR_CONFIG_DEFAULT
    },
)

/**
 * Drives the whole contact-sync flow: landing → add phone → verify phone → name → suggestions.
 *
 * The screen is a single `AppViewFlipper`, so this class owns which child is displayed as part of
 * its view state rather than navigating between fragments. It is scoped to the *activity* so the
 * two bottom sheets (upsell, permissions) share the same instance and their discovery toggles stay
 * in step with the main screen's.
 *
 * PRIVACY: [ViewState] and [StoreState] carry the user's own phone number and typed name, and
 * [Item.FriendSuggestionItem] carries a contact-book nickname. All three override `toString` to
 * redact them, because the generated data-class `toString` would otherwise put an address-book
 * entry into any log line, crash report, or debugger snapshot that prints the state. Uploaded
 * contact numbers never enter the view state at all — [onContactsFetched] passes them straight to
 * the REST layer, whose request bodies are already redacted.
 */
class WidgetContactSyncViewModel(
    mode: ContactSyncMode,
    allowPhone: Boolean = true,
    allowEmail: Boolean = true,
    private val restAPI: RestAPIInterface = RestAPI.api,
    storeObservable: Observable<WidgetContactSyncViewModel.StoreState> = observeStores(),
    val tracker: ContactSyncFlowAnalytics = ContactSyncFlowAnalytics(mode == ContactSyncMode.ONBOARDING),
    private var initialized: Boolean = false,
) : AppViewModel<WidgetContactSyncViewModel.ViewState>(
    initialContactSyncViewState(mode, allowPhone, allowEmail),
) {

    private val eventsSubject: PublishSubject<Event> = PublishSubject.create()

    init {
        storeObservable
            .ui(this)
            .appSubscribe(WidgetContactSyncViewModel::class.java) { handleStoreState(it) }
    }

    // region state

    /** Which flipper child is on screen. Ordinals match the child order of `widget_contact_sync`. */
    enum class Views(val trackingStep: String) {
        VIEW_LANDING("Landing"),
        VIEW_ADD_PHONE("Add Phone Number"),
        VIEW_VERIFY_PHONE("Verify Phone Number"),
        VIEW_NAME_INPUT("Name Input"),
        VIEW_SUGGESTIONS("Suggestions Results"),

        /** Shares its tracking step with [VIEW_SUGGESTIONS]; the funnel treats them as one. */
        VIEW_SUGGESTIONS_EMPTY("Suggestions Results"),
    }

    data class ToolbarConfig(val showBackButton: Boolean, val showSkip: Boolean)

    data class StoreState(
        val userPhone: String?,
        val username: String,
        val countryCode: PhoneCountryCode,
        val contactsConnection: ConnectedAccount?,
    ) {
        override fun toString(): String = "StoreState(userPhone=<redacted>, username=$username, " +
            "countryCode=$countryCode, contactsConnection=$contactsConnection)"
    }

    /** A row in the friend-suggestion list. */
    sealed class Item(override val type: Int) : MGRecyclerDataPayload {

        data class FriendSuggestionItem(
            val suggestion: FriendSuggestion,
            val selected: Boolean,
        ) : Item(TYPE_FRIEND_SUGGESTION) {
            override val key: String get() = suggestion.suggestedUser.id.toString()

            // `suggestion.reasons[].name` is the name the *user's own address book* has for this
            // person. Never print it.
            override fun toString(): String =
                "FriendSuggestionItem(userId=${suggestion.suggestedUser.id}, selected=$selected)"
        }

        companion object {
            const val TYPE_FRIEND_SUGGESTION = 0
        }
    }

    data class ViewState(
        val landingNextEnabled: Boolean,
        val mode: ContactSyncMode,
        val phoneNumber: String?,
        val existingConnection: ConnectedAccount?,
        val countryCode: PhoneCountryCode,
        val username: String,
        val name: String?,
        val isSubmitting: Boolean,
        val permissionsDenied: Boolean,
        val displayedChild: Views,
        val allowPhone: Boolean,
        val allowEmail: Boolean,
        val bulkAddToken: String?,
        val friendSuggestions: List<Item>,
        val selectedFriendIds: List<Long>,
        val toolbarConfig: ToolbarConfig,
    ) {
        /** Both the phone number and the typed real name are personal data; neither is printed. */
        override fun toString(): String = "ViewState(landingNextEnabled=$landingNextEnabled, " +
            "mode=$mode, phoneNumber=<redacted>, existingConnection=$existingConnection, " +
            "countryCode=$countryCode, username=$username, name=<redacted>, " +
            "isSubmitting=$isSubmitting, permissionsDenied=$permissionsDenied, " +
            "displayedChild=$displayedChild, allowPhone=$allowPhone, allowEmail=$allowEmail, " +
            "bulkAddToken=<redacted>, friendSuggestions=${friendSuggestions.size}, " +
            "selectedFriendIds=${selectedFriendIds.size}, toolbarConfig=$toolbarConfig)"
    }

    /** One-shot outcomes the screen reacts to; none of them are part of the rendered state. */
    sealed class Event {
        /** The store state arrived for the first time; honour an "immediately proceed" launch. */
        object MaybeProceedFromLanding : Event()
        object PermissionsNeeded : Event()
        object ContactsEnabled : Event()
        object Completed : Event()
        object RateLimited : Event()
        object ContactsEnableFailed : Event()
        object UploadFailed : Event()
        object AddFriendsFailed : Event()
        object AddFriendsFailedPartial : Event()
        object PhoneInvalid : Event()
        object VerificationCodeInvalid : Event()
        object VerificationFailed : Event()
    }

    // endregion

    fun observeEvents(): Observable<Event> = eventsSubject

    // region flow

    @MainThread
    fun onLandingNext() {
        val viewState = this.viewState ?: return
        if (viewState.phoneNumber == null) {
            tracker.trackFlowStep(Views.VIEW_ADD_PHONE.trackingStep, skip = false, back = false)
            updateViewState(
                viewState.copy(
                    displayedChild = Views.VIEW_ADD_PHONE,
                    toolbarConfig = TOOLBAR_CONFIG_DEFAULT,
                ),
            )
            return
        }
        eventsSubject.onNext(Event.PermissionsNeeded)
    }

    @MainThread
    fun onPhoneNumberSubmitted(phoneNumber: String) {
        restAPI.userAddPhone(
            RestAPIParams.Phone(
                phone = phoneNumber,
                source = WidgetUserPhoneManage.Source.CONTACT_SYNC.source,
            ),
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetContactSyncViewModel::class.java,
                errorHandler = { handlePhoneSubmittedError(it) },
            ) { handlePhoneSubmitted() }
    }

    @MainThread
    fun onVerifyPhone(code: String) {
        restAPI.userAddPhoneNoPassword(RestAPIParams.VerificationCodeOnly(code))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetContactSyncViewModel::class.java,
                errorHandler = { handlePhoneVerifiedError(it) },
            ) { handlePhoneVerified() }
    }

    /** Called just before the runtime permission prompt goes up. */
    @MainThread
    fun requestingPermissions() {
        tracker.trackFlowStep(PERMISSION_REQUESTED_STEP, skip = false, back = false)
    }

    @MainThread
    fun onPermissionsGranted() {
        val viewState = this.viewState ?: return
        // Coming back to the screen after the suggestions are already on display must not restart
        // the flow at the name step.
        if (viewState.displayedChild == Views.VIEW_SUGGESTIONS ||
            viewState.displayedChild == Views.VIEW_SUGGESTIONS_EMPTY
        ) {
            return
        }
        ContactSyncDependencies.analytics.permissionsAcked(CONTACTS_PERMISSION, granted = true)
        tracker.trackFlowStep(Views.VIEW_NAME_INPUT.trackingStep, skip = false, back = false)
        val name = viewState.name
        if (name != null) {
            onNameSubmitted(name)
        } else {
            updateViewState(
                viewState.copy(
                    displayedChild = Views.VIEW_NAME_INPUT,
                    toolbarConfig = ToolbarConfig(showBackButton = false, showSkip = true),
                ),
            )
        }
    }

    @MainThread
    fun onPermissionsDenied() {
        tracker.trackFlowStep(
            Views.VIEW_LANDING.trackingStep,
            skip = true,
            back = false,
            additionalProps = mapOf("mobile_contacts_permission" to "denied"),
        )
        ContactSyncDependencies.analytics.permissionsAcked(CONTACTS_PERMISSION, granted = false)
        val viewState = this.viewState ?: return
        updateViewState(viewState.copy(permissionsDenied = true))
    }

    /** The user granted the permission outside the app and came back. */
    @MainThread
    fun onPermissionsBecameAvailable() {
        val viewState = this.viewState ?: return
        updateViewState(viewState.copy(permissionsDenied = false))
    }

    @MainThread
    fun onPermissionsToggle(allowPhone: Boolean, allowEmail: Boolean) {
        ContactSyncDependencies.analytics.contactSyncToggled(false, allowPhone, allowEmail)
        val viewState = this.viewState ?: return
        updateViewState(viewState.copy(allowPhone = allowPhone, allowEmail = allowEmail))
    }

    /**
     * Creates (or updates) the `contacts` connection under [name] and stores the discovery flags.
     *
     * An empty or whitespace-only name is sent as `null`, which is how the user opts out of being
     * discoverable by name while still syncing.
     */
    @MainThread
    fun onNameSubmitted(name: String) {
        ContactSyncDependencies.analytics.nameSubmitted(
            wordCount = name.split(" ").size,
            characterCount = name.length,
        )
        val submittedName = if (name.isBlank()) null else name.trim()
        val viewState = this.viewState ?: return
        // The raw text is kept so re-entering the flow does not ask again; it is redacted in
        // `ViewState.toString`.
        updateViewState(viewState.copy(name = name))

        val existingConnection = viewState.existingConnection
        if (existingConnection != null) {
            restAPI.updateConnection(
                existingConnection.type,
                existingConnection.id,
                RestAPIParams.ConnectedAccount(
                    friendSync = true,
                    id = existingConnection.id,
                    name = submittedName,
                    revoked = existingConnection.revoked,
                    showActivity = existingConnection.showActivity,
                    type = existingConnection.type,
                    verified = existingConnection.verified,
                    visibility = existingConnection.visibility,
                ),
            )
                .restSubscribeOn()
                .ui(this)
                .appSubscribe(
                    errorClass = WidgetContactSyncViewModel::class.java,
                    errorHandler = { handleContactsEnableError(it) },
                ) { handleContactsEnabled() }
        } else {
            restAPI.createConnectionContacts(
                RestAPIParams.ConnectedAccountContacts(submittedName, friendSync = true),
            )
                .restSubscribeOn()
                .ui(this)
                .appSubscribe(
                    errorClass = WidgetContactSyncViewModel::class.java,
                    errorHandler = { handleContactsEnableError(it) },
                ) { handleContactsEnabled() }
        }

        var friendDiscoveryFlags = if (viewState.allowPhone) FLAG_DISCOVERABLE_BY_PHONE else 0
        if (viewState.allowEmail) friendDiscoveryFlags = friendDiscoveryFlags or FLAG_DISCOVERABLE_BY_EMAIL
        restAPI.updateUserSettings(
            RestAPIParams.UserSettings.createWithFriendDiscoveryFlags(friendDiscoveryFlags),
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetContactSyncViewModel::class.java,
                errorHandler = { handleContactsEnableError(it) },
            ) { }
    }

    /**
     * Uploads the address book.
     *
     * [contactNumbers] are normalised phone numbers straight from [ContactsProvider]; they are
     * turned into request entries and dropped. Nothing here is logged, cached, or written to the
     * view state.
     */
    @MainThread
    fun onContactsFetched(contactNumbers: Set<String>) {
        val viewState = this.viewState ?: return
        updateViewState(viewState.copy(isSubmitting = true))
        val entries = contactNumbers.map { number ->
            RestAPIParams.ContactEntry(number, number, mapOf("number" to number))
        }
        val payload = RestAPIParams.UploadContacts(
            friendListEntries = entries,
            background = false,
            setUseForSuggestions = AllowedInSuggestionsType.ANYONE_WITH_CONTACT_INFO,
        )
        // Recorded before the call so a failure still counts against the daily upload budget, which
        // is what stops a broken account from re-uploading on every launch.
        StoreStream.getContactSync().setContactSyncUploadTimestamp(ClockFactory.get().currentTimeMillis())
        restAPI.uploadContacts(payload)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetContactSyncViewModel::class.java,
                errorHandler = { handleUploadError(it) },
            ) { response -> response.body()?.let { handleFriendSuggestions(it) } }
    }

    @MainThread
    fun handleToggleFriendSuggestionSelected(userId: Long, selected: Boolean) {
        val viewState = this.viewState ?: return
        val selectedFriendIds = viewState.selectedFriendIds.toMutableList()
        if (selected) selectedFriendIds.add(userId) else selectedFriendIds.remove(userId)
        updateViewState(viewState.copy(selectedFriendIds = selectedFriendIds))
    }

    @MainThread
    fun onBulkAddFriends() {
        val viewState = this.viewState ?: return
        val bulkAddToken = viewState.bulkAddToken ?: return
        val selectedFriendIds = viewState.selectedFriendIds
        if (selectedFriendIds.isEmpty()) {
            tracker.trackEnd(
                skip = false,
                additionalProps = mapOf(
                    "num_contacts_found" to viewState.friendSuggestions.size,
                    "num_contacts_added" to 0,
                ),
            )
            handleComplete()
            return
        }
        updateViewState(viewState.copy(isSubmitting = true))
        restAPI.bulkAddRelationships(RestAPIParams.UserBulkRelationship(selectedFriendIds, bulkAddToken))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetContactSyncViewModel::class.java,
                errorHandler = { handleFriendsAddedError(it) },
            ) { response -> response.body()?.let { handleFriendsAdded(it) } }
    }

    /**
     * The toolbar's back arrow and skip action, and the system back press.
     *
     * Each step decides for itself what "skip" means: the landing step ends the flow, the two phone
     * steps walk backwards, the name step submits an empty name, and the suggestion steps finish
     * without adding anyone.
     */
    @MainThread
    fun skip() {
        val viewState = this.viewState ?: return
        when (viewState.displayedChild) {
            Views.VIEW_LANDING -> {
                tracker.trackEnd(skip = true)
                handleComplete()
            }
            Views.VIEW_ADD_PHONE -> {
                tracker.trackFlowStep(Views.VIEW_LANDING.trackingStep, skip = false, back = true)
                updateViewState(
                    viewState.copy(
                        displayedChild = Views.VIEW_LANDING,
                        toolbarConfig = getLandingToolbarConfig(viewState.mode),
                    ),
                )
            }
            Views.VIEW_VERIFY_PHONE -> {
                tracker.trackFlowStep(Views.VIEW_ADD_PHONE.trackingStep, skip = false, back = true)
                updateViewState(viewState.copy(displayedChild = Views.VIEW_ADD_PHONE))
            }
            Views.VIEW_NAME_INPUT -> onNameSubmitted("")
            Views.VIEW_SUGGESTIONS,
            Views.VIEW_SUGGESTIONS_EMPTY,
            -> {
                tracker.trackEnd(
                    skip = false,
                    additionalProps = mapOf(
                        "num_contacts_found" to viewState.friendSuggestions.size,
                        "num_contacts_added" to 0,
                    ),
                )
                handleComplete()
            }
        }
    }

    /** Dismisses the release upsell for good, locally and server-side. */
    @MainThread
    fun dismissUpsell() {
        StoreStream.getContactSync().dismissUpsell()
        StoreStream.getUserSettings().updateContactSyncShown()
    }

    // endregion

    // region handlers

    private fun handleStoreState(storeState: StoreState) {
        val viewState = this.viewState ?: return
        updateViewState(
            viewState.copy(
                landingNextEnabled = true,
                phoneNumber = storeState.userPhone,
                existingConnection = storeState.contactsConnection,
                countryCode = storeState.countryCode,
                username = storeState.username,
            ),
        )
        if (initialized) return
        initialized = true
        eventsSubject.onNext(Event.MaybeProceedFromLanding)
    }

    @MainThread
    private fun handlePhoneSubmitted() {
        val viewState = this.viewState ?: return
        tracker.trackFlowStep(Views.VIEW_VERIFY_PHONE.trackingStep, skip = false, back = false)
        updateViewState(viewState.copy(displayedChild = Views.VIEW_VERIFY_PHONE))
    }

    @MainThread
    private fun handlePhoneSubmittedError(error: RestApiError) {
        eventsSubject.onNext(Event.PhoneInvalid)
    }

    @MainThread
    private fun handlePhoneVerified() {
        val viewState = this.viewState ?: return
        updateViewState(
            viewState.copy(
                displayedChild = Views.VIEW_LANDING,
                toolbarConfig = getLandingToolbarConfig(viewState.mode),
            ),
        )
        eventsSubject.onNext(Event.PermissionsNeeded)
    }

    @MainThread
    private fun handlePhoneVerifiedError(error: RestApiError) {
        // A field-level message means the code itself was wrong; anything else is a transport or
        // server failure the user cannot fix by retyping.
        if (error.messages.isNotEmpty()) {
            eventsSubject.onNext(Event.VerificationCodeInvalid)
        } else {
            eventsSubject.onNext(Event.VerificationFailed)
        }
    }

    @MainThread
    private fun handleContactsEnabled() {
        val viewState = this.viewState ?: return
        ContactSyncDependencies.analytics.contactSyncToggled(
            true,
            viewState.allowPhone,
            viewState.allowEmail,
        )
        eventsSubject.onNext(Event.ContactsEnabled)
    }

    @MainThread
    private fun handleContactsEnableError(error: RestApiError) {
        eventsSubject.onNext(
            if (error.type == RestApiError.Type.RATE_LIMITED) Event.RateLimited else Event.ContactsEnableFailed,
        )
        val viewState = this.viewState ?: return
        updateViewState(
            viewState.copy(
                displayedChild = Views.VIEW_LANDING,
                toolbarConfig = getLandingToolbarConfig(viewState.mode),
            ),
        )
    }

    @MainThread
    private fun handleUploadError(error: RestApiError) {
        val viewState = this.viewState ?: return
        updateViewState(
            viewState.copy(
                isSubmitting = false,
                displayedChild = Views.VIEW_LANDING,
                toolbarConfig = getLandingToolbarConfig(viewState.mode),
            ),
        )
        eventsSubject.onNext(
            if (error.type == RestApiError.Type.RATE_LIMITED) Event.RateLimited else Event.UploadFailed,
        )
    }

    /**
     * Only suggestions that came from the contact upload are shown; the same endpoint also returns
     * suggestions from other sources, which do not belong in this flow.
     */
    @MainThread
    private fun handleFriendSuggestions(bulkFriendSuggestions: BulkFriendSuggestions) {
        val viewState = this.viewState ?: return
        if (bulkFriendSuggestions.friendSuggestions.isEmpty()) {
            tracker.trackFlowStep(
                Views.VIEW_SUGGESTIONS_EMPTY.trackingStep,
                skip = false,
                back = false,
                additionalProps = mapOf("num_contacts_found" to 0),
            )
            updateViewState(
                viewState.copy(
                    isSubmitting = false,
                    displayedChild = Views.VIEW_SUGGESTIONS_EMPTY,
                    toolbarConfig = ToolbarConfig(showBackButton = false, showSkip = false),
                ),
            )
            return
        }
        StoreStream.getFriendSuggestions().updateFriendSuggestions(bulkFriendSuggestions.friendSuggestions)
        val contactSuggestions = bulkFriendSuggestions.friendSuggestions.filter { suggestion ->
            suggestion.reasons.any { it.platformType == CONTACTS_PLATFORM_TYPE }
        }
        tracker.trackFlowStep(
            Views.VIEW_SUGGESTIONS.trackingStep,
            skip = false,
            back = false,
            additionalProps = mapOf("num_contacts_found" to contactSuggestions.size),
        )
        updateViewState(
            viewState.copy(
                isSubmitting = false,
                displayedChild = Views.VIEW_SUGGESTIONS,
                bulkAddToken = bulkFriendSuggestions.bulkAddToken,
                // Everyone is pre-selected; the user unchecks the people they do not want.
                friendSuggestions = contactSuggestions.map { Item.FriendSuggestionItem(it, true) },
                selectedFriendIds = contactSuggestions.map { it.suggestedUser.id },
                toolbarConfig = ToolbarConfig(showBackButton = false, showSkip = true),
            ),
        )
    }

    @MainThread
    private fun handleFriendsAdded(response: BulkAddFriendsResponse) {
        val viewState = this.viewState ?: return
        updateViewState(viewState.copy(isSubmitting = false))
        tracker.trackEnd(
            skip = false,
            additionalProps = mapOf(
                "num_contacts_found" to viewState.friendSuggestions.size,
                "num_contacts_added" to response.successfulRequests.size,
            ),
        )
        if (response.failedRequests.isNotEmpty()) {
            eventsSubject.onNext(Event.AddFriendsFailedPartial)
            return
        }
        handleComplete()
    }

    @MainThread
    private fun handleFriendsAddedError(error: RestApiError) {
        val viewState = this.viewState ?: return
        updateViewState(viewState.copy(isSubmitting = false))
        tracker.trackEnd(
            skip = false,
            additionalProps = mapOf(
                "num_contacts_found" to viewState.friendSuggestions.size,
                "num_contacts_added" to 0,
            ),
        )
        // REFERENCE BEHAVIOUR, KEPT: only a *field-level* failure raises the dialog. A network or
        // 5xx failure leaves the user on the suggestion list with the generic error toast that
        // `appSubscribe` already showed, so they can retry.
        if (error.messages.isNotEmpty()) {
            eventsSubject.onNext(Event.AddFriendsFailed)
        }
    }

    private fun handleComplete() {
        val viewState = this.viewState ?: return
        if (viewState.mode == ContactSyncMode.ONBOARDING) {
            StoreStream.getNux().setContactSyncCompleted(true)
        }
        eventsSubject.onNext(Event.Completed)
    }

    private fun getLandingToolbarConfig(mode: ContactSyncMode): ToolbarConfig = when (mode) {
        ContactSyncMode.ONBOARDING -> TOOLBAR_CONFIG_ONBOARDING
        ContactSyncMode.DEFAULT -> TOOLBAR_CONFIG_DEFAULT
    }

    // endregion

    companion object {
        private const val CONTACTS_PLATFORM_TYPE = "contacts"
        private const val CONTACTS_PERMISSION = "contacts"
        private const val PERMISSION_REQUESTED_STEP = "Contacts Permission Requested"

        /** `user_settings.friend_discovery_flags` bits. */
        private const val FLAG_DISCOVERABLE_BY_PHONE = 2
        private const val FLAG_DISCOVERABLE_BY_EMAIL = 4

        /**
         * The user's own phone number, display name, chosen country code and existing `contacts`
         * connection.
         *
         * Throttled on the leading edge because three stores feed it and a `READY` burst would
         * otherwise rebuild the state several times in a row.
         *
         * The three stores are observed together so READY produces one stable state after the
         * leading-edge throttle.
         */
        @JvmStatic
        fun observeStores(
            observationDeck: ObservationDeck = ObservationDeckProvider.get(),
        ): Observable<StoreState> {
            val users = StoreStream.getUsers()
            val phone = StoreStream.getPhone()
            val userConnections = StoreStream.getUserConnections()
            return observationDeck.connectRx(users, phone, userConnections)
                .leadingEdgeThrottle(1L, TimeUnit.SECONDS)
                .map {
                    val me = users.getMe()
                    StoreState(
                        userPhone = me.phoneNumber,
                        username = me.getUserNameWithDiscriminator().toString(),
                        countryCode = phone.getCountryCode(),
                        // `lastOrNull`, not `firstOrNull`: an account can hold more than one
                        // `contacts` connection and the most recent one is authoritative.
                        contactsConnection = userConnections.getConnectedAccounts()
                            ?.lastOrNull { it.type == CONTACTS_PLATFORM_TYPE },
                    )
                }
        }
    }
}
