package com.discord.app

import android.app.Application
import android.util.Log
import rx.Observable
import rx.subjects.BehaviorSubject
import java.util.UUID

/**
 * Process wide application logger.
 *
 * Every message passes through [redactSecrets] before it reaches logcat or the in-app log buffer.
 * Authentication tokens, cookies, passwords, MFA codes and message content must never be written
 * to a log sink, so the redaction step is applied unconditionally rather than at each call site.
 */
object AppLog {

    /** One recorded log line, replayed by the in-app debug log screen. */
    data class LoggedItem(
        val priority: Int,
        val message: String,
        val throwable: Throwable?,
        val isSystem: Boolean = false,
    ) {
        val key: String = UUID.randomUUID().toString()
    }

    private const val DEFAULT_TAG = "Discord"

    /** Chunk size used so long messages are not truncated by logcat's per-line limit. */
    private const val LOGCAT_CHUNK_SIZE = 1_000

    /** Nothing is logged until [init] runs, mirroring the reference's inert default. */
    private const val PRIORITY_DISABLED = 99

    internal const val MAX_BUFFERED_LOGS = 5_000

    private val logs = BehaviorSubject.create<List<LoggedItem>>(emptyList()).toSerialized()
    private val logLock = Any()
    private var logSnapshot = emptyList<LoggedItem>()

    @Volatile
    private var initialized: Boolean = false

    @Volatile
    var minimumPriority: Int = PRIORITY_DISABLED

    /** Replaces logcat output during unit tests. */
    @Volatile
    internal var sink: ((Int, String, String, Throwable?) -> Unit)? = null

    fun init(application: Application) {
        applicationLabel = application.packageName
        initialized = true
        minimumPriority = Log.VERBOSE
    }

    @Volatile
    private var applicationLabel: String? = null

    /** Replays the current bounded snapshot, then every later snapshot. */
    fun observeLogs(): Observable<List<LoggedItem>> = logs.asObservable()

    internal fun getLogs(): List<LoggedItem> = synchronized(logLock) { logSnapshot }

    fun v(message: String, throwable: Throwable? = null) = log(Log.VERBOSE, message, throwable, null)

    fun d(message: String, throwable: Throwable? = null) = log(Log.DEBUG, message, throwable, null)

    fun i(message: String, throwable: Throwable? = null) = log(Log.INFO, message, throwable, null)

    fun w(message: String, throwable: Throwable? = null) = log(Log.WARN, message, throwable, null)

    fun e(message: String, throwable: Throwable? = null, metadata: Map<String, String>? = null) =
        log(Log.ERROR, message, throwable, metadata)

    fun v(tag: String, message: String, throwable: Throwable? = null) = v("$tag -> $message", throwable)

    fun d(tag: String, message: String, throwable: Throwable? = null) = d("$tag -> $message", throwable)

    fun i(tag: String, message: String, throwable: Throwable? = null) = i("$tag -> $message", throwable)

    fun w(tag: String, message: String, throwable: Throwable? = null) = w("$tag -> $message", throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null, metadata: Map<String, String>? = null) =
        e("$tag -> $message", throwable, metadata)

    /** Records a low-noise breadcrumb, used for navigation and lifecycle traces. */
    fun recordBreadcrumb(message: String, category: String) {
        if (!initialized) return
        v("Breadcrumb, [$category]: $message")
    }

    /** Package name of the running application, recorded once at [init] for diagnostics. */
    fun applicationLabel(): String? = applicationLabel

    /** Records a screen-to-screen navigation breadcrumb. */
    fun recordNavigation(from: String, to: String) {
        recordBreadcrumb("Navigation [$from] > [$to]", "navigation")
    }

    /** Records an already-formatted line captured from this process's logcat stream. */
    internal fun captureLogcat(priority: Int, message: String, isSystem: Boolean) {
        if (!initialized || priority < minimumPriority) return
        append(priority, redactSecrets(message), null, isSystem)
    }

    private fun log(priority: Int, message: String, throwable: Throwable?, metadata: Map<String, String>?) {
        if (priority < minimumPriority) return
        val safeMessage = redactSecrets(message)
        append(priority, safeMessage, throwable)

        val sink = sink
        if (sink != null) {
            sink(priority, DEFAULT_TAG, safeMessage, throwable)
            return
        }
        println(priority, safeMessage)
        metadata
            ?.takeIf { it.isNotEmpty() }
            ?.entries
            ?.joinToString(separator = "\n\t") { "${it.key}=${redactSecrets(it.value)}" }
            ?.let { println(priority, "Metadata: $it") }
        // The stack trace goes through the same redaction pass as the message. An exception's own
        // message routinely quotes what failed — a malformed auth response, a request URL with a
        // token query parameter — so printing it raw would have defeated [redactSecrets].
        throwable?.let { println(priority, redactSecrets(Log.getStackTraceString(it))) }
    }

    private fun append(
        priority: Int,
        message: String,
        throwable: Throwable?,
        isSystem: Boolean = false,
    ) {
        synchronized(logLock) {
            logSnapshot = (logSnapshot + LoggedItem(priority, message, throwable, isSystem))
                .takeLast(MAX_BUFFERED_LOGS)
            logs.onNext(logSnapshot)
        }
    }

    private fun println(priority: Int, message: String) {
        message.chunked(LOGCAT_CHUNK_SIZE).forEach { chunk -> Log.println(priority, DEFAULT_TAG, chunk) }
    }

    /**
     * Masks values that follow a sensitive key in `key=value`, `key: value` or `"key": "value"`
     * form, plus bare `Bearer <token>` / `Bot <token>` authorization values.
     */
    fun redactSecrets(message: String): String {
        if (message.isEmpty()) return message
        var redacted = message
        SENSITIVE_KEY_VALUE.forEach { pattern -> redacted = pattern.replace(redacted) { "${it.groupValues[1]}$REDACTED" } }
        redacted = AUTHORIZATION_SCHEME.replace(redacted) { "${it.groupValues[1]} $REDACTED" }
        return redacted
    }

    internal const val REDACTED = "[redacted]"

    private val SENSITIVE_KEYS = listOf(
        "token",
        "access_token",
        "refresh_token",
        "session_id",
        "authorization",
        "cookie",
        "set-cookie",
        "password",
        "new_password",
        "old_password",
        "mfa_code",
        "backup_code",
        "ticket",
        "secret",
        "client_secret",
        "api_key",
        "captcha_key",
        "fingerprint",
        "content",
        "message_body",
        "private_message",
    )

    private val SENSITIVE_KEY_VALUE: List<Regex> = SENSITIVE_KEYS.flatMap { key ->
        val quotedKey = Regex.escape(key)
        listOf(
            // "key": "value" / "key":"value"
            Regex("""("$quotedKey"\s*:\s*)"[^"]*"""", RegexOption.IGNORE_CASE),
            // key=value / key: value up to the next delimiter
            Regex("""(\b$quotedKey\b\s*[=:]\s*)[^\s,;&})\]]+""", RegexOption.IGNORE_CASE),
        )
    }

    private val AUTHORIZATION_SCHEME = Regex("""\b(Bearer|Bot|Basic)\s+\S+""", RegexOption.IGNORE_CASE)
}
