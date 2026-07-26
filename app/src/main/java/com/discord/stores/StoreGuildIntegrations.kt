package com.discord.stores

import com.discord.models.domain.ModelGuildIntegration
import com.discord.restapi.RestAPIInterface
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable
import rx.Subscription
import rx.subjects.BehaviorSubject
import java.util.concurrent.TimeUnit

/**
 * Twitch/YouTube integrations of the guild whose integrations screen is currently open.
 *
 * This is the one store in the batch that is **not** a [StoreV2]: it predates the observation deck
 * and publishes through a [BehaviorSubject] instead of a snapshot. That is reproduced rather than
 * modernised, because the screen-open/close latch below only makes sense with a single writer.
 *
 * `GUILD_INTEGRATIONS_UPDATE` carries nothing but a guild id — it is a "something changed, re-read"
 * signal. Acting on it unconditionally would re-fetch on every gateway tick for every guild, so
 * [handleUpdate] only refetches while the integrations screen is open, tracked by
 * [isOnIntegrationsScreen].
 *
 * Closing the screen does not clear that latch immediately. [onIntegrationScreenClosed] waits
 * [HANDLE_CLOSE_INTEGRATION_SCREEN_DELAY_MS] first, and [onIntegrationScreenOpened] cancels the
 * pending timer, so a configuration change or a push/pop between two integration screens does not
 * look like a close followed by an open — which would otherwise re-fetch every rotation.
 */
class StoreGuildIntegrations(
    private val dispatcher: Dispatcher,
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
    private val timer: (Long, TimeUnit) -> Observable<Long> =
        { delay, unit -> Observable.timer(delay, unit) },
) {

    private val allIntegrations = HashMap<Long, Map<Long, ModelGuildIntegration>>()
    private val integrationsSubject =
        BehaviorSubject.create<Map<Long, Map<Long, ModelGuildIntegration>>>(HashMap())

    private var isOnIntegrationsScreen = false
    private var closeIntegrationScreenSubscription: Subscription? = null

    /** All loaded integrations of [guildId], or `null` before the first fetch returns. */
    fun get(guildId: Long): Observable<Map<Long, ModelGuildIntegration>?> =
        integrationsSubject.map { integrations -> integrations[guildId] }.distinctUntilChanged()

    fun get(guildId: Long, integrationId: Long): Observable<ModelGuildIntegration?> =
        get(guildId).map { integrations -> integrations?.get(integrationId) }.distinctUntilChanged()

    @Synchronized
    fun onIntegrationScreenOpened(guildId: Long) {
        closeIntegrationScreenSubscription?.unsubscribe()
        closeIntegrationScreenSubscription = null
        dispatcher.schedule { handleIntegrationScreenOpened(guildId) }
    }

    @Synchronized
    fun onIntegrationScreenClosed() {
        closeIntegrationScreenSubscription?.unsubscribe()
        timer(HANDLE_CLOSE_INTEGRATION_SCREEN_DELAY_MS, TimeUnit.MILLISECONDS)
            .appSubscribe(
                StoreGuildIntegrations::class.java,
                subscriptionHandler = { subscription -> closeIntegrationScreenSubscription = subscription },
            ) {
                dispatcher.schedule { handleIntegrationScreenClosed() }
            }
    }

    /**
     * `GUILD_INTEGRATIONS_UPDATE`. Ignored unless the integrations screen is open — see the class
     * doc.
     */
    @StoreThread
    fun handleUpdate(update: ModelGuildIntegration.Update?) {
        if (!isOnIntegrationsScreen || update == null) return
        requestGuildIntegrations(update.guildId)
    }

    @StoreThread
    internal fun handleIntegrationScreenOpened(guildId: Long) {
        if (isOnIntegrationsScreen) return
        isOnIntegrationsScreen = true
        requestGuildIntegrations(guildId)
    }

    @StoreThread
    internal fun handleIntegrationScreenClosed() {
        isOnIntegrationsScreen = false
    }

    @StoreThread
    internal fun handleIntegrationsLoaded(guildId: Long, integrations: List<ModelGuildIntegration>) {
        allIntegrations[guildId] = integrations.associateBy(ModelGuildIntegration::id)
        integrationsSubject.onNext(HashMap(allIntegrations))
    }

    @StoreThread
    internal fun handleConnectionOpen() {
        allIntegrations.clear()
        integrationsSubject.onNext(HashMap(allIntegrations))
    }

    private fun requestGuildIntegrations(guildId: Long) {
        val api = restApi() ?: return
        api.getGuildIntegrations(guildId)
            .restSubscribeOn(false)
            .appSubscribe(StoreGuildIntegrations::class.java) { integrations ->
                dispatcher.schedule { handleIntegrationsLoaded(guildId, integrations) }
            }
    }

    companion object {
        /** Grace period before a screen close is believed. Reference: `StoreGuildIntegrationsKt`. */
        internal const val HANDLE_CLOSE_INTEGRATION_SCREEN_DELAY_MS = 1000L
    }
}
