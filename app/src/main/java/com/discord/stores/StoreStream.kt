package com.discord.stores

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.discord.BuildConfig
import com.discord.api.channel.Channel
import com.discord.api.guild.Guild
import com.discord.api.guildmember.GuildMember
import com.discord.api.guildmember.GuildMemberRemove
import com.discord.api.guildmember.GuildMembersChunk
import com.discord.api.presence.Presence
import com.discord.api.role.GuildRoleCreate
import com.discord.api.role.GuildRoleDelete
import com.discord.api.role.GuildRoleUpdate
import com.discord.api.stageinstance.StageInstance
import com.discord.api.user.User
import com.discord.models.authentication.AuthState
import com.discord.models.domain.ModelChannelUnreadUpdate
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelReadState
import com.discord.models.domain.ModelSession
import com.discord.models.domain.ModelGuildFolder
import com.discord.models.domain.ModelGuildMemberListUpdate
import com.discord.gateway.GatewayCancellable
import com.discord.gateway.GatewayDiscovery
import com.discord.gateway.GatewayEventRouter
import com.discord.gateway.GatewaySocket
import com.discord.gateway.IdentifyData
import com.discord.gateway.RealGatewayScheduler
import com.discord.gateway.io.IdentifyClientState
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.domain.ModelUserSettings
import com.discord.models.domain.auth.ModelLoginResult
import com.discord.models.thread.dto.ModelThreadListSync
import com.discord.restapi.RequiredHeadersInterceptor
import com.discord.restapi.RestAPIBuilder
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.restapi.cookie.CookieJars
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.Clock
import com.discord.utilities.websocket.OkHttpGatewayWebSocketFactory
import java.io.File
import rx.Observable
import rx.Scheduler
import rx.subjects.BehaviorSubject

