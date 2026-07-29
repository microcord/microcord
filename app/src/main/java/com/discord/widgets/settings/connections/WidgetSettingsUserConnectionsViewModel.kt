package com.discord.widgets.settings.connections

import androidx.annotation.MainThread
import com.discord.api.connectedaccounts.ConnectedAccount
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.restapi.RestAPIInterface
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserConnections
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.BehaviorSubject

class WidgetSettingsUserConnectionsViewModel(
    storeStateObservable: Observable<StoreState> = observeStores(),
    storeUserConnections: StoreUserConnections = StoreStream.getUserConnections(),
    private val api: RestAPIInterface = RestAPI.api,
) : AppViewModel<WidgetSettingsUserConnectionsViewModel.ViewState>(ViewState.Uninitialized) {

    data class ConnectionState(
        val connection: ConnectedAccount,
        val integrationGuildJoinStatus: Map<String, JoinStatus>,
    )

    sealed class JoinStatus {
        object Joined : JoinStatus()
        object Joining : JoinStatus()
        object JoinFailed : JoinStatus()
    }

    data class StoreState(
        val connectedAccounts: List<ConnectedAccount>,
        val guilds: Map<Long, Guild>,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()
        object Empty : ViewState()
        data class Loaded(val data: List<ConnectionState>) : ViewState()
    }

    private val joinStatusMap = linkedMapOf<String, JoinStatus>()
    private val joinStateSubject = BehaviorSubject.create<Map<String, JoinStatus>>(emptyMap())

    init {
        storeUserConnections.fetchConnectedAccounts()
        Observable.combineLatest(storeStateObservable, joinStateSubject, ::combineState)
            .computationLatest()
            .ui(this)
            .appSubscribe(javaClass, onNext = ::handleConnectionsState)
    }

    @MainThread
    fun joinConnectionIntegrationGuild(integrationId: String) {
        updateJoinStatus(integrationId, JoinStatus.Joining)
        api.joinGuildFromIntegration(integrationId)
            .restSubscribeOn()
            .appSubscribe(
                javaClass,
                errorHandler = {
                    updateJoinStatus(integrationId, JoinStatus.JoinFailed)
                },
                onCompleted = {
                    updateJoinStatus(integrationId, JoinStatus.Joined)
                },
                onNext = {},
            )
    }

    private fun updateJoinStatus(integrationId: String, status: JoinStatus) {
        joinStatusMap[integrationId] = status
        joinStateSubject.onNext(joinStatusMap.toMap())
    }

    private fun handleConnectionsState(state: List<ConnectionState>) {
        updateViewState(if (state.isEmpty()) ViewState.Empty else ViewState.Loaded(state))
    }

    companion object {
        internal fun combineState(
            storeState: StoreState,
            joinMap: Map<String, JoinStatus>,
        ): List<ConnectionState> = storeState.connectedAccounts.map { account ->
            val statuses = linkedMapOf<String, JoinStatus>()
            account.integrations.orEmpty().forEach { integration ->
                val id = integration.id ?: return@forEach
                if (integration.guild?.id?.let(storeState.guilds::containsKey) == true) {
                    statuses[id] = JoinStatus.Joined
                }
                joinMap[id]?.let { statuses[id] = it }
            }
            ConnectionState(account, statuses)
        }

        private fun observeStores(): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getUserConnections().observeConnectedAccounts(),
            StoreStream.getGuilds().observeGuilds(),
        ) { accounts, guilds ->
            StoreState(accounts.filterNot { it.type == "contacts" }, guilds)
        }
    }
}
