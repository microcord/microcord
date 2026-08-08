package com.discord.widgets.servers.community

import com.discord.api.channel.Channel
import com.discord.api.guild.GuildFeature
import com.discord.api.permission.Permission
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuilds
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.PublishSubject

class WidgetServerSettingsCommunityOverviewViewModel(
    private val guildId: Long,
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId),
    private val updateGuild: (Long, RestAPIParams.UpdateGuild) -> Observable<com.discord.api.guild.Guild>? =
        { id, params -> RestAPI.apiOrNull?.updateGuild(id, params) },
    private val disableCommunityRequest: (Long, RestAPIParams.DisableCommunity) ->
    Observable<com.discord.api.guild.Guild>? = { id, params -> RestAPI.apiOrNull?.disableCommunity(id, params) },
) : AppViewModel<WidgetServerSettingsCommunityOverviewViewModel.ViewState>(ViewState.Uninitialized) {

    sealed class StoreState {
        object Invalid : StoreState()

        data class Valid(
            val guild: Guild,
            val me: MeUser,
            val permissions: Long?,
            val rulesChannel: Channel?,
            val updatesChannel: Channel?,
        ) : StoreState()
    }

    sealed class ViewState {
        object Uninitialized : ViewState()
        object Invalid : ViewState()
        data class Loaded(
            val guild: Guild,
            val rulesChannel: Channel?,
            val updatesChannel: Channel?,
            val isAdmin: Boolean = false,
        ) : ViewState()
        data class DisableCommunityLoading(val isLoading: Boolean) : ViewState()
    }

    sealed class Event {
        object DisableCommunitySuccess : Event()
        object SaveSuccess : Event()
        object Error : Event()
    }

    private val eventSubject = PublishSubject.create<Event>()

    init {
        storeStateObservable.computationLatest().ui(this).subscribe { updateViewState(toViewState(it)) }
    }

    fun observeEvents(): Observable<Event> = eventSubject

    fun saveRulesChannel(channelId: Long) = saveCommunityGuildSettings(
        RestAPIParams.UpdateGuild(rulesChannelId = channelId),
    )

    fun saveCommunityUpdatesChannel(channelId: Long) = saveCommunityGuildSettings(
        RestAPIParams.UpdateGuild(publicUpdatesChannelId = channelId),
    )

    fun saveLocale(locale: String) = saveCommunityGuildSettings(
        RestAPIParams.UpdateGuild(preferredLocale = locale),
    )

    /** Leaving Community also gives up Discovery and the two channels it required. */
    fun disableCommunity() {
        val loaded = viewState as? ViewState.Loaded ?: return
        updateViewState(ViewState.DisableCommunityLoading(true))
        val request = disableCommunityRequest(guildId, disableCommunityParams(loaded.guild))
        if (request == null) {
            handleGuildUpdateError()
            return
        }
        request.restSubscribeOn().ui(this).appSubscribe(
            errorClass = WidgetServerSettingsCommunityOverviewViewModel::class.java,
            errorHandler = { handleGuildUpdateError() },
        ) {
            updateViewState(ViewState.DisableCommunityLoading(false))
            eventSubject.onNext(Event.DisableCommunitySuccess)
        }
    }

    private fun saveCommunityGuildSettings(params: RestAPIParams.UpdateGuild) {
        val request = updateGuild(guildId, params)
        if (request == null) {
            handleGuildUpdateError()
            return
        }
        request.restSubscribeOn().ui(this).appSubscribe(
            errorClass = WidgetServerSettingsCommunityOverviewViewModel::class.java,
            errorHandler = { handleGuildUpdateError() },
        ) {
            eventSubject.onNext(Event.SaveSuccess)
        }
    }

    private fun handleGuildUpdateError() {
        updateViewState(ViewState.DisableCommunityLoading(false))
        eventSubject.onNext(Event.Error)
    }

    companion object {
        internal fun toViewState(storeState: StoreState): ViewState {
            val valid = storeState as? StoreState.Valid ?: return ViewState.Invalid
            val canManage = valid.guild.isOwner(valid.me.id) || PermissionUtils.canAndIsElevated(
                Permission.MANAGE_GUILD,
                valid.permissions,
                valid.me.mfaEnabled,
                valid.guild.mfaLevel,
            )
            return if (canManage) {
                ViewState.Loaded(
                    valid.guild,
                    valid.rulesChannel,
                    valid.updatesChannel,
                    isAdmin = valid.guild.isOwner(valid.me.id) ||
                        PermissionUtils.can(Permission.ADMINISTRATOR, valid.permissions),
                )
            } else {
                ViewState.Invalid
            }
        }

        internal fun disableCommunityParams(guild: Guild) = RestAPIParams.DisableCommunity(
            features = (guild.features - GuildFeature.COMMUNITY - GuildFeature.DISCOVERABLE).toList(),
        )

        fun observeStoreState(
            guildId: Long,
            storeGuilds: StoreGuilds = StoreStream.getGuilds(),
            storeChannels: StoreChannels = StoreStream.getChannels(),
            storePermissions: StorePermissions = StoreStream.getPermissions(),
            storeUsers: StoreUser = StoreStream.getUsers(),
        ): Observable<StoreState> = storeGuilds.observeGuild(guildId).switchMap { guild ->
            if (guild == null) {
                Observable.just(StoreState.Invalid)
            } else {
                Observable.combineLatest(
                    storeUsers.observeMe(),
                    storePermissions.observePermissionsForGuild(guildId),
                    storeChannels.observeChannel(guild.rulesChannelId ?: 0L),
                    storeChannels.observeChannel(guild.publicUpdatesChannelId ?: 0L),
                ) { me, permissions, rulesChannel, updatesChannel ->
                    StoreState.Valid(guild, me, permissions, rulesChannel, updatesChannel)
                }
            }
        }
    }
}
