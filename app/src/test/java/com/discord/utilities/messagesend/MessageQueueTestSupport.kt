package com.discord.utilities.messagesend

import com.discord.restapi.RestAPIInterface
import com.discord.utilities.networking.Backoff
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import rx.Emitter
import rx.Observable

/**
 * Shared fakes for the message-send tests.
 *
 * Everything here is deterministic and single threaded: the queue runs on [DirectExecutorService] so
 * `executorService.submit { }` executes inline, [ManualBackoffScheduler] captures the delayed retry
 * instead of arming a timer, and rate-limit retries use an `rx.schedulers.TestScheduler`. That makes
 * ordering and timing assertions exact rather than flaky.
 *
 * No fixture here contains a real token, site key or user content.
 */

/** Runs every submitted task on the calling thread, preserving submission order. */
class DirectExecutorService : AbstractExecutorService() {
    private var shutdown = false

    override fun execute(command: Runnable) = command.run()
    override fun shutdown() { shutdown = true }
    override fun shutdownNow(): List<Runnable> { shutdown = true; return emptyList() }
    override fun isShutdown() = shutdown
    override fun isTerminated() = shutdown
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}

/** Captures the action [Backoff] wants to run later so a test can fire it on demand. */
class ManualBackoffScheduler : Backoff.Scheduler {
    val scheduledDelays = mutableListOf<Long>()
    private var pendingAction: (() -> Unit)? = null

    override fun schedule(action: () -> Unit, delayMs: Long) {
        scheduledDelays += delayMs
        pendingAction = action
    }

    override fun cancel() {
        pendingAction = null
    }

    val hasPendingAction: Boolean get() = pendingAction != null

    /** Runs the armed action, as the real timer would once the delay elapsed. */
    fun fire() {
        val action = pendingAction ?: error("No backoff action is armed")
        pendingAction = null
        action()
    }
}

/** Deterministic backoff: no jitter, so delay growth is exactly reproducible. */
fun testBackoff(scheduler: ManualBackoffScheduler) = Backoff(
    minBackoffMs = MessageQueue.NETWORK_INITIAL_FAILURE_RETRY_MS,
    maxBackoffMs = MessageQueue.MESSAGE_TIMEOUT_MS,
    failureThreshold = Int.MAX_VALUE,
    jitter = false,
    scheduler = scheduler,
)

/**
 * A [RestAPIInterface] that records every send/edit and hands the test control of each response.
 *
 * Implemented with [Proxy] because the project intentionally has no mocking library.
 */
class RecordingRestApi {
    data class SendCall(
        val channelId: Long,
        val body: com.discord.restapi.RestAPIParams.Message?,
        val multipart: Boolean,
    )

    data class EditCall(val channelId: Long, val messageId: Long, val content: String?)

    val sendCalls = mutableListOf<SendCall>()
    val editCalls = mutableListOf<EditCall>()
    val typingCalls = mutableListOf<Long>()

    private val pendingEmitters = ArrayDeque<Emitter<Any>>()

    /** True while a request is on the wire and has neither succeeded nor failed. */
    val inFlightCount: Int get() = pendingEmitters.size

    @Suppress("UNCHECKED_CAST")
    val api: RestAPIInterface = Proxy.newProxyInstance(
        RestAPIInterface::class.java.classLoader,
        arrayOf(RestAPIInterface::class.java),
    ) { _, method, args ->
        val arguments = args ?: emptyArray()
        when (method.name) {
            "sendMessage" -> {
                val channelId = arguments[0] as Long
                val multipart = arguments.size > 2
                val body = when {
                    multipart -> (arguments[1] as com.discord.restapi.PayloadJSON<*>).let { payload ->
                        payloadMessage(payload)
                    }
                    else -> arguments[1] as com.discord.restapi.RestAPIParams.Message
                }
                sendCalls += SendCall(channelId, body, multipart)
                deferred()
            }

            "editMessage" -> {
                editCalls += EditCall(
                    arguments[0] as Long,
                    arguments[1] as Long,
                    (arguments[2] as com.discord.restapi.RestAPIParams.Message).content,
                )
                deferred()
            }

            "setUserTyping" -> {
                typingCalls += arguments[0] as Long
                Observable.empty<Void>()
            }

            else -> error("Unexpected RestAPIInterface call: ${method.name}")
        }
    } as RestAPIInterface

    private fun payloadMessage(payload: com.discord.restapi.PayloadJSON<*>): com.discord.restapi.RestAPIParams.Message? {
        val field = com.discord.restapi.PayloadJSON::class.java.getDeclaredField("data")
        field.isAccessible = true
        return field.get(payload) as? com.discord.restapi.RestAPIParams.Message
    }

    private fun deferred(): Observable<Any> = Observable.create(
        { emitter -> pendingEmitters.addLast(emitter) },
        Emitter.BackpressureMode.BUFFER,
    )

    fun respondSuccess(message: com.discord.api.message.Message) {
        val emitter = pendingEmitters.removeFirst()
        emitter.onNext(message)
        emitter.onCompleted()
    }

    fun respondError(throwable: Throwable) {
        pendingEmitters.removeFirst().onError(throwable)
    }

    /** Fails the in-flight request with a Discord error body of [status] and [json]. */
    fun respondApiError(status: Int, json: String) = respondError(httpException(status, json))

    companion object {
        fun httpException(status: Int, json: String): HttpException = HttpException(
            Response.error<Any>(status, json.toResponseBody("application/json".toMediaType())),
        )
    }
}

fun executor(): ExecutorService = DirectExecutorService()
