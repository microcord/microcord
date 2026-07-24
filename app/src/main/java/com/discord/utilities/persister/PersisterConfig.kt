package com.discord.utilities.persister

import android.content.Context
import com.discord.utilities.lifecycle.Backgrounded
import com.discord.utilities.time.Clock
import java.util.concurrent.TimeUnit
import rx.Observable

object PersisterConfig {
    fun init(context: Context, clock: Clock) {
        Persister.init(context, clock, Observable.never())
        Observable.merge(
            Backgrounded.observe().filter { it },
            Observable.interval(1, TimeUnit.MINUTES).map { true },
        ).subscribe { Persister.persistAll() }
    }
}
