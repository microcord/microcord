package com.discord.utilities.video

import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Scheduler
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import rx.subscriptions.SerialSubscription

class VideoPlayerIdleDetector(
    private val idleDetectionMs: Long = 3_000L,
    private val idleDetectionScheduler: Scheduler = AndroidSchedulers.mainThread(),
    private val backgroundThreadScheduler: Scheduler = Schedulers.computation(),
    private val onIdleStateChanged: (Boolean) -> Unit,
) {
    private var idleDetectionSubscription: Subscription? = null
    private var idle = false

    fun beginIdleDetection() {
        if (idleDetectionSubscription == null) {
            setIdle(false)
            beginIdleDetectionTimer()
        }
    }

    fun dispose() {
        cancelIdleDetectionTimer()
        setIdle(false)
    }

    fun endIdleDetection() {
        cancelIdleDetectionTimer()
        setIdle(false)
    }

    fun isIdle(): Boolean = idle

    @JvmOverloads
    fun onInteraction(otherIsIdle: Boolean = true) {
        if (!idle && !otherIsIdle) {
            cancelIdleDetectionTimer()
            setIdle(true)
        } else {
            setIdle(false)
            beginIdleDetectionTimer()
        }
    }

    fun onPreventIdle() {
        setIdle(false)
        if (idleDetectionSubscription != null) beginIdleDetectionTimer()
    }

    private fun beginIdleDetectionTimer() {
        cancelIdleDetectionTimer()
        val serial = SerialSubscription()
        idleDetectionSubscription = serial
        serial.set(
            Observable.timer(idleDetectionMs, TimeUnit.MILLISECONDS, backgroundThreadScheduler)
                .observeOn(idleDetectionScheduler)
                .subscribe { setIdle(true) },
        )
    }

    private fun cancelIdleDetectionTimer() {
        idleDetectionSubscription?.unsubscribe()
        idleDetectionSubscription = null
    }

    private fun setIdle(isIdle: Boolean) {
        if (idle == isIdle) return
        idle = isIdle
        onIdleStateChanged(isIdle)
    }
}
