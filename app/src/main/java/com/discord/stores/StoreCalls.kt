package com.discord.stores

import com.discord.models.domain.ModelCall
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.takeSingleUntilTimeout
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.SerializedSubject

/**
 * The private/group calls the client knows about, keyed by channel.
 *
 * A `null` value is meaningful and different from an absent key: it means the channel is known to
 * have no call, rather than never having been mentioned.
 *
 * Nothing is ever requested. `PASSIVE_CALLS_V1` has the gateway connect the client to every DM and
 * group DM call as it learns of them, so the map is filled entirely by CALL_CREATE; guild channels
 * never carry a call object.
 */
class StoreCalls(
    /** `GET /channels/{id}/call` — reports whether the channel may be rung. */
    private val ringRequest: (channelId: Long, messageId: Long, recipients: List<Long>?) -> Unit,
    private val stopRingingRequest: (channelId: Long, messageId: Long, recipients: List<Long>?) -> Unit,
    private val onStopRingingWithoutCall: (channelId: Long) -> Unit,
) : Store() {

    private val calls = HashMap<Long, ModelCall?>()
    private val callsSubject =
        SerializedSubject<HashMap<Long, ModelCall?>, HashMap<Long, ModelCall?>>(BehaviorSubject.create())

    private fun callSubjectUpdate(updated: Boolean = true) {
        if (updated) callsSubject.onNext(HashMap(calls))
    }

    /**
     * Resolves the current call for [channelId], then runs [onFoundCall].
     *
     * Ringing needs the call's `messageId`, which only arrives with `CALL_CREATE`. The lookup waits
     * up to 3 s for it and then gives up with `null` rather than hanging the caller.
     */
    private fun findCall(channelId: Long, onFoundCall: (ModelCall?) -> Unit) {
        get(channelId)
            .filter { it != null }
            .timeout(FIND_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS, Observable.just<ModelCall?>(null))
            .takeSingleUntilTimeout()
            .subscribe(onFoundCall, {})
    }

    fun get(channelId: Long): Observable<ModelCall?> =
        callsSubject.map { it[channelId] }.computationLatest().distinctUntilChanged()

    fun handleCallCreateOrUpdate(call: ModelCall) {
        val channelId = call.channelId
        val changed = call != calls[channelId]
        if (changed) calls[channelId] = call
        callSubjectUpdate(changed)
    }

    /** Keeps the key with a `null` value: the channel is known to have no call, which is not the
     * same as never having been told about it. */
    fun handleCallDelete(callDelete: ModelCall) {
        if (!calls.containsKey(callDelete.channelId)) return
        calls[callDelete.channelId] = null
        callSubjectUpdate()
    }

    fun handleConnectionOpen() {
        calls.clear()
        callSubjectUpdate()
    }

    @JvmOverloads
    fun ring(channelId: Long, recipients: List<Long>? = null) = findCall(channelId) { call ->
        if (call != null) ringRequest(call.channelId, call.messageId, recipients)
    }

    /**
     * Stops ringing [channelId]. With no call object there is nothing for the server to stop, so the
     * local incoming-call banner is dismissed directly instead.
     */
    @JvmOverloads
    fun stopRinging(channelId: Long, recipients: List<Long>? = null) = findCall(channelId) { call ->
        if (call == null) {
            onStopRingingWithoutCall(channelId)
        } else {
            stopRingingRequest(call.channelId, call.messageId, recipients)
        }
    }

    private companion object {
        private const val FIND_CALL_TIMEOUT_SECONDS = 3L
    }
}
