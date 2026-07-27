package com.discord.utilities.system

import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.annotation.AnyThread
import com.discord.utilities.time.Clock
import com.discord.utilities.time.TimeSpan
import java.io.File
import kotlin.concurrent.thread

class DeviceResourceUsageMonitor(
    private val timeSpan: TimeSpan,
    private val clock: Clock,
    private val callback: (ResourceUsage) -> Unit,
) {
    private val threadSync = Any()
    private var activeThreadId: Long? = null
    private var thread: Thread? = null

    private fun monitor() {
        var previousStat: ProcStat? = null
        var previousTimeMillis: Long? = null
        while (true) {
            synchronized(threadSync) {
                if (activeThreadId != Thread.currentThread().id) return
            }
            if (Thread.interrupted()) return

            val stat = readStatFile()
            val now = clock.currentTimeMillis()
            if (previousStat != null && previousTimeMillis != null) {
                callback(
                    calculateResourceUsage(
                        previousTotalTime = previousStat.totalTime,
                        currentTotalTime = stat.totalTime,
                        rssPages = stat.rssPages,
                        elapsedSeconds = (now - previousTimeMillis).toDouble() / 1_000.0,
                        clockTicksPerSecond = clockTicksPerSecond,
                        pageSize = pageSize,
                        coreCount = availableCoreCount,
                    ),
                )
            }
            previousTimeMillis = now
            Thread.sleep(timeSpan.toMillis())
            previousStat = stat
        }
    }

    @AnyThread
    fun start() {
        val previousThread = synchronized(threadSync) {
            val previous = thread
            val newThread = thread(
                start = true,
                isDaemon = true,
                name = "DeviceResourceUsageMonitor",
                priority = 2,
            ) {
                try {
                    monitor()
                } catch (_: InterruptedException) {
                }
            }
            activeThreadId = newThread.id
            thread = newThread
            previous
        }
        previousThread?.interrupt()
    }

    @AnyThread
    fun stop() {
        val previousThread = synchronized(threadSync) {
            val previous = thread
            thread = null
            activeThreadId = null
            previous
        }
        previousThread?.interrupt()
    }

    @Suppress("deprecation")
    protected fun finalize() {
        stop()
    }

    data class ResourceUsage(
        val cpuUsagePercent: Int,
        val memoryRssBytes: Long,
        val cpuCoreCount: Int,
    )

    private data class ProcStat(val totalTime: Long, val rssPages: Long)

    companion object {
        private val clockTicksPerSecond = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L)
        private val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGE_SIZE) }.getOrDefault(4_096L)
        private val availableCoreCount = runCatching { Runtime.getRuntime().availableProcessors() }.getOrDefault(1)

        private fun readStatFile(): ProcStat {
            val input = try {
                File("/proc/${Process.myPid()}/stat").takeIf(File::exists)?.readText()
            } catch (_: Exception) {
                null
            } ?: return ProcStat(0, 0)
            if (input.isEmpty()) return ProcStat(0, 0)

            val fields = input.split(' ')
            val userTime = fields[13].toLongOrNull() ?: 0L
            val systemTime = fields[14].toLongOrNull() ?: 0L
            return ProcStat(userTime + systemTime, fields[23].toLongOrNull() ?: 0L)
        }

        internal fun calculateResourceUsage(
            previousTotalTime: Long,
            currentTotalTime: Long,
            rssPages: Long,
            elapsedSeconds: Double,
            clockTicksPerSecond: Long,
            pageSize: Long,
            coreCount: Int,
        ): ResourceUsage {
            val normalizedCoreCount = coreCount.takeUnless { it == 0 } ?: 1
            val cpuPercent =
                ((currentTotalTime - previousTotalTime) * 100L).toDouble() /
                    (clockTicksPerSecond.toDouble() * elapsedSeconds)
            return ResourceUsage(
                cpuUsagePercent = (cpuPercent / normalizedCoreCount).toInt(),
                memoryRssBytes = rssPages * pageSize,
                cpuCoreCount = normalizedCoreCount,
            )
        }
    }
}
