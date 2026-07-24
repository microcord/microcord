package com.discord.utilities.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Scheduler
import rx.Subscription
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject

internal class BackgroundDelay(private val scheduler: Scheduler) {
    private val state = BehaviorSubject.create(true)
    private var pending: Subscription? = null

    fun observe(): Observable<Boolean> = state.distinctUntilChanged()

    fun paused() {
        pending?.unsubscribe()
        pending = Observable.just(true)
            .delay(DELAY_MS, TimeUnit.MILLISECONDS, scheduler)
            .subscribe(state::onNext)
    }

    fun resumed() {
        pending?.unsubscribe()
        pending = null
        state.onNext(false)
    }

    private companion object {
        const val DELAY_MS = 2_000L
    }
}

object Backgrounded : Application.ActivityLifecycleCallbacks {
    private val delay = BackgroundDelay(Schedulers.computation())

    fun init(application: Application) = application.registerActivityLifecycleCallbacks(this)
    fun observe(): Observable<Boolean> = delay.observe()

    override fun onActivityPaused(activity: Activity) = delay.paused()
    override fun onActivityResumed(activity: Activity) = delay.resumed()
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
