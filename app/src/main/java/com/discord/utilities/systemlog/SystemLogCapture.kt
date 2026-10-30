package com.discord.utilities.systemlog

import android.os.Process as AndroidProcess
import android.os.SystemClock
import android.util.Log
import com.discord.app.AppLog
import com.discord.utilities.collections.FixedSizeLineBuffer
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.concurrent.thread

/**
 * A continuously-tailed, size-bounded copy of this process's logcat output.
 *
 * Android's own log ring is small and shared with every other app on the device, so by the time a
 * user files a bug report the interesting lines have usually been evicted. This keeps the app's own
 * rolling window in memory, ready to attach. The same window is periodically persisted so a JVM or
 * native crash can be gathered on the next launch.
 *
 * Two buffers, not one: tombstone lines (native crash traces) go into a second, smaller buffer that
 * is emitted *first*, so a native crash survives even after half a megabyte of ordinary logging has
 * pushed the rest of the window out.
 */
class SystemLogCapture {

    private val buffer = FixedSizeLineBuffer(MAX_BUFFER_CHARACTERS)
    private val tombstoneBuffer = FixedSizeLineBuffer(MAX_TOMBSTONE_BUFFER_CHARACTERS)
    private val persistenceLock = Any()
    private var currentLogFile: File? = null
    private var previousLogFile: File? = null
    private var lastPersistAt = 0L

    fun init(filesDir: File) {
        synchronized(persistenceLock) {
            val current = File(filesDir, CURRENT_LOG_FILE)
            val previous = File(filesDir, PREVIOUS_LOG_FILE)
            if (current.exists()) current.copyTo(previous, overwrite = true)
            current.delete()
            currentLogFile = current
            previousLogFile = previous
            lastPersistAt = 0L
        }
    }

    /**
     * Starts the tail on a daemon thread.
     *
     * Daemon so it never keeps the process alive; the loop is intentionally endless and is only
     * ever stopped by process death.
     */
    fun startThread(): Thread = thread(
        start = true,
        isDaemon = true,
        name = SystemLogCapture::class.java.simpleName,
    ) { start() }

    fun appendOutput(sb: StringBuilder) {
        tombstoneBuffer.appendString(sb)
        buffer.appendString(sb)
    }

    fun getOutput(): String = buildString {
        append(tombstoneBuffer.getString())
        append('\n')
        append(buffer.getString())
    }

    fun getPreviousOutput(): String = synchronized(persistenceLock) {
        runCatching { previousLogFile?.takeIf(File::exists)?.readText().orEmpty() }.getOrDefault("")
    }

    fun recordCrash(throwable: Throwable) {
        buffer.addLine(AppLog.redactSecrets(Log.getStackTraceString(throwable)))
        persist()
    }

    /**
     * Reads `logcat` until it dies, then waits a second and starts it again.
     *
     * The restart loop is the point: `logcat` is killed by the system on low memory and after a
     * `logcat -c`, and a capture that gave up on the first failure would silently stop recording
     * for the rest of the session. Failures are recorded into the buffer itself so that a report
     * shows *why* logs are missing.
     */
    private fun start() {
        if (!File(LOGCAT_PATH).exists()) {
            buffer.addLine("Unable to locate '$LOGCAT_PATH'")
            return
        }
        while (true) {
            var process: Process? = null
            try {
                process = ProcessBuilder(LOGCAT_PATH, "--pid=${AndroidProcess.myPid()}", "-v", "brief")
                    .redirectErrorStream(true)
                    .start()
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        captureLine(line)
                        if (SystemLogUtils.regexExtractTombstone.matches(line)) {
                            tombstoneBuffer.addLine(line)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                buffer.addLine("Exception getting system logs '$e'")
                e.stackTrace.forEach { buffer.addLine("    $it") }
            } finally {
                process?.destroy()
            }
            Thread.sleep(RESTART_DELAY_MS)
        }
    }

    private fun captureLine(line: String) {
        if (!shouldIncludeLogLine(line)) return
        val safeLine = AppLog.redactSecrets(line)
        buffer.addLine(safeLine)
        LOG_LINE.matchEntire(line)?.let { match ->
            val priority = priority(match.groupValues[1])
            if (match.groupValues[2] != APP_LOG_TAG) {
                AppLog.captureLogcat(priority, safeLine, isSystem = true)
            }
            // Each persist rewrites the whole buffer (up to 512 KB). Counting lines re-armed itself on
            // the line after every persist, so it ran per line and took ~9% of startup CPU; errors
            // still go to disk at once, everything else at most every couple of seconds.
            val now = SystemClock.uptimeMillis()
            if (priority >= Log.ERROR || now - lastPersistAt >= PERSIST_INTERVAL_MS) persist()
        }
    }

    private fun persist() {
        val file = synchronized(persistenceLock) {
            lastPersistAt = SystemClock.uptimeMillis()
            currentLogFile
        } ?: return
        runCatching { file.writeText(getOutput()) }
    }

    companion object {
        private const val LOGCAT_PATH = "/system/bin/logcat"
        private const val MAX_BUFFER_CHARACTERS = 524_288
        private const val MAX_TOMBSTONE_BUFFER_CHARACTERS = 102_400
        private const val RESTART_DELAY_MS = 1_000L

        /**
         * Drops the kernel's "chatty" rate-limit notices, which are pure noise and can outnumber
         * the real lines during a burst.
         */
        internal fun shouldIncludeLogLine(line: String): Boolean =
            !line.contains("chatty  : uid=") && LOG_LINE.matches(line)

        private val LOG_LINE = Regex("^([VDIWE])/([^(:]+)\\(\\s*\\d+\\):\\s?(.*)$")

        private const val APP_LOG_TAG = "Discord"
        private const val CURRENT_LOG_FILE = "debug-log-current.txt"
        private const val PREVIOUS_LOG_FILE = "debug-log-previous.txt"
        private const val PERSIST_INTERVAL_MS = 2_000L

        private fun priority(value: String): Int = when (value) {
            "V" -> Log.VERBOSE
            "D" -> Log.DEBUG
            "I" -> Log.INFO
            "W" -> Log.WARN
            else -> Log.ERROR
        }

    }
}
