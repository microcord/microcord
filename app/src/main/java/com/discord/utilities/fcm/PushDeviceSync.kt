package com.discord.utilities.fcm

import com.discord.api.notification.DeviceSyncResponse
import com.discord.app.AppLog
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreMultiAccount
import com.discord.stores.authentication.AccountTokens
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.restSubscribeOn
import retrofit2.HttpException
import rx.Observable

/**
 * Registers this device's push token against every signed-in account.
 *
 * A device token identifies the install, not the account, so registering it a second time would
 * simply hand it to whoever registered last and silence the others. Each account instead mints a
 * sync token using its own credentials, and the active account submits the whole set.
 *
 * With a single account there is nothing to fan out to, and the reference keeps using the plain
 * registration for that case.
 */
internal object PushDeviceSync {

    fun register(
        accounts: List<StoreMultiAccount.Account>,
        tokens: AccountTokens,
        currentUserId: Long,
        pushToken: String?,
        onSyncToken: (userId: Long, syncToken: String?) -> Unit,
        onTokenRejected: (userId: Long) -> Unit,
        onInvalidated: (List<String>) -> Unit,
        persisted: (Boolean) -> Unit,
    ) {
        val api = RestAPI.apiOrNull ?: return persisted(false)
        val ordered = accounts.sortedByDescending { it.id == currentUserId }
        if (ordered.size < 2 || ordered.firstOrNull()?.id != currentUserId) {
            return registerDevice(pushToken, persisted)
        }

        Observable.from(ordered)
            .concatMap { account ->
                syncToken(api, account, tokens, onSyncToken, onTokenRejected).map { account.id to it }
            }
            .toList()
            .flatMap { minted ->
                val syncTokens = minted.mapNotNull { it.second }
                // The active account is the one whose owner is holding the phone. If its token is
                // the one that died, fan-out is pointless, but a plain registration still keeps
                // that account notified — better than submitting a set it is missing from.
                if (minted.firstOrNull { it.first == currentUserId }?.second == null) {
                    return@flatMap api.userCreateDevice(RestAPIParams.UserDevices(pushToken))
                        .restSubscribeOn(retry = false)
                        .map { DeviceSyncResponse() }
                }
                api.userSyncDevices(RestAPIParams.UserDevicesSync(pushToken, syncTokens))
                    .restSubscribeOn(retry = false)
            }
            .subscribe(
                { response -> onInvalidated(response.invalidPushSyncTokens.orEmpty()) },
                { persisted(false) },
                { persisted(true) },
            )
    }

    /**
     * Mints one account's sync token, or reports none.
     *
     * Never fails the stream: an account whose token has been revoked, or whose owner is locked
     * out, would otherwise take every other account's notifications down with it. A refusal is
     * distinguished from a transient failure — only the former means the stored token is spent, and
     * saying so is what lets the switcher offer to sign in again instead of silently doing nothing.
     */
    private fun syncToken(
        api: RestAPIInterface,
        account: StoreMultiAccount.Account,
        tokens: AccountTokens,
        onSyncToken: (userId: Long, syncToken: String?) -> Unit,
        onTokenRejected: (userId: Long) -> Unit,
    ): Observable<String?> {
        account.pushSyncToken?.let { return Observable.just(it) }
        val token = tokens.get(account.id) ?: return Observable.just(null)
        return api.userPushSyncToken(token)
            .restSubscribeOn(retry = false)
            .map { it.pushSyncToken as String? }
            .doOnNext { onSyncToken(account.id, it) }
            .onErrorReturn { error ->
                if (isAuthFailure(error)) onTokenRejected(account.id)
                AppLog.w("Push sync token unavailable for one account: ${error.javaClass.simpleName}")
                null
            }
    }

    /** A 403 refuses the request for some other reason (a restriction, a missing verification), not the token. */
    internal fun isAuthFailure(error: Throwable): Boolean = error is HttpException && error.code() == 401

    fun registerDevice(pushToken: String?, persisted: (Boolean) -> Unit) {
        val api = RestAPI.apiOrNull ?: return persisted(false)
        api.userCreateDevice(RestAPIParams.UserDevices(pushToken))
            .restSubscribeOn(retry = false)
            .subscribe({}, { persisted(false) }, { persisted(true) })
    }
}
