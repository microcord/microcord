package com.discord.stores

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.discord.api.channel.Channel
import com.discord.api.guild.Guild
import com.discord.api.presence.Presence
import com.discord.api.stageinstance.StageInstance
import com.discord.api.user.User
import com.discord.models.domain.ModelChannelUnreadUpdate
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelReadState
import com.discord.models.domain.ModelSession
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.domain.ModelUserSettings
import com.discord.models.thread.dto.ModelThreadListSync
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.Clock
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
        isAuthenticated = { users.getMeInternal().id != 0L },
    )
    private val guildSettings = StoreUserGuildSettings(
        dispatcher,
        clock,
        observationDeck,
        getChannel = channels::findChannelById,
    )
    private val stageInstances = StoreStageInstances(observationDeck)
    private val presences = StoreUserPresence(clock, observationDeck, this)
    private val readStates = StoreReadStates(dispatcher, clock, observationDeck)
    private val storesV2 = listOf(
        accessibility,
        userSettingsSystem,
        users,
        userRelationships,
        guilds,
        guildSettings,
        channels,
        stageInstances,
        presences,
        readStates,
    )
    private val storeV2DispatchHandler = StoreV2DispatchHandler(storesV2, observationDeck)
    private val initialized = BehaviorSubject.create(false)

    init {
        dispatcher.registerDispatchHandlers(storeV2DispatchHandler)
    }

    private fun init(context: Context) {
        userSettingsSystem.init(context)
        accessibilityMonitorBind(context)
        accessibility.init(context)
        userSettings.init(context)
        dispatcher.schedule {
            users.restore()
            userRelationships.init()
            guilds.restore()
            channels.init()
            guildSettings.restore()
            readStates.restore()
        }
        initialized.onNext(true)
    }

    private fun initForTesting() {
        userSettingsSystem.restore()
        accessibility.initForTesting()
        userSettings.initForTesting()
        dispatcher.schedule {
            users.restore()
            userRelationships.init()
            guilds.restore()
            channels.init()
            guildSettings.restore()
            readStates.restore()
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
    }

    fun handleGuildCreateOrUpdate(guild: Guild) = dispatcher.schedule { handleGuildCreateOrUpdateInternal(guild) }
    fun handleGuildRemove(guild: Guild) = dispatcher.schedule { handleGuildRemoveInternal(guild) }
    fun handleChannelOrThreadDelete(channel: Channel) = dispatcher.schedule { handleChannelDeleteInternal(channel) }
    fun handleStageInstanceCreate(stageInstance: StageInstance) = dispatcher.schedule { stageInstances.handleStageInstanceCreate(stageInstance) }
    fun handleStageInstanceUpdate(stageInstance: StageInstance) = dispatcher.schedule { stageInstances.handleStageInstanceUpdate(stageInstance) }
    fun handleStageInstanceDelete(stageInstance: StageInstance) = dispatcher.schedule { stageInstances.handleStageInstanceDelete(stageInstance) }
    fun handleGatewayConnected(connected: Boolean) = dispatcher.schedule { channels.handleConnected(connected) }

    fun handleGatewayDispatch(type: String?, data: Any) = dispatcher.schedule {
        when (type) {
            "READY" -> (data as? ModelPayload)?.let(::handleConnectionOpenInternal)
            "USER_SETTINGS_UPDATE" -> (data as? ModelUserSettings)?.let {
                userSettings.handleUserSettingsUpdate(it)
                userSettingsSystem.handleUserSettingsUpdate(it)
                presences.handleUserSettingsUpdate(it)
            }
            "USER_GUILD_SETTINGS_UPDATE" -> (data as? ModelNotificationSettings)?.let {
                guildSettings.handleGuildSettingUpdated(listOf(it))
            }
            "USER_UPDATE" -> (data as? User)?.let(users::handleUserUpdated)
            "GUILD_CREATE", "GUILD_UPDATE" -> (data as? Guild)?.let(::handleGuildCreateOrUpdateInternal)
            "GUILD_DELETE" -> (data as? Guild)?.let(::handleGuildRemoveInternal)
            "CHANNEL_CREATE", "CHANNEL_UPDATE", "THREAD_CREATE", "THREAD_UPDATE" ->
                (data as? Channel)?.let(::handleChannelCreateOrUpdateInternal)
            "CHANNEL_DELETE", "THREAD_DELETE" -> (data as? Channel)?.let(::handleChannelDeleteInternal)
            "THREAD_LIST_SYNC" -> (data as? ModelThreadListSync)?.let {
                channels.handleThreadListSync(it.guildId, it.threads)
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
    fun handlePreLogout() = dispatcher.schedule { userSettingsSystem.handlePreLogout() }

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
        guildSettings.reset()
        stageInstances.reset()
        presences.reset()
        readStates.reset()
        check(preferencesCleared && durablePreferencesCleared) { "Unable to clear logout preferences" }
    }

    @StoreThread
    private fun handleConnectionOpenInternal(payload: ModelPayload) {
        users.handleConnectionOpen(payload)
        payload.userSettings?.let {
            userSettings.handleConnectionOpen(it)
            userSettingsSystem.handleConnectionOpen(it)
        }
        userRelationships.handleConnectionOpen(payload)
        guilds.handleConnectionOpen(payload)
        guildSettings.handleConnectionOpen(payload)
        channels.handleConnectionOpen(payload)
        stageInstances.handleConnectionOpen(payload.guilds.orEmpty())
        presences.handleConnectionOpen(payload)
        readStates.handleConnectionOpen(payload)
    }

    @StoreThread
    private fun handleGuildCreateOrUpdateInternal(guild: Guild) {
        users.handleGuildAddOrSync(guild)
        guilds.handleGuildAdd(guild)
        presences.handleGuildAdd(guild)
        channels.handleGuildAdd(guild)
        stageInstances.handleGuildAdd(guild)
    }

    @StoreThread
    private fun handleGuildRemoveInternal(guild: Guild) {
        guilds.handleGuildRemove(guild)
        presences.handleGuildRemove(guild)
        channels.handleGuildRemove(guild)
        stageInstances.handleGuildRemove(guild)
    }

    @StoreThread
    private fun handleChannelCreateOrUpdateInternal(channel: Channel) {
        users.handleChannelCreated(channel)
        channels.handleChannelOrThreadCreateOrUpdate(channel)
    }

    @StoreThread
    private fun handleChannelDeleteInternal(channel: Channel) {
        channels.handleChannelOrThreadDelete(channel)
        stageInstances.handleChannelDelete(channel)
    }

    fun observeInitialized(): Observable<Boolean> = initialized.distinctUntilChanged()

    companion object {
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
        @JvmStatic fun getUserSettings() = requireInstance().userSettings
        @JvmStatic fun getUserSettingsSystem() = requireInstance().userSettingsSystem
        @JvmStatic fun getUsers() = requireInstance().users
        @JvmStatic fun getUserRelationships() = requireInstance().userRelationships
        @JvmStatic fun getGuilds() = requireInstance().guilds
        @JvmStatic fun getChannels() = requireInstance().channels
        @JvmStatic fun getUserGuildSettings() = requireInstance().guildSettings
        @JvmStatic fun getStageInstances() = requireInstance().stageInstances
        @JvmStatic fun getPresences() = requireInstance().presences
        @JvmStatic fun getReadStates() = requireInstance().readStates
    }
}
