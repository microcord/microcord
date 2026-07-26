package com.discord.utilities.systemlog

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.schedulers.Schedulers

/**
 * Reads the device log, and reconstructs the last native crash out of it.
 *
 * A native crash inside `libdiscord.so` never reaches the JVM crash handler — the process is gone
 * before any Java code runs — so the only record of it is the `DEBUG` tombstone the system writes
 * to logcat. Recovering that on the *next* launch is the only way the client learns a native crash
 * happened at all.
 *
 * Not reconstructed: `SystemLogReport`, which forwards the recovered tombstone to Crashlytics and
 * the analytics tracker. Both of those subsystems are absent from this project, and reporting is
 * the only thing it does.
 */
object SystemLogUtils {

    internal const val LOGCAT_PATH = "/system/bin/logcat"

    private const val LIBDISCORD_VERSION_KEY = "libdiscord_version"

    /** Hard cap on how much log text a single fetch will return. */
    private const val MAX_LOG_SIZE = 2_500_000

    /**
     * Matches the two line shapes a tombstone is made of: the system's `F DEBUG :` frames, and any
     * line carrying the native library's version marker.
     *
     * The version marker is captured separately because it is logged *before* the crash banner and
     * is what identifies which `libdiscord.so` build produced the trace.
     */
    // Written literally rather than through `Regex.escape`: the marker has no metacharacters, and
    // `\Q…\E` blocks are one more thing for Android's ICU engine to disagree with the JVM about.
    internal val regexExtractTombstone = Regex(
        "(?:^(?:[^\\s]+\\s+){4}F\\s+DEBUG\\s+:\\s(.+))|(^.+(\\[libdiscord_version\\].+))"
    )

    private val systemLogCapture = SystemLogCapture()

    /** Starts the rolling in-memory logcat tail. Call once, at app start. */
    fun initSystemLogCapture(): Thread = systemLogCapture.startThread()

    /** The captured window, tombstone lines first. */
    fun fetch(): String = buildString { systemLogCapture.appendOutput(this) }

