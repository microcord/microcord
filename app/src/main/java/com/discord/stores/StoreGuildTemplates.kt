package com.discord.stores

import com.discord.models.domain.ModelGuildTemplate
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable

/**
 * Guild templates resolved by code, cached for the lifetime of the session.
 *
 * A template code reaches the client from two places — a chat message containing a template link,
 * and a Firebase dynamic link that survived an install — so both a per-code cache and a single
 * "the code the app was launched with" slot exist.
 *
 * The three failure states are not interchangeable and the distinction is what the chat row renders:
 *
 * - [GuildTemplateState.Invalid] — the server answered "no such template"
 *   ([RestApiError.Type.DISCORD_REQUEST_ERROR]). Terminal; the row shows "invalid template" and is
 *   never retried.
 * - [GuildTemplateState.LoadFailed] — the request never reached the server
 *   ([RestApiError.Type.NETWORK]). [maybeInitTemplateState] deliberately re-requests over this
 *   state, so scrolling the row back into view retries.
 * - Any other error type leaves the code on [GuildTemplateState.Loading] forever, which is the
 *   reference behaviour: the `when` has exactly these two arms.
 */
class StoreGuildTemplates(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
) : StoreV2() {

    sealed class GuildTemplateState {
        /** No code is set. Only ever produced by [observeDynamicLinkGuildTemplateCode]'s consumers. */
        object None : GuildTemplateState()
        object Loading : GuildTemplateState()

        /** Transport failure — retried the next time the code is requested. */
        object LoadFailed : GuildTemplateState()

        /** The server rejected the code. Terminal. */
        object Invalid : GuildTemplateState()
        data class Resolved(val guildTemplate: ModelGuildTemplate) : GuildTemplateState()
    }

    private val guildTemplatesByCode = LinkedHashMap<String, GuildTemplateState>()
    private var guildTemplatesByCodeSnapshot: Map<String, GuildTemplateState> = emptyMap()

    private var dynamicLinkGuildTemplateCode: String? = null
    private var dynamicLinkGuildTemplateCodeSnapshot: String? = null

    fun getGuildTemplate(guildTemplateCode: String): GuildTemplateState? =
        guildTemplatesByCodeSnapshot[guildTemplateCode]

    fun getDynamicLinkGuildTemplateCode(): String? = dynamicLinkGuildTemplateCodeSnapshot

    /**
     * Emits [GuildTemplateState.Loading] for a code that has never been requested, so a row can
     * bind before [maybeInitTemplateState] has run.
     */
    fun observeGuildTemplate(guildTemplateCode: String): Observable<GuildTemplateState> =
        observationDeck.connectRx(this) {
            getGuildTemplate(guildTemplateCode) ?: GuildTemplateState.Loading
        }.distinctUntilChanged()

    fun observeDynamicLinkGuildTemplateCode(): Observable<String?> =
        observationDeck.connectRx(this) { dynamicLinkGuildTemplateCodeSnapshot }.distinctUntilChanged()

    /** Requests [guildTemplateCode] unless it is already cached or in flight. */
    fun maybeInitTemplateState(guildTemplateCode: String) = dispatcher.schedule {
        val state = guildTemplatesByCode[guildTemplateCode]
        if (state != null && state !is GuildTemplateState.LoadFailed) return@schedule
        guildTemplatesByCode[guildTemplateCode] = GuildTemplateState.Loading
        markChanged()
        requestGuildTemplate(guildTemplateCode)
    }

    fun setDynamicLinkGuildTemplateCode(guildTemplateCode: String?) = dispatcher.schedule {
        dynamicLinkGuildTemplateCode = guildTemplateCode
        markChanged()
    }

    fun clearDynamicLinkGuildTemplateCode() = setDynamicLinkGuildTemplateCode(null)

    @StoreThread
    internal fun handleRequestGuildTemplateSuccess(guildTemplate: ModelGuildTemplate) {
        guildTemplatesByCode[guildTemplate.code] = GuildTemplateState.Resolved(guildTemplate)
        markChanged()
    }

    @StoreThread
    internal fun handleRequestGuildTemplateError(guildTemplateCode: String, errorType: RestApiError.Type) {
        when (errorType) {
            RestApiError.Type.DISCORD_REQUEST_ERROR ->
                guildTemplatesByCode[guildTemplateCode] = GuildTemplateState.Invalid
            RestApiError.Type.NETWORK ->
                guildTemplatesByCode[guildTemplateCode] = GuildTemplateState.LoadFailed
            // Every other type leaves the code on Loading, exactly as the reference does.
            else -> return
        }
        markChanged()
    }

    @StoreThread
    internal fun reset() {
        guildTemplatesByCode.clear()
        dynamicLinkGuildTemplateCode = null
        markChanged()
    }

    private fun requestGuildTemplate(guildTemplateCode: String) {
        val api = restApi() ?: return
        api.getGuildTemplateCode(guildTemplateCode)
            .restSubscribeOn(false)
            .appSubscribe(
                StoreGuildTemplates::class.java,
                errorHandler = { error ->
                    dispatcher.schedule { handleRequestGuildTemplateError(guildTemplateCode, error.type) }
                },
            ) { guildTemplate ->
                dispatcher.schedule { handleRequestGuildTemplateSuccess(guildTemplate) }
            }
    }

    @StoreThread
    override fun snapshotData() {
        guildTemplatesByCodeSnapshot = HashMap(guildTemplatesByCode)
        dynamicLinkGuildTemplateCodeSnapshot = dynamicLinkGuildTemplateCode
    }
}