class StoreStream private constructor(
    val dispatcher: Dispatcher,
    val storeThreadScheduler: Scheduler,
    val clock: Clock,
    val observationDeck: ObservationDeck,
    private val preferences: SharedPreferences,
    private val sessionDurablePreferences: SharedPreferences,
) {
    private val authentication = StoreAuthentication(
        dispatcher,
        preferences,
        sessionDurablePreferences,
        onAuthStateChanged = ::handleAuthStateInternal,
        onLoginResult = ::handleLoginResultInternal,
        onPreLogout = ::handlePreLogout,
    )
    private val accessibility = StoreAccessibility(dispatcher, observationDeck)
    private val userSettings = StoreUserSettings(dispatcher, accessibility, preferences)
    private val userSettingsSystem = StoreUserSettingsSystem(
        accessibility::updateFontScale,
        dispatcher,
        observationDeck,
        preferences,
        sessionDurablePreferences,
    )
    private val users = StoreUser(dispatcher = dispatcher, observationDeck = observationDeck)
    private val userRelationships = StoreUserRelationships(observationDeck)
    private val guilds = StoreGuilds(users, dispatcher, observationDeck)
    private val channels = StoreChannels(
        dispatcher,
        observationDeck,
        isAuthenticated = { authentication.isAuthed() || users.getMeInternal().id != 0L },
    )
    private val permissions = StorePermissions(users, channels, guilds, observationDeck)
    private val guildSelected = StoreGuildSelected(dispatcher, observationDeck)
    private val channelsSelected = StoreChannelsSelected(channels, permissions, guildSelected, dispatcher, observationDeck)
    private val channelMembers = StoreChannelMembers(observationDeck) { guildId, roleId ->
        guilds.getGuildRolesInternal()[guildId]?.get(roleId)
    }
    private val guildSubscriptions = StoreGuildSubscriptions(observationDeck)
    private val guildsSorted = StoreGuildsSorted(observationDeck, dispatcher)
    private val expandedGuildFolders = StoreExpandedGuildFolders(dispatcher, observationDeck)
    private val collapsedChannelCategories = StoreCollapsedChannelCategories(dispatcher, observationDeck)
    private var guildFolders: List<ModelGuildFolder> = emptyList()
    private var guildFolderUpdateVersion = 0
    private val guildSettings = StoreUserGuildSettings(
        dispatcher,
        clock,
        observationDeck,
        getChannel = channels::findChannelById,
    )
    private val stageInstances = StoreStageInstances(observationDeck)
    private val presences = StoreUserPresence(clock, observationDeck, this)
    private val readStates = StoreReadStates(dispatcher, clock, observationDeck)
    private val navigation = StoreNavigation(this)
    private val tabsNavigation = StoreTabsNavigation(dispatcher, this, observationDeck)
    private val storesV2 = listOf(
        accessibility,
        userSettingsSystem,
        users,
        userRelationships,
        guilds,
        permissions,
        guildSelected,
        channelsSelected,
        channelMembers,
        guildSubscriptions,
        guildsSorted,
        expandedGuildFolders,
        collapsedChannelCategories,
        guildSettings,
        channels,
        stageInstances,
        presences,
        readStates,
        tabsNavigation,
    )
    private val storeV2DispatchHandler = StoreV2DispatchHandler(storesV2, observationDeck)
    private val initialized = BehaviorSubject.create(false)
    private val gatewayScheduler = RealGatewayScheduler()
    private var gateway: GatewaySocket? = null
    private var gatewayInitTimeout: GatewayCancellable? = null
    private var gatewayToken: String? = null

    init {
        dispatcher.registerDispatchHandlers(storeV2DispatchHandler)
    }

    private fun init(context: Context) {
        val api = createRestApi(context)
        authentication.bindApi(api)
        userSettingsSystem.init(context)
        gateway = GatewaySocket(
            identifyDataProvider = {
                authentication.getAuthState()?.token?.let { token ->
                    IdentifyData(token, IdentifyClientState(emptyMap(), 0, -1, -1))
                }
            },
            identifyProperties = mapOf(
                "browser" to "Discord Android",
                "browser_user_agent" to USER_AGENT,
                "client_build_number" to BuildConfig.VERSION_CODE,
                "client_version" to BuildConfig.VERSION_NAME,
                "device" to Build.DEVICE,
                "os" to "Android",
                "os_sdk_version" to Build.VERSION.SDK_INT,
                "os_version" to Build.VERSION.RELEASE,
                "system_locale" to userSettingsSystem.getLocale(),
            ),
            eventHandler = GatewayEventRouter(this),
            scheduler = gatewayScheduler,
            discovery = GatewayDiscovery(preferences) { api.getGateway().toBlocking().single() },
            webSocketFactory = OkHttpGatewayWebSocketFactory(),
        )
        authentication.init(context)
        accessibilityMonitorBind(context)
        accessibility.init(context)
        userSettings.init(context)
        dispatcher.schedule {
            users.restore()
            userRelationships.init()
            guilds.restore()
            channels.init()
            permissions.init()
            guildSelected.restore()
            channelsSelected.restore()
            expandedGuildFolders.restore()
            collapsedChannelCategories.restore()
            guildSettings.restore()
            readStates.restore()
            guildFolders = userSettings.getGuildFolders()
            updateSortedGuilds()
            updateReadStateInputs()
        }
        initialized.onNext(true)
    }

    private fun initForTesting() {
        authentication.init()
        userSettingsSystem.restore()
        accessibility.initForTesting()
        userSettings.initForTesting()
        dispatcher.schedule {
            users.restore()
            userRelationships.init()
            guilds.restore()
            channels.init()
            permissions.init()
            guildSelected.restore()
            channelsSelected.restore()
            expandedGuildFolders.restore()
            collapsedChannelCategories.restore()
            guildSettings.restore()
            readStates.restore()
            guildFolders = userSettings.getGuildFolders()
            updateSortedGuilds()
            updateReadStateInputs()
        }
        initialized.onNext(true)
    }

    private fun accessibilityMonitorBind(context: Context) =
        com.discord.utilities.accessibility.AccessibilityMonitor.INSTANCE.bindContext(context)

    fun handleConnectionOpen(userSettings: ModelUserSettings, guilds: List<Guild>) = dispatcher.schedule {
        this.userSettings.handleConnectionOpen(userSettings)
        userSettingsSystem.handleConnectionOpen(userSettings)
        stageInstances.handleConnectionOpen(guilds)
    }

    fun handleConnectionOpen(payload: ModelPayload) = dispatcher.schedule { handleConnectionOpenInternal(payload) }

    fun handleUserSettingsUpdate(userSettings: ModelUserSettings) = dispatcher.schedule {
        this.userSettings.handleUserSettingsUpdate(userSettings)
        userSettingsSystem.handleUserSettingsUpdate(userSettings)
        userSettings.guildFolders?.let { guildFolders = it }
        updateSortedGuilds()
    }

    fun handleGuildCreateOrUpdate(guild: Guild) = dispatcher.schedule { handleGuildCreateOrUpdateInternal(guild) }
    fun handleGuildRemove(guild: Guild) = dispatcher.schedule { handleGuildRemoveInternal(guild) }
    fun handleChannelOrThreadDelete(channel: Channel) = dispatcher.schedule { handleChannelDeleteInternal(channel) }
    fun handleStageInstanceCreate(stageInstance: StageInstance) = dispatcher.schedule { stageInstances.handleStageInstanceCreate(stageInstance) }
    fun handleStageInstanceUpdate(stageInstance: StageInstance) = dispatcher.schedule { stageInstances.handleStageInstanceUpdate(stageInstance) }
    fun handleStageInstanceDelete(stageInstance: StageInstance) = dispatcher.schedule { stageInstances.handleStageInstanceDelete(stageInstance) }
    fun handleGatewayConnected(connected: Boolean) = dispatcher.schedule { channels.handleConnected(connected) }
    fun handleGatewayConnectionReady(ready: Boolean) = dispatcher.schedule { guildSubscriptions.handleConnectionReady(ready) }
    fun handleGatewayAuthenticationFailure() = authentication.invalidate()

    fun handleGatewayDispatch(type: String?, data: Any) = dispatcher.schedule {
        when (type) {
            "READY" -> (data as? ModelPayload)?.let(::handleConnectionOpenInternal)
            "USER_SETTINGS_UPDATE" -> (data as? ModelUserSettings)?.let {
                userSettings.handleUserSettingsUpdate(it)
                userSettingsSystem.handleUserSettingsUpdate(it)
                presences.handleUserSettingsUpdate(it)
                it.guildFolders?.let { folders -> guildFolders = folders }
                updateSortedGuilds()
            }
            "USER_GUILD_SETTINGS_UPDATE" -> (data as? ModelNotificationSettings)?.let {
                guildSettings.handleGuildSettingUpdated(listOf(it))
                updateSortedGuilds()
                updateReadStateInputs()
            }
            "USER_UPDATE" -> (data as? User)?.let(users::handleUserUpdated)
            "GUILD_CREATE", "GUILD_UPDATE" -> (data as? Guild)?.let(::handleGuildCreateOrUpdateInternal)
            "GUILD_DELETE" -> (data as? Guild)?.let(::handleGuildRemoveInternal)
            "GUILD_MEMBER_ADD", "GUILD_MEMBER_UPDATE" -> (data as? GuildMember)?.let {
                users.handleGuildMemberAdd(it)
                guilds.handleGuildMember(it, preserveProfile = type == "GUILD_MEMBER_UPDATE")
                permissions.handleGuildMemberAdd(it)
                channelsSelected.handlePermissionsChanged(it.guildId)
                channelMembers.handleGuildMemberUpdate(it.guildId, it)
                updateReadStateInputs()
            }
            "GUILD_MEMBER_REMOVE" -> (data as? GuildMemberRemove)?.let {
                guilds.handleGuildMemberRemove(it.guildId, it.user.id)
                channelMembers.handleGuildMemberRemove(it.guildId, it.user.id)
                presences.handleGuildMemberRemove(it.user.id, it.guildId)
                if (it.user.id == users.getMeInternal().id) permissions.handleGuildRolesChanged(it.guildId)
                channelsSelected.handlePermissionsChanged(it.guildId)
                updateReadStateInputs()
            }
            "GUILD_MEMBERS_CHUNK" -> (data as? GuildMembersChunk)?.let {
                val members = it.members.orEmpty()
                users.handleGuildMembersChunk(members)
                guilds.handleGuildMembersChunk(it.guildId, members)
                it.presences.orEmpty().forEach { presence -> presences.handlePresenceUpdate(it.guildId, presence) }
                if (members.any { member -> member.user.id == users.getMeInternal().id }) {
                    permissions.handleGuildRolesChanged(it.guildId)
                    channelsSelected.handlePermissionsChanged(it.guildId)
                }
                members.forEach { member -> channelMembers.handleGuildMemberUpdate(it.guildId, member) }
                updateReadStateInputs()
            }
            "GUILD_ROLE_CREATE" -> (data as? GuildRoleCreate)?.let {
                it.role?.let { role -> guilds.handleGuildRoleCreateOrUpdate(it.guildId, role) }
                permissions.handleGuildRolesChanged(it.guildId)
                channelsSelected.handlePermissionsChanged(it.guildId)
                it.role?.let { role -> channelMembers.handleGuildRoleCreateOrUpdate(it.guildId, role) }
                updateReadStateInputs()
            }
            "GUILD_ROLE_UPDATE" -> (data as? GuildRoleUpdate)?.let {
                it.role?.let { role -> guilds.handleGuildRoleCreateOrUpdate(it.guildId, role) }
                permissions.handleGuildRolesChanged(it.guildId)
                channelsSelected.handlePermissionsChanged(it.guildId)
                it.role?.let { role -> channelMembers.handleGuildRoleCreateOrUpdate(it.guildId, role) }
                updateReadStateInputs()
            }
            "GUILD_ROLE_DELETE" -> (data as? GuildRoleDelete)?.let {
                guilds.handleGuildRoleRemove(it.guildId, it.roleId)
                permissions.handleGuildRolesChanged(it.guildId)
                channelsSelected.handlePermissionsChanged(it.guildId)
                updateReadStateInputs()
            }
            "GUILD_MEMBER_LIST_UPDATE" ->
                (data as? ModelGuildMemberListUpdate)?.let(channelMembers::handleGuildMemberListUpdate)
            "CHANNEL_CREATE", "CHANNEL_UPDATE", "THREAD_CREATE", "THREAD_UPDATE" ->
                (data as? Channel)?.let(::handleChannelCreateOrUpdateInternal)
            "CHANNEL_DELETE", "THREAD_DELETE" -> (data as? Channel)?.let(::handleChannelDeleteInternal)
            "THREAD_LIST_SYNC" -> (data as? ModelThreadListSync)?.let {
                channels.handleThreadListSync(it.guildId, it.threads)
                permissions.recomputeAllPermissions()
                updateReadStateInputs()
            }
            "PRESENCE_UPDATE" -> (data as? Presence)?.let {
                users.handlePresenceUpdate(it)
                presences.handlePresenceUpdate(it.guildId ?: 0L, it)
            }
            "PRESENCES_REPLACE" -> (data as? List<*>)?.filterIsInstance<Presence>()?.let {
                it.forEach(users::handlePresenceUpdate)
                presences.handlePresenceReplace(it)
            }
            "SESSIONS_REPLACE" -> (data as? List<*>)?.filterIsInstance<ModelSession>()?.let(presences::handleSessionsReplace)
            "RELATIONSHIP_ADD" -> (data as? ModelUserRelationship)?.let {
                users.handleUserRelationshipAdd(it)
                userRelationships.handleRelationshipAdd(it)
            }
            "RELATIONSHIP_REMOVE" -> (data as? ModelUserRelationship)?.let(userRelationships::handleRelationshipRemove)
            "MESSAGE_ACK" -> (data as? ModelReadState)?.let(readStates::handleMessageAck)
            "CHANNEL_UNREAD_UPDATE" -> (data as? ModelChannelUnreadUpdate)?.let {
                readStates.handleChannelUnreadUpdate(it.channelReadStates)
            }
            "STAGE_INSTANCE_CREATE" -> (data as? StageInstance)?.let(stageInstances::handleStageInstanceCreate)
            "STAGE_INSTANCE_UPDATE" -> (data as? StageInstance)?.let(stageInstances::handleStageInstanceUpdate)
            "STAGE_INSTANCE_DELETE" -> (data as? StageInstance)?.let(stageInstances::handleStageInstanceDelete)
        }
    }
    fun handlePreLogout() = dispatcher.schedule { handlePreLogoutInternal() }

    private fun selectGuildInternal(guildId: Long) = dispatcher.schedule {
        guildSelected.handleGuildSelected(guildId)
        channelsSelected.handleGuildSelected()
    }

    private fun selectChannelInternal(guildId: Long, channelId: Long) = dispatcher.schedule {
        if (guildSelected.getSelectedGuildIdInternal() != guildId) guildSelected.handleGuildSelected(guildId)
        channelsSelected.handleChannelSelected(guildId, channelId)
        navigation.setNavigationPanelAction(StoreNavigation.PanelAction.CLOSE)
    }

    private fun updateMemberPanelInternal(channel: Channel?, open: Boolean) = dispatcher.schedule {
        if (channel == null || channel.guildId == 0L) return@schedule
        if (open) guildSubscriptions.handlePanelOpened(channel.guildId, channel.id)
        else guildSubscriptions.handlePanelClosed(channel.guildId, channel.id)
    }

    private fun updateMemberRangeInternal(channel: Channel, range: IntRange) = dispatcher.schedule {
        guildSubscriptions.handleViewportChanged(channel.guildId, channel.id, range)
    }

    @Synchronized
    private fun updateGuildFoldersInternal(entries: List<StoreGuildsSorted.Entry>) {
        val folders = entries.map(StoreGuildsSorted.Entry::asModelGuildFolder)
        val previous = guildFolders
        val version = ++guildFolderUpdateVersion
        dispatcher.schedule {
            guildFolders = folders
            guildsSorted.handlePositions(entries)
            userSettings.handleUserSettingsUpdate(ModelUserSettings(guildFolders = folders))
        }
        if (::restApi.isInitialized) restApi.updateUserSettings(RestAPIParams.GuildFoldersUpdate(folders)).subscribe(
            ::handleUserSettingsUpdate,
            {
                dispatcher.schedule {
                    synchronized(this) {
                        if (version != guildFolderUpdateVersion) return@schedule
                        guildFolders = previous
                    }
                    userSettings.handleUserSettingsUpdate(ModelUserSettings(guildFolders = previous))
                    updateSortedGuilds()
                }
            },
        )
    }

    @StoreThread
    private fun handleAuthStateInternal(state: AuthState?) {
        authentication.handleAuthStateInternal(state)
        users.handleAuthToken(state?.token)
        val token = state?.token
        if (token != gatewayToken) {
            if (gatewayToken != null) gateway?.close(resetSession = true)
            gatewayToken = token
            gatewayInitTimeout?.cancel()
            gatewayInitTimeout = null
            if (token != null) {
                gateway?.connect()
                gatewayInitTimeout = gatewayScheduler.schedule(GATEWAY_INIT_TIMEOUT_MS) {
                    dispatcher.schedule {
                        channels.handleStoreInitTimeout()
                        navigation.updateAuthState(authentication.getAuthState(), channels.getInitializedForAuthedUser())
                    }
                }
            }
        }
        navigation.updateAuthState(state, channels.getInitializedForAuthedUser())
        if (state == null) {
            Persister.reset()
            preferences.edit().clear().apply()
        }
    }

    @StoreThread
    private fun handleLoginResultInternal(loginResult: ModelLoginResult) {
        authentication.handleLoginResultInternal(loginResult)
        userSettingsSystem.handleLoginResult(loginResult)
    }

    @StoreThread
    private fun handlePreLogoutInternal() {
        authentication.handlePreLogout()
        userSettingsSystem.handlePreLogout()
        userRelationships.handlePreLogout()
        readStates.handlePreLogout()
        guildSelected.handlePreLogout()
        guildSubscriptions.reset()
        tabsNavigation.handlePreLogout()
    }

    @StoreThread
    internal fun handleHomeTabSelected(panelAction: StoreNavigation.PanelAction) {
        navigation.handleHomeTabSelected(panelAction)
    }

    fun resetForLogout() = dispatcher.schedule {
        userSettingsSystem.handlePreLogout()
        Persister.reset()
        val preferencesCleared = preferences.edit().clear().commit()
        val durablePreferencesCleared = sessionDurablePreferences.edit().clear().commit()
        accessibility.reset()
        userSettings.reset()
        userSettingsSystem.reset()
        users.reset()
        userRelationships.handlePreLogout()
        guilds.reset()
        channels.reset()
        permissions.reset()
        guildSelected.reset()
        channelsSelected.reset()
        channelMembers.reset()
        guildSubscriptions.reset()
        expandedGuildFolders.reset()
        collapsedChannelCategories.reset()
        guildSettings.reset()
        stageInstances.reset()
        presences.reset()
        readStates.reset()
        tabsNavigation.handlePreLogout()
        navigation.reset()
        guildFolders = emptyList()
        check(preferencesCleared && durablePreferencesCleared) { "Unable to clear logout preferences" }
    }

    @StoreThread
    private fun handleConnectionOpenInternal(payload: ModelPayload) {
        gatewayInitTimeout?.cancel()
        gatewayInitTimeout = null
        payload.authToken?.let(authentication::setAuthed)
        users.handleConnectionOpen(payload)
        payload.userSettings?.let {
            userSettings.handleConnectionOpen(it)
            userSettingsSystem.handleConnectionOpen(it)
            it.guildFolders?.let { folders -> guildFolders = folders }
        }
        userRelationships.handleConnectionOpen(payload)
        guilds.handleConnectionOpen(payload)
        guildSettings.handleConnectionOpen(payload)
        channels.handleConnectionOpen(payload)
        permissions.handleConnectionOpen()
        guildSelected.handleConnectionOpen(payload)
        channelsSelected.handleConnectionOpen(payload)
        collapsedChannelCategories.handleConnectionOpen(payload)
        stageInstances.handleConnectionOpen(payload.guilds.orEmpty())
        presences.handleConnectionOpen(payload)
        readStates.handleConnectionOpen(payload)
        updateSortedGuilds()
        updateReadStateInputs()
        navigation.updateAuthState(authentication.getAuthState(), channels.getInitializedForAuthedUser())
    }

    @StoreThread
    private fun handleGuildCreateOrUpdateInternal(guild: Guild) {
        users.handleGuildAddOrSync(guild)
        guilds.handleGuildAdd(guild)
        presences.handleGuildAdd(guild)
        channels.handleGuildAdd(guild)
        permissions.handleGuildAdd(guild)
        channelsSelected.handleGuildSelected()
        stageInstances.handleGuildAdd(guild)
        updateSortedGuilds()
        updateReadStateInputs()
    }

    @StoreThread
    private fun handleGuildRemoveInternal(guild: Guild) {
        guildSelected.handleGuildRemove(guild)
        guilds.handleGuildRemove(guild)
        if (guild.unavailable) {
            updateSortedGuilds()
            return
        }
        presences.handleGuildRemove(guild)
        channels.handleGuildRemove(guild)
        permissions.handleGuildRemove(guild)
        if (guild.unavailable) channelsSelected.handleGuildSelected() else channelsSelected.handleGuildRemove(guild.id)
        stageInstances.handleGuildRemove(guild)
        channelMembers.handleGuildRemove(guild.id)
        guildSubscriptions.handleGuildRemove(guild.id)
        updateSortedGuilds()
        updateReadStateInputs()
    }

    @StoreThread
    private fun handleChannelCreateOrUpdateInternal(channel: Channel) {
        users.handleChannelCreated(channel)
        channels.handleChannelOrThreadCreateOrUpdate(channel)
        permissions.handleChannelOrThreadCreateOrUpdate(channel)
        channelsSelected.handleChannelOrThreadCreateOrUpdate(channel)
        updateReadStateInputs()
    }

    @StoreThread
    private fun handleChannelDeleteInternal(channel: Channel) {
        channels.handleChannelOrThreadDelete(channel)
        permissions.handleChannelOrThreadDelete(channel)
        channelsSelected.handleChannelOrThreadDelete(channel)
        stageInstances.handleChannelDelete(channel)
        updateReadStateInputs()
    }

    @StoreThread
    private fun updateSortedGuilds() {
        val mutedGuildIds = guildSettings.getGuildSettingsInternal().values
            .filter(ModelNotificationSettings::isMuted).mapTo(HashSet(), ModelNotificationSettings::guildId)
        guildsSorted.update(
            guilds.getGuildsInternal(),
            guilds.getUnavailableGuildsInternal(),
            emptySet(),
            guildFolders,
            mutedGuildIds,
            guilds.getGuildsJoinedAtInternal(),
        )
    }

    @StoreThread
    private fun updateReadStateInputs() {
        val allChannels = channels.getChannelsByIdInternal() + channels.getThreadsByIdInternal()
        readStates.updateUnreadInputs(
            StoreReadStates.Inputs(
                channelPermissions = permissions.getPermissionsByChannelInternal(),
                channels = allChannels,
                guildJoinedAt = guilds.getGuildsJoinedAtInternal(),
                guildSettings = guildSettings.getGuildSettingsInternal(),
                recentMessageIds = allChannels.values.filter { it.lastMessageId != 0L }.associate { it.id to it.lastMessageId },
                channelGuildIds = allChannels.mapValues { it.value.guildId },
            ),
        )
    }

    fun observeInitialized(): Observable<Boolean> = initialized.distinctUntilChanged()

    private lateinit var restApi: RestAPIInterface

    private fun createRestApi(context: Context): RestAPIInterface {
        val headers = object : RequiredHeadersInterceptor.HeadersProvider {
            override fun getAuthToken() = authentication.getAuthState()?.token
            override fun getFingerprint() = authentication.getFingerprint()
            override fun getLocale() = userSettingsSystem.getLocale()
            override fun getAcceptLanguages() = userSettingsSystem.getLocale()
            override fun getUserAgent() = USER_AGENT
            override fun getSpotifyToken(): String? = null
        }
        return RestAPIBuilder(API_BASE_URL, CookieJars.persistent(context)).build(
            RestAPIInterface::class.java,
            interceptors = listOf(RequiredHeadersInterceptor(headers)),
            clientName = "main",
        ).also { restApi = it }
    }

    companion object {
        private const val API_BASE_URL = "https://discord.com/api/"
        private const val USER_AGENT = "Manticord Android/126.21"
        private const val GATEWAY_INIT_TIMEOUT_MS = 10_000L
        @Volatile private var instance: StoreStream? = null

        @JvmStatic
        @Synchronized
        fun initialize(context: Context): StoreStream {
            instance?.let { return it }
            SharedPreferencesProvider.init(context.applicationContext)
            val clock = Clock(System::currentTimeMillis)
            Persister.init(context.applicationContext, clock, Observable.never())
            return create(
                StoreThreadScheduler.create(),
                clock,
                SharedPreferencesProvider.get(),
                SharedPreferencesProvider.getPrefsSessionDurable(),
            ).also {
                instance = it
                it.init(context.applicationContext)
            }
        }

        @Synchronized
        @SuppressLint("VisibleForTests")
        internal fun initializeForTesting(
            filesDir: File,
            preferences: SharedPreferences,
            sessionDurablePreferences: SharedPreferences,
            scheduler: Scheduler,
            clock: Clock = Clock { 0 },
        ): StoreStream {
            instance?.let { return it }
            Persister.init(filesDir, Observable.never(), clock)
            return create(scheduler, clock, preferences, sessionDurablePreferences).also {
                instance = it
                it.initForTesting()
            }
        }

        private fun create(
            scheduler: Scheduler,
            clock: Clock,
            preferences: SharedPreferences,
            sessionDurablePreferences: SharedPreferences,
        ): StoreStream {
            val deck = ObservationDeck()
            return StoreStream(
                Dispatcher(scheduler),
                scheduler,
                clock,
                deck,
                preferences,
                sessionDurablePreferences,
            )
        }

        private fun requireInstance() = checkNotNull(instance) { "StoreStream.initialize() must be called first" }

        @JvmStatic fun getAccessibility() = requireInstance().accessibility
        @JvmStatic fun getAuthentication() = requireInstance().authentication
        @JvmStatic fun getUserSettings() = requireInstance().userSettings
        @JvmStatic fun getUserSettingsSystem() = requireInstance().userSettingsSystem
        @JvmStatic fun getUsers() = requireInstance().users
        @JvmStatic fun getUserRelationships() = requireInstance().userRelationships
        @JvmStatic fun getGuilds() = requireInstance().guilds
        @JvmStatic fun getGuildSelected() = requireInstance().guildSelected
        @JvmStatic fun getGuildsSorted() = requireInstance().guildsSorted
        @JvmStatic fun getExpandedGuildFolders() = requireInstance().expandedGuildFolders
        @JvmStatic fun getCollapsedChannelCategories() = requireInstance().collapsedChannelCategories
        @JvmStatic fun getChannels() = requireInstance().channels
        @JvmStatic fun getChannelsSelected() = requireInstance().channelsSelected
        @JvmStatic fun getChannelMembers() = requireInstance().channelMembers
        @JvmStatic fun getGuildSubscriptions() = requireInstance().guildSubscriptions
        @JvmStatic fun getPermissions() = requireInstance().permissions
        @JvmStatic fun getUserGuildSettings() = requireInstance().guildSettings
        @JvmStatic fun getStageInstances() = requireInstance().stageInstances
        @JvmStatic fun getPresences() = requireInstance().presences
        @JvmStatic fun getReadStates() = requireInstance().readStates
        @JvmStatic fun getNavigation() = requireInstance().navigation
        @JvmStatic fun getTabsNavigation() = requireInstance().tabsNavigation
        @JvmStatic fun selectGuild(guildId: Long) = requireInstance().selectGuildInternal(guildId)
        @JvmStatic fun selectChannel(guildId: Long, channelId: Long) = requireInstance().selectChannelInternal(guildId, channelId)
        @JvmStatic fun updateMemberPanel(channel: Channel?, open: Boolean) = requireInstance().updateMemberPanelInternal(channel, open)
        @JvmStatic fun updateMemberRange(channel: Channel, range: IntRange) = requireInstance().updateMemberRangeInternal(channel, range)
        @JvmStatic fun updateGuildFolders(entries: List<StoreGuildsSorted.Entry>) = requireInstance().updateGuildFoldersInternal(entries)
    }
}
