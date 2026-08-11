package com.discord.stores

import com.discord.app.AppLog
import com.discord.models.domain.UserSettingsProtoUpdate
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.persister.Persister
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.restSubscribeOn
import com.google.protobuf.Message
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import rx.Observable

/**
 * One of the account's settings protos, replacing the legacy `user_settings` object.
 *
 * [lazy] protos are absent from READY and fetched on first use.
 */
class StoreUserSettingsProto<T : Message>(
    val type: Int,
    private val dispatcher: Dispatcher,
    private val empty: T,
    private val parse: (ByteArray) -> T,
    private val dataVersionOf: (T) -> Int,
    cacheKey: String,
    private val lazy: Boolean = false,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val cache: Persister<String> = Persister(cacheKey, ""),
    private val api: () -> RestAPIInterface? = RestAPI::apiOrNull,
) : StoreV2() {

    private var settings: T = empty
    private var settingsSnapshot: T = settings
    private var loaded = false
    private var loading = false

    @StoreThread
    fun init(): T {
        decode(cache.get())?.let { settings = it }
        markChanged()
        return settings
    }

    fun getSettings(): T = settingsSnapshot

    fun observeSettings(): Observable<T> =
        observationDeck.connectRx(this, generator = ::getSettings)

    /** Sent in `client_state` so READY may omit a proto this client already has. */
    fun getDataVersion(): Int = dataVersionOf(settingsSnapshot)

    /** An absent proto means "what you have is current", not "you have nothing". */
    @StoreThread
    fun handleConnectionOpen(encoded: String?): T {
        if (lazy) {
            loaded = false
            loading = false
        }
        encoded?.let {
            decode(it)?.let { decoded ->
                settings = decoded
                loaded = true
                markChanged()
            }
        }
        return settings
    }

    fun loadIfNecessary(force: Boolean = false) = dispatcher.schedule {
        if (loading || (loaded && !force)) return@schedule
        val api = api() ?: return@schedule
        loading = true
        api.getUserSettingsProto(type)
            .restSubscribeOn()
            .subscribe(
                { response ->
                    dispatcher.schedule {
                        loading = false
                        loaded = true
                        decode(response.settings)?.let {
                            settings = it
                            markChanged()
                        }
                    }
                },
                { dispatcher.schedule { loading = false } },
            )
    }

    @StoreThread
    fun handleUserSettingsProtoUpdate(update: UserSettingsProtoUpdate): T {
        if (update.settings.type != type) return settings
        val incoming = decode(update.settings.proto) ?: return settings
        settings = if (update.partial) mergeTopLevel(settings, incoming) else incoming.also { loaded = true }
        markChanged()
        return settings
    }

    @StoreThread
    fun handleLocalUpdate(partial: T) {
        settings = mergeTopLevel(settings, partial)
        markChanged()
    }

    @StoreThread
    fun handlePreLogout() {
        settings = empty
        loaded = false
        loading = false
        cache.clear(persist = true)
        markChanged()
    }

    override fun snapshotData() {
        super.snapshotData()
        settingsSnapshot = settings
        cache.set(settings.toByteArray().toByteString().base64())
    }

    /**
     * Replaces whole top-level fields. Protobuf's recursive merge would make clearing a nested
     * field indistinguishable from omitting it; the server applies the same rule.
     */
    @Suppress("UNCHECKED_CAST")
    fun mergeTopLevel(base: T, updates: T): T {
        val merged = base.toBuilder()
        updates.allFields.forEach { (field, value) -> merged.setField(field, value) }
        return merged.build() as T
    }

    private fun decode(encoded: String?): T? {
        if (encoded.isNullOrEmpty()) return null
        return runCatching {
            parse(encoded.decodeBase64()?.toByteArray() ?: error("not base64"))
        }.onFailure { AppLog.e(TAG, "type $type failed to decode", it) }.getOrNull()
    }

    companion object {
        const val TYPE_PRELOADED = 1
        const val TYPE_FRECENCY = 2
        const val PRELOADED_CACHE_KEY = "STORE_USER_SETTINGS_PROTO_V1"
        const val FRECENCY_CACHE_KEY = "STORE_USER_SETTINGS_PROTO_V2"
        private const val TAG = "SettingsProto"
    }
}
