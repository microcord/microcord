package com.discord.stores

import android.content.Context
import com.discord.api.friendsuggestions.AllowedInSuggestionsType
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.utilities.persister.Persister
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.time.Clock
import com.discord.utilities.user.UserUtils.getAgeMs
import com.discord.utilities.user.UserUtils.hasPhone
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.analytics.Traits
import com.discord.widgets.contact_sync.AndroidContactsProvider
import com.discord.widgets.contact_sync.ContactSyncFlowAnalytics
import com.discord.widgets.contact_sync.ContactSyncUpsellSheet

/**
 * Contact-sync upsell state and the daily background contact upload.
 *
 * Two dismissals are tracked separately because they mean different things: the release upsell
 * notice is dismissed *permanently* (and is mirrored into session-durable prefs so a cache wipe
 * does not resurrect it), while the friends-list upsell is dismissed for the current install only.
 *
 * The upsell is deliberately narrow — it only appears for an account that is at least a week old,
 * has a phone number, has no contacts connection yet, and is bucketed into the rollout experiment.
 * Anything looser would ask brand-new accounts for their address book on first launch.
 */
class StoreContactSync(
    private val dispatcher: Dispatcher,
    private val clock: Clock,
    private val connectionsStore: StoreUserConnections,
    private val usersStore: StoreUser,
    private val userSettingsStore: StoreUserSettings,
    private val api: () -> RestAPIInterface? = { null },
    /** Optional test replacement for the device contact reader. */
    private val fetchContacts: (() -> Set<String>)? = null,
    /** `StoreNotices.requestToShow(createContactSyncNotice())`. */
    private val requestToShowUpsellNotice: (() -> Unit)? = null,
) : StoreV2() {
    private val dismissState = HashMap<String, Boolean>()
    private val dismissStateCache = Persister<Map<String, Boolean>>(DISMISS_STATE_CACHE_KEY, HashMap())
    private val uploadTimestampCache = Persister(LAST_UPLOAD_TIME_CACHE_KEY, 0L)
    private var shouldTryUploadContacts = false
    private lateinit var context: Context

    override fun init(context: Context) {
        super.init(context)
        this.context = context
        dismissState.putAll(dismissStateCache.get())
        // The Kryo-backed dismissal predates the session-durable one; carry it across once so an
        // upgrading install does not see the upsell again.
        if (dismissState[UPSELL_DISMISS_KEY] == true) {
            prefsSessionDurable.edit().putBoolean(UPSELL_DISMISS_KEY, true).apply()
        }
        dismissState[UPSELL_DISMISS_KEY] = prefsSessionDurable.getBoolean(UPSELL_DISMISS_KEY, false)
        shouldTryUploadContacts = clock.currentTimeMillis() - uploadTimestampCache.get() > UPLOAD_INTERVAL_MS
        markChanged()
    }

    fun getFriendsListUpsellDismissed(): Boolean = dismissState[FRIENDS_UPSELL_DISMISS_KEY] == true

    @StoreThread
    fun handleConnectionOpen() = dispatcher.schedule { handlePostConnectionOpen() }

    /**
     * Runs a dispatch *after* `READY` so connections, settings and experiments are all populated;
     * evaluating on the READY dispatch itself would race the stores this reads.
     */
    @StoreThread
    fun handlePostConnectionOpen() {
        val me = usersStore.getMe()
        val contactsConnection = connectionsStore.getConnectedAccounts()
            .lastOrNull { it.type == CONTACTS_CONNECTION_TYPE }
        val upsellDismissed = dismissState[UPSELL_DISMISS_KEY]
        val upsellShownServerSide = userSettingsStore.getContactSyncUpsellShown()

        val shouldShowUpsell = contactsConnection == null &&
            me.hasPhone &&
            upsellDismissed != true &&
            upsellShownServerSide != true &&
            me.getAgeMs(clock) > EXISTING_ACCOUNT_AGE_THRESHOLD_MS

        // Locally dismissed but the server has not been told: record it so other clients agree.
        if (upsellDismissed == true && upsellShownServerSide != true) {
            userSettingsStore.updateContactSyncShown()
        }
        if (shouldShowUpsell) {
            requestToShowUpsellNotice?.invoke()
                ?: StoreStream.getNotices().requestToShow(createContactSyncNotice())
        }
        if (shouldTryUploadContacts) backgroundUploadContacts()
    }

    fun backgroundUploadContacts() {
        val api = api() ?: return
        val contacts = fetchContacts?.invoke() ?: AndroidContactsProvider.allContactPhoneNumbers(context)
        val entries = contacts.map { contact ->
            RestAPIParams.ContactEntry(contact, contact, mapOf("number" to contact))
        }
        api.uploadContacts(
            RestAPIParams.UploadContacts(entries, true, AllowedInSuggestionsType.ANYONE_WITH_CONTACT_INFO),
        ).restSubscribeOn().appSubscribe(StoreContactSync::class.java) {}
        setContactSyncUploadTimestamp(clock.currentTimeMillis())
    }

    fun setContactSyncUploadTimestamp(timestamp: Long) {
        uploadTimestampCache.set(timestamp, persist = true)
        dispatcher.schedule {
            shouldTryUploadContacts = clock.currentTimeMillis() - timestamp > UPLOAD_INTERVAL_MS
            markChanged()
        }
    }

    fun dismissUpsell() {
        prefsSessionDurable.edit().putBoolean(UPSELL_DISMISS_KEY, true).apply()
        dispatcher.schedule {
            dismissState[UPSELL_DISMISS_KEY] = true
            dismissStateCache.set(HashMap(dismissState), persist = true)
            markChanged()
        }
    }

    fun dismissFriendsListUpsell() {
        dispatcher.schedule {
            dismissState[FRIENDS_UPSELL_DISMISS_KEY] = true
            dismissStateCache.set(HashMap(dismissState), persist = true)
            markChanged()
        }
    }

    fun clearDismissStates() {
        prefsSessionDurable.edit().remove(UPSELL_DISMISS_KEY).apply()
        dispatcher.schedule {
            dismissState.clear()
            dismissStateCache.clear(persist = true)
            markChanged()
        }
    }

    private fun createContactSyncNotice() = StoreNotices.Notice(
        name = NOTICE_NAME,
        priority = 0,
        persist = true,
    ) { activity ->
        AnalyticsTracker.openPopout("Contact Sync", Traits.Location(page = "Release Upsell"))
        ContactSyncFlowAnalytics.trackStart(
            additionalProps = mapOf("location_page" to "Release Upsell"),
        )
        ContactSyncUpsellSheet.show(activity.supportFragmentManager)
        true
    }

    companion object {
        internal const val NOTICE_NAME = "CONTACT_SYNC_UPSELL"
        private const val CONTACTS_CONNECTION_TYPE = "contacts"
        private const val DISMISS_STATE_CACHE_KEY = "CONTACT_SYNC_DISMISS_STATE"
        private const val LAST_UPLOAD_TIME_CACHE_KEY = "CONTACT_SYNC_LAST_UPLOAD_TIME"
        private const val UPSELL_DISMISS_KEY = "CONTACT_SYNC_DISMISS_UPSELL"
        private const val FRIENDS_UPSELL_DISMISS_KEY = "CONTACT_SYNC_DISMISS_FRIENDS_UPSELL"
        private const val EXISTING_ACCOUNT_AGE_THRESHOLD_MS = 604_800_000L
        private const val UPLOAD_INTERVAL_MS = 86_400_000L
    }
}
