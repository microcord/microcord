package com.discord.stores

import com.discord.api.application.ApplicationAsset
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable

/**
 * The one asset an embedded activity needs before it can be shown: its `embedded_background`.
 *
 * The endpoint returns every asset an application has registered, but only the background is kept —
 * it is what the activity tile paints behind the participant pile while the activity loads. An
 * application with no such asset caches nothing, so its tile falls back to the flat channel colour
 * and the request is retried on the next bind.
 */
class StoreApplicationAssets(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
) : StoreV2() {

    private val embeddedAppBackgrounds = LinkedHashMap<Long, ApplicationAsset>()
    private val embeddedAppBackgroundsLoading = HashSet<Long>()
    private var embeddedAppBackgroundsSnapshot: Map<Long, ApplicationAsset> = emptyMap()

    fun getEmbeddedAppBackgrounds(): Map<Long, ApplicationAsset> = embeddedAppBackgroundsSnapshot

    fun observeEmbeddedAppBackgrounds(): Observable<Map<Long, ApplicationAsset>> =
        observationDeck.connectRx(this) { getEmbeddedAppBackgrounds() }

    /**
     * Note the reference asymmetry, reproduced here: the in-flight id is only ever *removed*, never
     * added. [embeddedAppBackgroundsLoading] therefore never suppresses anything and a tile that
     * binds twice before the response lands issues two requests. Keeping it means the set is in
     * place for whoever fixes it; the alternative — adding the id — would change request volume
     * relative to the reference.
     */
    fun fetchIfNonExisting(appId: Long) = dispatcher.schedule {
        if (embeddedAppBackgrounds.containsKey(appId) ||
            embeddedAppBackgroundsLoading.contains(appId)
        ) {
            return@schedule
        }
        val api = restApi() ?: return@schedule
        api.getApplicationAssets(appId)
            .restSubscribeOn(false)
            .appSubscribe(
                StoreApplicationAssets::class.java,
                errorHandler = {
                    dispatcher.schedule { embeddedAppBackgroundsLoading.remove(appId) }
                },
            ) { applicationAssets ->
                dispatcher.schedule {
                    embeddedAppBackgroundsLoading.remove(appId)
                    handleApplicationAssets(appId, applicationAssets)
                }
            }
    }

    @StoreThread
    internal fun handleApplicationAssets(applicationId: Long, appAssets: List<ApplicationAsset>) {
        val background = appAssets.firstOrNull { it.name == EMBEDDED_BACKGROUND_ASSET_NAME } ?: return
        embeddedAppBackgrounds[applicationId] = background
        markChanged()
    }

    @StoreThread
    internal fun handlePreLogout() {
        embeddedAppBackgrounds.clear()
        embeddedAppBackgroundsLoading.clear()
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        embeddedAppBackgroundsSnapshot = HashMap(embeddedAppBackgrounds)
    }

    private companion object {
        const val EMBEDDED_BACKGROUND_ASSET_NAME = "embedded_background"
    }
}
