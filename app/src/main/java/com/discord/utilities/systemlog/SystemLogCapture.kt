package com.discord.utilities.systemlog

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
 * rolling window in memory, ready to attach.
 *
 * Two buffers, not one: tombstone lines (native crash traces) go into a second, smaller buffer that
 * is emitted *first*, so a native crash survives even after half a megabyte of ordinary logging has
 * pushed the rest of the window out.
 */
class SystemLogCapture {

    private val buffer = FixedSizeLineBuffer(MAX_BUFFER_CHARACTERS)
    private val tombstoneBuffer = FixedSizeLineBuffer(MAX_TOMBSTONE_BUFFER_CHARACTERS)

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
                process = ProcessBuilder(LOGCAT_PATH).redirectErrorStream(true).start()
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (shouldIncludeLogLine(line)) buffer.addLine(line)
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

    companion object {
        private const val LOGCAT_PATH = "/system/bin/logcat"
        private const val MAX_BUFFER_CHARACTERS = 524_288
        private const val MAX_TOMBSTONE_BUFFER_CHARACTERS = 102_400
        private const val RESTART_DELAY_MS = 1_000L

        /**
         * Drops the kernel's "chatty" rate-limit notices, which are pure noise and can outnumber
         * the real lines during a burst.
         */
        internal fun shouldIncludeLogLine(line: String): Boolean = !line.contains("chatty  : uid=")
    }
}
