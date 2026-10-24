package com.discord.stores

import android.content.Context
import android.content.SharedPreferences
import com.discord.models.user.MeUser
import com.discord.stores.authentication.AccountTokens
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.SerializedSubject

/**
 * Every account signed in on this device, in the order the user arranged them.
 *
 * Holds a display snapshot only — the tokens live in [AccountTokens]. That separation is the
 * reference's: the switcher has to draw rows for accounts it is not signed in as, and a name and
 * avatar are all it needs to do that.
 *
 * Stored beside the tokens in the session-durable preferences rather than in a
 * [com.discord.utilities.persister.Persister], because signing out clears every one of those and
 * the whole point of this list is to outlive that. The reference keeps `MultiAccountStore` out of
 * its persisted-store clear for the same reason.
 */
class StoreMultiAccount(
    private val dispatcher: Dispatcher,
    private val tokens: AccountTokens,
    private val sessionDurablePreferences: SharedPreferences,
    private val gson: Gson = Gson(),
) : StoreV2() {

    /**
     * @param tokenStatus Whether this account's stored token is still good. A rejected token is kept
     *   in the list so the row can offer to sign in again rather than silently vanishing.
     * @param pushSyncToken Lets the server fan a notification out to this account while another one
     *   holds the device registration; see [StoreNotifications].
     */
    data class Account(
        val id: Long,
        val username: String,
        val globalName: String?,
        val discriminator: Int,
        val avatar: String?,
        val tokenStatus: TokenStatus = TokenStatus.VALID,
        val pushSyncToken: String? = null,
    )

    enum class TokenStatus { VALID, VALIDATING, INVALID }

    /**
     * The selected guild with its recent history (oldest first), open guild folders and each guild's
     * last channel, put back when switching to the account again. [selectedGuildIds] is null in
     * entries saved before it existed.
     */
    data class Navigation(
        val openFolderIds: Set<Long>,
        val selectedChannelIds: Map<Long, Long>,
        val selectedGuildIds: List<Long>?,
    )

    private var accounts: List<Account> = emptyList()
    private var accountsSnapshot: List<Account> = emptyList()
    private val accountsSubject =
        SerializedSubject<List<Account>, List<Account>>(BehaviorSubject.create(emptyList()))

    private val navigation = HashMap<Long, Navigation>()

    override fun init(context: Context) {
        accounts = read()
        sessionDurablePreferences.getString(CACHE_KEY_NAVIGATION, null)
            ?.let { runCatching { gson.fromJson<Map<Long, Navigation>>(it, NAVIGATION_TYPE) }.getOrNull() }
            ?.let(navigation::putAll)
        accountsSnapshot = accounts
        accountsSubject.onNext(accounts)
    }

    fun getAccounts(): List<Account> = accountsSnapshot

    fun observeAccounts(): Observable<List<Account>> = accountsSubject.distinctUntilChanged()

    fun getAccount(userId: Long): Account? = accountsSnapshot.firstOrNull { it.id == userId }

    /** Whether another account could still be added, matching the reference's cap of five. */
    fun canAddAccount(): Boolean = accountsSnapshot.size < MAX_ACCOUNTS

    /**
     * Folds the connected user into the list.
     *
     * Also the point where the active token is written into the account's slot: a token can be
     * rotated by paths that know nothing about the account list (password change, READY handing back
     * a new one), so binding it here is what keeps the map honest.
     */
    @StoreThread
    fun handleConnectionOpen(user: MeUser, token: String?) {
        if (token != null) tokens.put(user.id, token)
        val existing = getAccountInternal(user.id)
        val updated = Account(
            id = user.id,
            username = user.username,
            globalName = user.globalName,
            discriminator = user.discriminator,
            avatar = user.avatar,
            tokenStatus = TokenStatus.VALID,
            pushSyncToken = existing?.pushSyncToken,
        )
        // Replaced where it already sits: the list order is the user's, and reconnecting is not a
        // reason to shuffle it. Overflow is dropped from the far end so a new account can still be
        // added, and its token goes with it rather than being orphaned.
        val next = if (existing != null) {
            accounts.map { if (it.id == user.id) updated else it }
        } else {
            (accounts + updated).let {
                if (it.size <= MAX_ACCOUNTS) it else it.takeLast(MAX_ACCOUNTS)
            }
        }
        (accounts.map(Account::id) - next.map(Account::id).toSet()).forEach(tokens::remove)
        publish(next)
    }

    @StoreThread
    fun handleCurrentUserUpdated(user: MeUser) {
        val existing = getAccountInternal(user.id) ?: return
        publish(
            accounts.map {
                if (it.id != user.id) {
                    it
                } else {
                    existing.copy(
                        username = user.username,
                        globalName = user.globalName,
                        discriminator = user.discriminator,
                        avatar = user.avatar,
                    )
                }
            },
        )
    }

    fun removeAccount(userId: Long) = dispatcher.schedule { removeAccountInternal(userId) }

    @StoreThread
    fun removeAccountInternal(userId: Long) {
        tokens.remove(userId)
        if (navigation.remove(userId) != null) persistNavigation()
        publish(accounts.filterNot { it.id == userId })
    }

    /**
     * Rearranges the list to [userIds].
     *
     * Keyed by id rather than by index because the drag that produced this order ran against a
     * snapshot: an account removed or added while the finger was down would otherwise shift every
     * position under it. Anything the caller did not mention keeps its place at the end.
     */
    fun setOrder(userIds: List<Long>) = dispatcher.schedule {
        val byId = accounts.associateBy { it.id }
        val reordered = userIds.mapNotNull(byId::get)
        publish(reordered + accounts.filterNot { it.id in userIds.toSet() })
    }

    fun handleTokenStatusAsync(userId: Long, status: TokenStatus) =
        dispatcher.schedule { handleTokenStatus(userId, status) }

    @StoreThread
    fun handleTokenStatus(userId: Long, status: TokenStatus) {
        val existing = getAccountInternal(userId) ?: return
        if (existing.tokenStatus == status) return
        publish(accounts.map { if (it.id == userId) it.copy(tokenStatus = status) else it })
    }

    @StoreThread
    fun handlePushSyncToken(userId: Long, syncToken: String?) {
        val existing = getAccountInternal(userId) ?: return
        if (existing.pushSyncToken == syncToken) return
        publish(accounts.map { if (it.id == userId) it.copy(pushSyncToken = syncToken) else it })
    }

    /** A rejected sync token has to be re-minted; the server tells us which ones lapsed. */
    @StoreThread
    fun handleInvalidPushSyncTokens(syncTokens: Collection<String>) {
        if (syncTokens.isEmpty()) return
        val next = accounts.map {
            if (it.pushSyncToken in syncTokens) it.copy(pushSyncToken = null) else it
        }
        if (next != accounts) publish(next)
    }

    @StoreThread
    fun saveNavigation(userId: Long, state: Navigation) {
        navigation[userId] = state
        persistNavigation()
    }

    @StoreThread
    fun getNavigation(userId: Long): Navigation? = navigation[userId]

    private fun persistNavigation() =
        sessionDurablePreferences.edit().putString(CACHE_KEY_NAVIGATION, gson.toJson(navigation)).apply()

    @StoreThread
    private fun getAccountInternal(userId: Long): Account? = accounts.firstOrNull { it.id == userId }

    @StoreThread
    private fun publish(next: List<Account>) {
        if (next == accounts) return
        accounts = next
        sessionDurablePreferences.edit().putString(CACHE_KEY_ACCOUNTS, gson.toJson(next)).apply()
        accountsSubject.onNext(next)
        markChanged()
    }

    private fun read(): List<Account> = sessionDurablePreferences.getString(CACHE_KEY_ACCOUNTS, null)
        ?.let { runCatching { gson.fromJson<List<Account>>(it, TYPE) }.getOrNull() }
        .orEmpty()

    override fun snapshotData() {
        accountsSnapshot = accounts
    }

    companion object {
        const val CACHE_KEY_ACCOUNTS = "STORE_MULTI_ACCOUNT_ACCOUNTS_V1"
        const val CACHE_KEY_NAVIGATION = "STORE_MULTI_ACCOUNT_NAVIGATION_V1"
        const val MAX_ACCOUNTS = 5
        private val TYPE = object : TypeToken<List<Account>>() {}.type
        private val NAVIGATION_TYPE = object : TypeToken<Map<Long, Navigation>>() {}.type
    }
}
