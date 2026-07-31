package com.discord.widgets.servers.community

import androidx.annotation.MainThread
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.discord.api.channel.Channel
import com.discord.api.guild.GuildExplicitContentFilter
import com.discord.api.guild.GuildFeature
import com.discord.api.guild.GuildVerificationLevel
import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
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

const val COMMUNITY_SETUP_FIRST_STEP = 1
const val CREATE_NEW_CHANNEL_CONSTANT = 1L
internal const val ARG_GUILD_ID = "INTENT_EXTRA_GUILD_ID"

internal fun Fragment.enableCommunityViewModel() = lazy(LazyThreadSafetyMode.NONE) {
    ViewModelProvider(requireParentFragment())[WidgetServerSettingsEnableCommunityViewModel::class.java]
}

class WidgetServerSettingsEnableCommunityViewModel(
    val guildId: Long,
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId),
    private val updateGuild: (Long, RestAPIParams.UpdateGuild) -> Observable<com.discord.api.guild.Guild>? =
        { id, params -> RestAPI.apiOrNull?.updateGuild(id, params) },
    private val updateRole: (Long, Long, RestAPIParams.Role) -> Observable<Void?>? =
        { id, roleId, params -> RestAPI.apiOrNull?.updateRole(id, roleId, params) },
) : AppViewModel<WidgetServerSettingsEnableCommunityViewModel.ViewState>(ViewState.Uninitialized) {

    data class CommunityGuildConfig(
        val rulesChannel: Channel?,
        val updatesChannel: Channel?,
        val rulesChannelId: Long?,
        val updatesChannelId: Long?,
        val isPrivacyPolicyAccepted: Boolean,
        val defaultMessageNotifications: Boolean,
        val verificationLevel: Boolean,
        val explicitContentFilter: Boolean,
        val guild: Guild?,
        val everyonePermissions: Boolean,
        val features: List<GuildFeature> = emptyList(),
        val roles: Map<Long, GuildRole>?,
    )

    sealed class StoreState {
        object Invalid : StoreState()

        data class Valid(
            val guild: Guild,
            val me: MeUser,
            val permissions: Long?,
            val roles: Map<Long, GuildRole>?,
            val rulesChannel: Channel?,
            val updatesChannel: Channel?,
        ) : StoreState()
    }

    sealed class ViewState {
        object Uninitialized : ViewState()
        object Invalid : ViewState()
        data class Loaded(
            val currentPage: Int,
            val isLoading: Boolean,
            val communityGuildConfig: CommunityGuildConfig,
        ) : ViewState()
    }

    sealed class Event {
        object SaveSuccess : Event()
        object Error : Event()
    }

    private val eventSubject = PublishSubject.create<Event>()

    init {
        storeStateObservable.computationLatest().ui(this).subscribe { state ->
            updateViewState(toViewState(state, guildId))
        }
    }

    fun observeEvents(): Observable<Event> = eventSubject

    @MainThread
    fun updateCurrentPage(page: Int) {
        val loaded = viewState as? ViewState.Loaded ?: return
        updateViewState(loaded.copy(currentPage = page))
    }

    @MainThread
    fun goBackToPreviousPage() {
        val loaded = viewState as? ViewState.Loaded ?: return
        updateCurrentPage(loaded.currentPage - 1)
    }

    @MainThread
    fun modifyGuildConfig(transform: (CommunityGuildConfig) -> CommunityGuildConfig) {
        val loaded = viewState as? ViewState.Loaded ?: return
        updateViewState(loaded.copy(communityGuildConfig = transform(loaded.communityGuildConfig)))
    }

    fun updateGuild() {
        val loaded = viewState as? ViewState.Loaded ?: return
        val config = loaded.communityGuildConfig
        updateViewState(loaded.copy(isLoading = true))
        val request = updateGuild(guildId, updatedGuildParams(config))
        if (request == null) return handleGuildUpdateError()
        request.restSubscribeOn().ui(this).appSubscribe(
            errorClass = WidgetServerSettingsEnableCommunityViewModel::class.java,
            errorHandler = { handleGuildUpdateError() },
        ) { guild ->
            if (config.everyonePermissions) patchEveryoneRole(guild.id, config, loaded)
            else completeSave(loaded)
        }
    }

    private fun patchEveryoneRole(roleId: Long, config: CommunityGuildConfig, loaded: ViewState.Loaded) {
        val permissions = config.roles?.get(guildId)?.permissions.orZero() and COMMUNITY_EVERYONE_PERMISSIONS
        val request = updateRole(guildId, guildId, RestAPIParams.Role(permissions = permissions, id = roleId))
        if (request == null) return handleGuildUpdateError()
        request.restSubscribeOn().ui(this).appSubscribe(
            errorClass = WidgetServerSettingsEnableCommunityViewModel::class.java,
            errorHandler = { handleGuildUpdateError() },
        ) { completeSave(loaded) }
    }

    private fun completeSave(loaded: ViewState.Loaded) {
        updateViewState(loaded.copy(isLoading = false))
        eventSubject.onNext(Event.SaveSuccess)
    }

    private fun handleGuildUpdateError() {
        val loaded = viewState as? ViewState.Loaded
        if (loaded != null) updateViewState(loaded.copy(isLoading = false))
        eventSubject.onNext(Event.Error)
    }

    companion object {
        internal const val COMMUNITY_EVERYONE_PERMISSIONS = -1116960071743L

        internal fun toViewState(storeState: StoreState, guildId: Long): ViewState {
            val valid = storeState as? StoreState.Valid ?: return ViewState.Invalid
            val canManage = valid.guild.isOwner(valid.me.id) || PermissionUtils.canAndIsElevated(
                Permission.MANAGE_GUILD,
                valid.permissions,
                valid.me.mfaEnabled,
                valid.guild.mfaLevel,
            )
            if (!canManage) return ViewState.Invalid

            val everyonePermissions = valid.roles?.get(guildId)?.permissions
            return ViewState.Loaded(
                currentPage = 0,
                isLoading = false,
                communityGuildConfig = CommunityGuildConfig(
                    rulesChannel = valid.rulesChannel,
                    updatesChannel = valid.updatesChannel,
                    rulesChannelId = CREATE_NEW_CHANNEL_CONSTANT,
                    updatesChannelId = CREATE_NEW_CHANNEL_CONSTANT,
                    isPrivacyPolicyAccepted = false,
                    defaultMessageNotifications = valid.guild.defaultMessageNotifications == 1,
                    verificationLevel = valid.guild.verificationLevel > GuildVerificationLevel.NONE,
                    explicitContentFilter = valid.guild.explicitContentFilter > GuildExplicitContentFilter.NONE,
                    guild = valid.guild,
                    everyonePermissions = everyonePermissions?.let {
                        it and COMMUNITY_EVERYONE_PERMISSIONS == it
                    } == true,
                    features = valid.guild.features.toList(),
                    roles = valid.roles,
                ),
            )
        }

        internal fun updatedGuildParams(config: CommunityGuildConfig): RestAPIParams.UpdateGuild {
            val guild = config.guild
            val verificationLevel = when {
                guild == null -> GuildVerificationLevel.NONE
                guild.verificationLevel == GuildVerificationLevel.NONE && config.verificationLevel -> GuildVerificationLevel.LOW
                guild.verificationLevel > GuildVerificationLevel.NONE && !config.verificationLevel -> GuildVerificationLevel.NONE
                else -> guild.verificationLevel
            }
            val explicitContentFilter = when {
                guild == null -> GuildExplicitContentFilter.NONE
                guild.explicitContentFilter == GuildExplicitContentFilter.NONE && config.explicitContentFilter ->
                    GuildExplicitContentFilter.ALL
                guild.explicitContentFilter > GuildExplicitContentFilter.NONE && !config.explicitContentFilter ->
                    GuildExplicitContentFilter.NONE
                else -> guild.explicitContentFilter
            }
            return RestAPIParams.UpdateGuild(
                defaultMessageNotifications = if (config.defaultMessageNotifications) 1 else 0,
                verificationLevel = verificationLevel,
                explicitContentFilter = explicitContentFilter,
                features = config.features + GuildFeature.COMMUNITY,
                rulesChannelId = config.rulesChannelId,
                publicUpdatesChannelId = config.updatesChannelId,
            )
        }

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
                    storeGuilds.observeRoles(guildId),
                    storeChannels.observeChannel(guild.rulesChannelId ?: 0L),
                    storeChannels.observeChannel(guild.publicUpdatesChannelId ?: 0L),
                ) { me, permissions, roles, rulesChannel, updatesChannel ->
                    StoreState.Valid(guild, me, permissions, roles, rulesChannel, updatesChannel)
                }
            }
        }

        private fun Long?.orZero() = this ?: 0L
    }
}
