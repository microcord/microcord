package com.discord.stores

import com.discord.app.AppLog
import com.discord.models.domain.UserSettingsProtoEnvelope
import com.discord.models.domain.UserSettingsProtoPatch
import com.discord.models.domain.UserSettingsProtoResponse
import com.discord.models.domain.UserSettingsProtoUpdate
import com.discord.restapi.RestAPIInterface
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.restSubscribeOn
import com.google.gson.JsonParser
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Message
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import okio.ByteString.Companion.toByteString
import retrofit2.HttpException

/**
 * Sends settings changes for one proto, coalescing them per delay tier.
 *
 * A change is applied locally the moment it is made and only then sent, so the UI never waits on a
 * request. Whatever the server returns replaces local state wholesale, which is also how a rejected
 * write corrects itself.
 */
class UserSettingsProtoWriter<T : Message, B : Message.Builder>(
    private val store: StoreUserSettingsProto<T>,
    private val dispatcher: Dispatcher,
    private val scheduler: ScheduledExecutorService,
    private val newBuilder: () -> B,
    private val api: () -> RestAPIInterface? = RestAPI::apiOrNull,
) {
    /**
     * Fills in state that is tracked outside the proto and only serialised when something is
     * already being sent — see [StoreFrecency].
     */
    abstract class BeforeSend<B> {
        @Volatile private var dirty = false

        /** Called when the tracked state changed, so the next write picks it up. */
        fun markDirty() { dirty = true }

        internal fun hasPendingChanges() = dirty
        internal fun onSaved() { dirty = false }

        abstract fun writeInto(builder: B)
    }

    private val lock = Any()
    private val pending = LinkedHashMap<FieldDescriptor, Any>()
    private val beforeSend = CopyOnWriteArrayList<BeforeSend<B>>()
    private var scheduled: ScheduledFuture<*>? = null
    private var scheduledDelaySeconds = Long.MAX_VALUE
    private var sending = false

    fun addBeforeSend(callback: BeforeSend<B>) = beforeSend.add(callback)

    /**
     * [populate] is handed the settings as they currently stand rather than an empty builder: the
     * smallest change the wire format can express is a whole top-level message, so one built from
     * scratch would clear every sibling field it did not mention.
     */
    @Suppress("UNCHECKED_CAST")
    fun write(delay: UserSettingsDelay, populate: B.() -> Unit) {
        val change = synchronized(lock) {
            val base = current()
            val updated = (base.toBuilder() as B).apply(populate).build()
            val touched = newBuilder()
            updated.allFields.forEach { (field, value) ->
                if (base.getField(field) != value) touched.setField(field, value)
            }
            val change = touched.build() as T
            if (change.allFields.isEmpty()) return
            change.allFields.forEach { (field, value) -> pending[field] = value }
            schedule(delay.seconds)
            change
        }
        dispatcher.schedule { store.handleLocalUpdate(change) }
    }

    /** Sends anything pending immediately; called when the app is leaving the foreground. */
    fun flush() {
        if (synchronized(lock) { pending.isEmpty() } && beforeSend.none { it.hasPendingChanges() }) return
        send()
    }

    @StoreThread
    fun handlePreLogout() {
        synchronized(lock) {
            scheduled?.cancel(false)
            scheduled = null
            scheduledDelaySeconds = Long.MAX_VALUE
            sending = false
            pending.clear()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun send() {
        val contributing = beforeSend.filter(BeforeSend<B>::hasPendingChanges)
        val toSend = synchronized(lock) {
            if (sending) return
            scheduled?.cancel(false)
            scheduled = null
            scheduledDelaySeconds = Long.MAX_VALUE
            val builder = newBuilder()
            pending.forEach { (field, value) -> builder.setField(field, value) }
            // Latched on rather than sent on their own: a frecency map rides along with whatever
            // write was already going out.
            contributing.forEach { it.writeInto(builder) }
            val toSend = builder.build() as T
            if (toSend.allFields.isEmpty()) return
            pending.clear()
            sending = true
            toSend
        }
        val api = api() ?: return

        // No `required_data_version`: it guards a write composed against a proto the server has
        // since moved past, which only happens to an edit held across a disconnect. Sending the
        // current version on every write instead turns any concurrent change into a rejection.
        api.patchUserSettingsProto(
            store.type,
            UserSettingsProtoPatch(settings = toSend.toByteArray().toByteString().base64()),
        )
            .restSubscribeOn(retry = false)
            .subscribe(
                { response -> dispatcher.schedule { handleResponse(response, contributing) } },
                { failure -> dispatcher.schedule { handleFailure(toSend, failure) } },
            )
    }

    @StoreThread
    private fun handleResponse(response: UserSettingsProtoResponse, contributing: List<BeforeSend<B>>) {
        synchronized(lock) { sending = false }
        if (response.outOfDate) AppLog.i(TAG, "type ${store.type} write was out of date, discarding")
        contributing.forEach(BeforeSend<B>::onSaved)
        store.handleUserSettingsProtoUpdate(
            UserSettingsProtoUpdate(
                settings = UserSettingsProtoEnvelope(type = store.type, proto = response.settings),
                // The response is the whole proto, not just what was sent.
                partial = false,
            ),
        )
        if (synchronized(lock) { pending.isNotEmpty() }) send()
    }

    @StoreThread
    private fun handleFailure(toSend: T, failure: Throwable) {
        synchronized(lock) {
            sending = false
            // Put the change back so the retry, or the next write, carries it.
            toSend.allFields.forEach { (field, value) -> if (field !in pending) pending[field] = value }
        }
        val http = failure as? HttpException
        if (http?.code() == RATE_LIMITED) {
            val seconds = http.response()?.headers()?.get("Retry-After")?.toDoubleOrNull() ?: 60.0
            synchronized(lock) { schedule(seconds.toLong().coerceAtMost(MAX_RATE_LIMIT_DELAY_SECONDS), force = true) }
            return
        }
        AppLog.e(TAG, "type ${store.type} write failed: ${failure.message}")
        // The server rejected the proto itself; only its copy can say what the truth is now.
        if (http?.code() == BAD_REQUEST && errorCode(http) == INVALID_USER_SETTINGS_DATA) {
            synchronized(lock) { pending.clear() }
            store.loadIfNecessary(force = true)
        }
    }

    private fun schedule(delaySeconds: Long, force: Boolean = false) {
        // A more urgent change pulls an existing timer forward rather than queueing behind it.
        if (!force && scheduled != null && delaySeconds >= scheduledDelaySeconds) return
        scheduled?.cancel(false)
        scheduledDelaySeconds = delaySeconds
        scheduled = scheduler.schedule(::send, delaySeconds, TimeUnit.SECONDS)
    }

    private fun current(): T = store.mergeTopLevel(
        store.getSettings(),
        @Suppress("UNCHECKED_CAST")
        newBuilder().also { builder -> pending.forEach { (field, value) -> builder.setField(field, value) } }
            .build() as T,
    )

    // Peeked, not consumed: an error this does not recognise still has to classify downstream.
    private fun errorCode(error: HttpException): Int? = runCatching {
        JsonParser.parseString(error.response()?.errorBody()?.source()?.peek()?.readUtf8())
            .asJsonObject["code"].asInt
    }.getOrNull()

    private companion object {
        const val TAG = "SettingsProto"
        const val BAD_REQUEST = 400
        const val RATE_LIMITED = 429
        const val INVALID_USER_SETTINGS_DATA = 50105
        const val MAX_RATE_LIMIT_DELAY_SECONDS = 30L
    }
}
