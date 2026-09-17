package com.discord.widgets.servers.role_members

import com.discord.api.role.GuildRole
import com.discord.app.AppViewModel
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreStream
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils.getUserNameWithDiscriminator
import com.discord.widgets.channels.permissions.PermissionOwner
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject
import com.discord.utilities.user.UserUtils.getDisplayName

class ServerSettingsRoleMembersViewModel(
    private val guildId: Long,
    private val guildRoleId: Long,
    private val searchQuerySubject: BehaviorSubject<String> = BehaviorSubject.create(""),
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId, guildRoleId, searchQuerySubject),
) : AppViewModel<ServerSettingsRoleMembersViewModel.ViewState>(ViewState.Loading) {

    data class StoreState(
        val guildMembers: List<GuildMember>,
        val users: Map<Long, User>,
        val role: GuildRole?,
    )

    sealed class ViewState {
        object Loading : ViewState()
        data class Loaded(
            val isUpdating: Boolean = false,
            val memberList: List<ServerSettingsRoleMemberAdapterItem>,
            val role: GuildRole,
        ) : ViewState()
    }

    sealed class Event {
        data class RemoveMemberFailure(val error: RestApiError) : Event()
    }

    private val eventSubject = PublishSubject.create<Event>()

    init {
        requestInitialMembers()
        storeStateObservable
            .distinctUntilChanged()
            .computationLatest()
            .ui(this)
            .appSubscribe(javaClass) { state ->
                val role = state.role ?: return@appSubscribe
                val updating = (viewState as? ViewState.Loaded)?.isUpdating == true
                updateViewState(ViewState.Loaded(updating, generateMemberList(state), role))
            }
        searchQuerySubject
            .debounce(SEARCH_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            .distinctUntilChanged()
            .ui(this)
            .appSubscribe(javaClass) { query ->
                StoreStream.getGatewayConnection().requestGuildMembers(guildId, query)
            }
    }

    fun observeEvents(): Observable<Event> = eventSubject

    fun updateSearchQuery(query: String) = searchQuerySubject.onNext(query)

    fun removeRoleFromMember(guildMember: GuildMember) {
        val loaded = viewState as? ViewState.Loaded ?: return
        updateViewState(loaded.copy(isUpdating = true))
        val roles = guildMember.getRoles().filter { it != guildRoleId }
        val api = RestAPI.apiOrNull ?: run {
            updateViewState(loaded)
            return
        }
        api.changeGuildMember(
            guildId,
            guildMember.userId,
            RestAPIParams.GuildMember.createWithRoles(roles),
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = javaClass,
                errorHandler = { error ->
                    updateViewState(loaded)
                    eventSubject.onNext(Event.RemoveMemberFailure(error))
                },
                onCompleted = { updateViewState(loaded.copy(isUpdating = false)) },
            ) {}
    }

    private fun requestInitialMembers() {
        val api = RestAPI.apiOrNull ?: return
        StoreGuilds.Actions.requestRoleMembers(
            guildId,
            this,
            api.getGuildRoleMemberIds(guildId, guildRoleId).restSubscribeOn(),
            StoreStream.getGatewayConnection(),
        )
    }

    private fun generateMemberList(state: StoreState): List<ServerSettingsRoleMemberAdapterItem> =
        state.guildMembers.mapNotNull { member ->
            val user = state.users[member.userId] ?: return@mapNotNull null
            ServerSettingsRoleMemberAdapterItem(
                member,
                PermissionOwner.Member(
                    user,
                    user.getDisplayName(member.identityNick()),
                    false,
                ),
            )
        }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 750L

        fun observeStoreState(
            guildId: Long,
            guildRoleId: Long,
            searchFilterSubject: Observable<String>,
        ): Observable<StoreState> = Observable.combineLatest(
            searchFilterSubject.distinctUntilChanged(),
            StoreStream.getGuilds().observeComputed(guildId),
            StoreStream.getUsers().observeAllUsers(),
            StoreStream.getGuilds().observeRoles(guildId),
        ) { filter, members, users, roles ->
            StoreState(
                guildMembers = filterMembers(members.values, users, guildRoleId, filter),
                users = users,
                role = roles[guildRoleId],
            )
        }

        internal fun filterMembers(
            members: Collection<GuildMember>,
            users: Map<Long, User>,
            guildRoleId: Long,
            filter: String,
        ): List<GuildMember> = members.filter { member ->
            guildRoleId in member.getRoles() && users[member.userId]?.let { user ->
                user.username.contains(filter, ignoreCase = true) ||
                    member.nick?.contains(filter, ignoreCase = true) == true
            } == true
        }
    }
}
