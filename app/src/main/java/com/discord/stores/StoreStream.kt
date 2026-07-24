package com.discord.stores

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.discord.api.channel.Channel
import com.discord.api.guild.Guild
import com.discord.api.stageinstance.StageInstance
import com.discord.models.domain.ModelUserSettings
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
    private val stageInstances = StoreStageInstances(observationDeck)
    private val storesV2 = listOf(accessibility, userSettingsSystem, stageInstances)
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
        initialized.onNext(true)
    }

    private fun initForTesting() {
        userSettingsSystem.restore()
        accessibility.initForTesting()
        userSettings.initForTesting()
        initialized.onNext(true)
    }

    private fun accessibilityMonitorBind(context: Context) =
        com.discord.utilities.accessibility.AccessibilityMonitor.INSTANCE.bindContext(context)

    fun handleConnectionOpen(userSettings: ModelUserSettings, guilds: List<Guild>) = dispatcher.schedule {
        this.userSettings.handleConnectionOpen(userSettings)
        userSettingsSystem.handleConnectionOpen(userSettings)
        stageInstances.handleConnectionOpen(guilds)
    }

    fun handleUserSettingsUpdate(userSettings: ModelUserSettings) = dispatcher.schedule {
        this.userSettings.handleUserSettingsUpdate(userSettings)
        userSettingsSystem.handleUserSettingsUpdate(userSettings)
    }

    fun handleGuildCreateOrUpdate(guild: Guild) = dispatcher.schedule { stageInstances.handleGuildAdd(guild) }
    fun handleGuildRemove(guild: Guild) = dispatcher.schedule { stageInstances.handleGuildRemove(guild) }
    fun handleChannelOrThreadDelete(channel: Channel) = dispatcher.schedule { stageInstances.handleChannelDelete(channel) }
    fun handleStageInstanceCreate(stageInstance: StageInstance) = dispatcher.schedule { stageInstances.handleStageInstanceCreate(stageInstance) }
    fun handleStageInstanceUpdate(stageInstance: StageInstance) = dispatcher.schedule { stageInstances.handleStageInstanceUpdate(stageInstance) }
    fun handleStageInstanceDelete(stageInstance: StageInstance) = dispatcher.schedule { stageInstances.handleStageInstanceDelete(stageInstance) }
    fun handlePreLogout() = dispatcher.schedule { userSettingsSystem.handlePreLogout() }

    fun resetForLogout() = dispatcher.schedule {
        userSettingsSystem.handlePreLogout()
        Persister.reset()
        val preferencesCleared = preferences.edit().clear().commit()
        val durablePreferencesCleared = sessionDurablePreferences.edit().clear().commit()
        accessibility.reset()
        userSettings.reset()
        userSettingsSystem.reset()
        stageInstances.reset()
        check(preferencesCleared && durablePreferencesCleared) { "Unable to clear logout preferences" }
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
        @JvmStatic fun getStageInstances() = requireInstance().stageInstances
    }
}
