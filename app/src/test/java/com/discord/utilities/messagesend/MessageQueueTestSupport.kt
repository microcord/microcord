package com.discord.utilities.messagesend

import com.discord.api.message.Message as ApiMessage
import com.discord.restapi.PayloadJSON
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.utilities.captcha.CaptchaRequestPolicy
import com.discord.utilities.networking.Backoff
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import rx.Emitter
import rx.Observable

/**
 * Shared deterministic fakes for the message-send tests.
 *
 * Everything is single threaded: the queue runs on [DirectExecutorService] so `execute { }` happens
 * inline, [ManualBackoffScheduler] captures the delayed retry instead of arming a timer, and
 * rate-limit retries run on an `rx.schedulers.TestScheduler`. That makes ordering and timing
 * assertions exact instead of flaky.
 *
 * No fixture here contains a real token, site key, or user content.
 */

/** Runs every submitted task on the calling thread, preserving submission order. */
class DirectExecutorService : AbstractExecutorService() {
    private var stopped = false

    override fun execute(command: Runnable) = command.run()
    override fun shutdown() { stopped = true }
    override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
    override fun isShutdown() = stopped
    override fun isTerminated() = stopped
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}

/** Captures the action [Backoff] wants to run later so a test can fire it on demand. */
class ManualBackoffScheduler : Backoff.Scheduler {
    val scheduledDelays = mutableListOf<Long>()
    private var pendingAction: (() -> Unit)? = null

    val hasPendingAction: Boolean get() = pendingAction != null

    override fun schedule(action: () -> Unit, delayMs: Long) {
        scheduledDelays += delayMs
        pendingAction = action
    }

    override fun cancel() {
        pendingAction = null
    }

    /** Runs the armed action, as the real timer would once the delay elapsed. */
    fun fire() {
        val action = checkNotNull(pendingAction) { "No backoff action is armed" }
        pendingAction = null
        action()
    }
}

/** Deterministic backoff: jitter off, so delay growth is exactly reproducible. */
fun testBackoff(scheduler: ManualBackoffScheduler) = Backoff(
    minBackoffMs = MessageQueue.NETWORK_INITIAL_FAILURE_RETRY_MS,
    maxBackoffMs = MessageQueue.MESSAGE_TIMEOUT_MS,
    failureThreshold = Int.MAX_VALUE,
    jitter = false,
    scheduler = scheduler,
)

/**
 * A [RestAPIInterface] that records every call and lets the test decide when each one responds.
 *
 * Implemented with [Proxy] because the project intentionally has no mocking library.
 */
class RecordingRestApi {
    data class SendCall(
        val channelId: Long,
        val body: RestAPIParams.Message?,
        val multipart: Boolean,
        val captcha: CaptchaRequestPolicy? = null,
        val context: String? = null,
    )
    data class CommandCall(val body: RestAPIParams.ApplicationCommand, val multipart: Boolean)
    data class EditCall(val channelId: Long, val messageId: Long, val content: String?)

    val sendCalls = mutableListOf<SendCall>()
    val commandCalls = mutableListOf<CommandCall>()
    val editCalls = mutableListOf<EditCall>()
    val typingCalls = mutableListOf<Long>()

    private val pendingEmitters = ArrayDeque<Emitter<Any>>()

    /** Requests that are on the wire and have neither succeeded nor failed. */
    val inFlightCount: Int get() = pendingEmitters.size

    val api: RestAPIInterface = Proxy.newProxyInstance(
        RestAPIInterface::class.java.classLoader,
        arrayOf(RestAPIInterface::class.java),
    ) { _, method, args ->
        val arguments = args ?: emptyArray()
        when (method.name) {
            "sendApplicationCommand" -> {
                val multipart = arguments.firstOrNull() is PayloadJSON<*>
                val body = if (multipart) {
                    payloadData(arguments[0] as PayloadJSON<*>) as RestAPIParams.ApplicationCommand
                } else {
                    arguments[0] as RestAPIParams.ApplicationCommand
                }
                commandCalls += CommandCall(body, multipart)
                deferred()
            }

            "sendMessage" -> {
                val multipart = arguments[1] is PayloadJSON<*>
                val body = if (multipart) {
                    payloadMessage(arguments[1] as PayloadJSON<*>)
                } else {
                    arguments[1] as RestAPIParams.Message
                }
                sendCalls += SendCall(
                    arguments[0] as Long,
                    body,
                    multipart,
                    arguments[if (multipart) 3 else 2] as CaptchaRequestPolicy?,
                    arguments.last() as String,
                )
                deferred()
            }

            "editMessage" -> {
                editCalls += EditCall(
                    arguments[0] as Long,
                    arguments[1] as Long,
                    (arguments[2] as RestAPIParams.Message).content,
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

    fun respondSuccess(message: ApiMessage) {
        val emitter = pendingEmitters.removeFirst()
        emitter.onNext(message)
        emitter.onCompleted()
    }

    fun respondCommandSuccess() {
        val emitter = pendingEmitters.removeFirst()
        emitter.onNext(null)
        emitter.onCompleted()
    }

    fun respondError(throwable: Throwable) = pendingEmitters.removeFirst().onError(throwable)

    /** Fails the oldest in-flight request with a Discord error body. */
    fun respondApiError(status: Int, json: String) = respondError(httpException(status, json))

    private fun payloadMessage(payload: PayloadJSON<*>): RestAPIParams.Message? {
        return payloadData(payload) as? RestAPIParams.Message
    }

    private fun payloadData(payload: PayloadJSON<*>): Any? {
        val field = PayloadJSON::class.java.getDeclaredField("data")
        field.isAccessible = true
        return field.get(payload)
    }

    private fun deferred(): Observable<Any> = Observable.create<Any>(
        { emitter: Emitter<Any> -> pendingEmitters.addLast(emitter) },
        Emitter.BackpressureMode.BUFFER,
    )

    companion object {
        fun httpException(status: Int, json: String): HttpException = HttpException(
            Response.error<Any>(status, json.toResponseBody("application/json".toMediaType())),
        )
    }
}

/** Server response for "this channel is in slow mode", cooldown 2.5s. */
const val SLOW_MODE_BODY = """{"message":"You are sending messages too quickly.","code":20016,"retry_after":2.5}"""

/** Server response for a rate limit, retry after 0.75s. */
const val RATE_LIMIT_BODY = """{"message":"You are being rate limited.","retry_after":0.75,"global":false}"""

/** Server response demanding a captcha. Site key and token are obvious placeholders. */
const val CAPTCHA_BODY =
    """{"captcha_key":["captcha-required"],"captcha_sitekey":"test-site-key","captcha_service":"hcaptcha","captcha_rqtoken":"test-rqtoken"}"""

/** Server response for an AutoMod block. */
const val AUTOMOD_BODY = """{"message":"Message blocked by automatic moderation","code":200000}"""