    /**
     * Runs `logcat -d` on a fresh thread and returns the lines matching [filter] (all lines when
     * null).
     *
     * A new thread rather than the IO pool: the read blocks for as long as `logcat` takes to dump,
     * and this runs during startup where the IO pool is contended.
     */
    private fun fetch(filter: Regex?): Observable<LinkedList<String>> {
        val output = LinkedList<String>()
        val logErrors = filter == null
        if (!File(LOGCAT_PATH).exists()) {
            if (logErrors) output.add("Unable to locate '$LOGCAT_PATH'")
            return Observable.just(output)
        }
        return Observable.fromCallable {
            var process: Process? = null
            try {
                process = ProcessBuilder(LOGCAT_PATH, "-d").redirectErrorStream(true).start()
                waitFor(process)
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use {
                    processLogs(it, output, filter)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (logErrors) {
                    output.add("Exception getting system logs '$e'")
                    e.stackTrace.forEach { output.add("    $it") }
                }
            } finally {
                process?.destroy()
            }
            output
        }.subscribeOn(Schedulers.newThread())
    }

    /** The last native crash recorded in the device log, if there is one. */
    fun fetchLastTombstone(): Observable<Tombstone?> =
        fetch(regexExtractTombstone).map { fetchLastTombstone(it) }

    /**
     * Reads [reader] into [output], applying [filter] and the size cap.
     *
     * When a filter is given the *last non-empty capture group* is kept rather than the whole line
     * — the tombstone pattern has two alternatives with differently-numbered groups, and taking the
     * last populated one is what makes a single pass work for both.
     *
     * "Accessing hidden method" lines are dropped: the platform emits one per reflective call and
     * they can be thousands of lines of pure noise.
     */
    internal fun processLogs(reader: BufferedReader, output: LinkedList<String>, filter: Regex?) {
        var totalLength = 0
        try {
            while (true) {
                val rawLine = reader.readLine() ?: break
                val line = if (filter == null) {
                    rawLine
                } else {
                    val match = filter.find(rawLine) ?: continue
                    match.groupValues.lastOrNull { it.isNotEmpty() } ?: continue
                }
                if (line.isBlank()) continue
                if (line.contains("Accessing hidden method")) continue

                output.add(line)
                totalLength += line.length + 1
                while (totalLength > MAX_LOG_SIZE && output.isNotEmpty()) {
                    totalLength -= output.pop().length + 1
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (filter == null) output.add("IOException: $e")
        }
    }

    /**
     * Turns raw tombstone lines into a single crash record.
     *
     * Only the *last* tombstone in the window is kept: each crash banner resets the accumulator, so
     * a log holding several crashes yields the most recent one.
     */
    internal fun fetchLastTombstone(crashes: Collection<String>): Tombstone? {
        if (crashes.isEmpty()) return null

        val builder = StringBuilder()
        var latestVersion: String? = null
        var tombstoneVersion: String? = null
        for (line in crashes) {
            if (line.contains(LIBDISCORD_VERSION_KEY)) {
                latestVersion = line
                continue
            }
            if (line.contains(CRASH_BANNER)) {
                builder.clear()
                // The version logged most recently before this banner is the one that crashed.
                tombstoneVersion = latestVersion
            }
            builder.append(line).append('\n')
        }
        if (builder.isNotBlank()) {
            builder.append('\n')
                .append("Tombstone's libdiscord_version: ")
                .append(tombstoneVersion ?: "Unknown libdiscord_version")
                .append('\n')
        }

        val text = builder.toString()
        if (text.isBlank()) return null

        // Stack frames, with per-split dex file names normalised away so the same crash from two
        // different dex splits groups together.
        val frames = STACK_FRAME.findAll(text)
            .map { REMOVE_DEX_NAME.replace(it.groupValues[1], "classesN.dex") }
            .toList()

        // The topmost frame that is not part of the abort machinery itself — that is the frame
        // that actually identifies the crash.
        val origin = frames.firstOrNull { it !in ABORT_FRAMES } ?: "Unknown"
        val groupBy = frames.joinToString("\n")
        val cause = CAUSE.find(text)?.groupValues?.get(1)

        return Tombstone(
            text = text,
            cause = cause,
            groupBy = groupBy,
            origin = origin,
            groupHash = hashString(groupBy),
            textHash = hashString(text),
        )
    }

    /**
     * Polls for the process to exit, giving up after fifteen seconds.
     *
     * `Process.waitFor` is uninterruptible on older platforms, and a `logcat` that never exits
     * would hang the capture thread for the life of the app.
     */
    private fun waitFor(process: Process) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_FOR_TIMEOUT_SECONDS)
        while (true) {
            try {
                process.exitValue()
                return
            } catch (e: IllegalThreadStateException) {
                Thread.sleep(WAIT_FOR_POLL_MS)
                if (System.nanoTime() >= deadline) return
            }
        }
    }

    private fun hashString(input: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * One recovered native crash.
     *
     * [groupBy] and [groupHash] cover the stack only, so two crashes with the same stack but
     * different register dumps are one issue; [textHash] covers everything and is what tells the
     * client whether it has already reported this exact tombstone.
     */
    data class Tombstone(
        val text: String,
        val cause: String?,
        val groupBy: String,
        val origin: String,
        val groupHash: String,
        val textHash: String,
    )

    private const val WAIT_FOR_TIMEOUT_SECONDS = 15L
    private const val WAIT_FOR_POLL_MS = 100L
    private const val CRASH_BANNER =
        "*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***"

    // Regexes are written with every metacharacter escaped: Android's ICU engine rejects bare
    // closing delimiters that the JVM's engine tolerates.
    private val CAUSE = Regex("^Cause: (.+)$", RegexOption.MULTILINE)
    private val STACK_FRAME =
        Regex("^\\s+#\\d+ pc .+/(.+? .+?)\\+?[+)]", RegexOption.MULTILINE)
    private val REMOVE_DEX_NAME = Regex("classes\\d+\\.dex")

    private val ABORT_FRAMES = hashSetOf(
        "libc.so (abort",
        "libart.so (art::Runtime::Abort(char const*",
        "libbase.so (android::base::LogMessage::~LogMessage(",
    )
}
