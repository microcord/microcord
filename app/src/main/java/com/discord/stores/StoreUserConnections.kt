package com.discord.stores

import android.content.Context
import com.discord.api.connectedaccounts.ConnectedAccount
import com.discord.models.domain.ModelPayload
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.uri.UriHandler
import rx.Observable

/**
 * The third-party accounts (Spotify, Xbox, Steam, …) linked to this user.
 *
 * [State] is a list rather than a wrapper because every consumer iterates it; the [State.Loading]
 * case exists so a screen can tell "not fetched yet" from "fetched, none linked" without a
 * separate flag. `READY.connected_accounts` seeds it and `USER_CONNECTIONS_UPDATE` triggers a
 * refetch, since that event carries no payload.
 */
class StoreUserConnections(
    val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val api: () -> RestAPIInterface? = { null },
    /**
     * The reference routes the fetch result back through `StoreStream.handleUserConnections`, which
     * fans it out to this store *and* `StoreSpotify`. Injected so the fan-out stays in the graph
     * owner rather than becoming a store-to-store call.
     */
    private val onConnectionsLoaded: (List<ConnectedAccount>) -> Unit = {},
    /** `USER_CONNECTIONS_UPDATE`; the payload is empty, so it only means "refetch". */
    private val connectionUpdates: Observable<Unit> = Observable.never(),
) : StoreV2() {
    /**
     * A list *and* a loading marker, exactly as in the reference: consumers iterate it directly,
     * so making it a wrapper would force every call site through `.connectedAccounts`.
     */
    sealed class State(
        val connectedAccounts: List<ConnectedAccount>,
    ) : List<ConnectedAccount> by connectedAccounts {
        object Loading : State(emptyList())

        class ConnectedAccounts(connectedAccounts: List<ConnectedAccount>) : State(connectedAccounts) {
            override fun equals(other: Any?) =
                this === other || (other is ConnectedAccounts && other.connectedAccounts == connectedAccounts)

            override fun hashCode() = connectedAccounts.hashCode()

            override fun toString() = "ConnectedAccounts(connectedAccounts=$connectedAccounts)"
        }
    }

    private var state: State = State.Loading
    private var stateSnapshot: State = State.Loading

    fun getConnectedAccounts(): State = stateSnapshot

    fun observeConnectedAccounts(): Observable<State> =
        observationDeck.connectRx(this) { getConnectedAccounts() }

    override fun init(context: Context) {
        super.init(context)
        connectionUpdates.appSubscribe(StoreUserConnections::class.java) { fetchConnectedAccounts() }
    }

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        handleUserConnections(payload.connectedAccounts.orEmpty())
    }

    @StoreThread
    fun handleUserConnections(accounts: List<ConnectedAccount>) {
        state = State.ConnectedAccounts(accounts)
        markChanged()
    }

    fun fetchConnectedAccounts() {
        val api = api() ?: return
        api.getConnections().restSubscribeOn().appSubscribe(StoreUserConnections::class.java) { accounts ->
            dispatcher.schedule { onConnectionsLoaded(accounts) }
        }
    }

    fun authorizeConnection(platformName: String, context: Context) {
        val api = api() ?: return
        api.authorizeConnection(platformName).restSubscribeOn()
            .appSubscribe(StoreUserConnections::class.java) { response ->
                UriHandler.handle(context, response.url)
            }
    }

    fun updateUserConnection(
        connectedAccount: ConnectedAccount,
        syncFriends: Boolean,
        showActivity: Boolean,
        isVisible: Boolean,
    ) {
        val api = api() ?: return
        api.updateConnection(
            connectedAccount.type,
            connectedAccount.id,
            RestAPIParams.ConnectedAccount.create(
                connectedAccount,
                syncFriends,
                showActivity,
                if (isVisible) ConnectedAccount.VISIBLE else ConnectedAccount.HIDDEN,
            ),
        ).restSubscribeOn().appSubscribe(StoreUserConnections::class.java) { fetchConnectedAccounts() }
    }

    fun deleteUserConnection(platformName: String, connectionId: String) {
        val api = api() ?: return
        api.deleteConnection(platformName, connectionId).restSubscribeOn()
            .appSubscribe(StoreUserConnections::class.java) { fetchConnectedAccounts() }
    }

    override fun snapshotData() {
        stateSnapshot = when (val current = state) {
            is State.Loading -> State.Loading
            is State.ConnectedAccounts -> State.ConnectedAccounts(ArrayList(current.connectedAccounts))
        }
    }
}
