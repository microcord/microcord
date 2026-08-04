@file:JvmName("ObservableExtensionsKt")

package com.discord.utilities.rx

import android.content.Context
import com.discord.app.AppComponent
import com.discord.stores.utilities.LeadingEdgeThrottle
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.error.RestApiError
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import retrofit2.HttpException
import rx.Observable
import rx.Scheduler
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers

/**
 * The app's shared RxJava 1 operator vocabulary.
 *
 * Reconstruction of the reference `com.discord.utilities.rx.ObservableExtensionsKt`. The important
 * piece is [appSubscribe]: it is the single place where a stream failure is classified into a
 * [RestApiError], offered to the caller, reported to the crash sink and surfaced to the user. Every
 * REST-backed subscription in the app is supposed to go through it, so that `subscribe({}, {})`
 * never silently swallows a failure.
 *
 * Operators from the reference file that depend on types this project has not reconstructed
 * (`MGRecyclerAdapterSimple` for adapter unbinding, `RxCoroutineUtils`, `OperatorBufferedDelay`)
 * are intentionally absent rather than stubbed; they are recorded as gaps in `PLAN.md`.
 */

/** Observe on the Android main thread. */
fun <T> Observable<T>.ui(): Observable<T> = observeOn(AndroidSchedulers.mainThread())

/**
 * Observe on the main thread and stop when [appComponent]'s lifecycle ends.
 * Reference `ui(appComponent, adapter)` minus the adapter unbinding hook.
 */
fun <T> Observable<T>.ui(appComponent: AppComponent): Observable<T> =
    ui().bindToComponentLifecycle(appComponent)

/**
 * Terminate the stream when [appComponent] emits its unsubscribe signal.
 *
 * Reference `bindToComponentLifecycle`. The reference additionally filters emissions while a
 * `Fragment` is detached or its activity is finishing; there is no `AppFragment` base class in this
 * project yet, so the filter is applied by the caller instead.
 */
fun <T> Observable<T>.bindToComponentLifecycle(appComponent: AppComponent): Observable<T> =
    takeUntil(appComponent.unsubscribeSignal)

/** Buffer under back pressure and move both subscription and delivery to the computation pool. */
fun <T> Observable<T>.computationBuffered(): Observable<T> =
    onBackpressureBuffer().subscribeOn(Schedulers.computation()).observeOn(Schedulers.computation())

/** Drop all but the latest value under back pressure, on the computation pool. */
fun <T> Observable<T>.computationLatest(): Observable<T> =
    onBackpressureLatest().subscribeOn(Schedulers.computation()).observeOn(Schedulers.computation())

/**
 * Subscribe REST work on the IO pool, optionally retrying transient transport failures.
 *
 * Reference `restSubscribeOn(retry)`, which delegates to
 * `com.discord.restapi.utils.RetryWithDelay.restRetry(delay = 1000, maxRetries = 3,
 * maxBackoffs = 3, predicate = ::isNetworkError)`. That class lives in a package this slice does not
 * own and has not been reconstructed yet, so its policy is applied inline here and should move to
 * `RetryWithDelay` once it exists. The policy, taken from the reference predicate:
 *
 * - Only `IOException`s are retried at all.
 * - An `HttpException` is never retried when the request was **not** a GET, nor for status
 *   401/403/404/429/503. A `TimeoutException` is never retried.
 * - Delay is 1s for the first retry, then doubles (2s, 4s), for at most three retries.
 *
 * The "non-GET is never retried" rule is why `MessageQueue`'s own [com.discord.utilities.networking.Backoff]
 * is the real network retry for sends: a POST or PATCH never gets a second transport attempt here.
 * `MessageQueue.doSend` still passes `retry = message.nonce != null`, matching the reference.
 */
@JvmOverloads
fun <T> Observable<T>.restSubscribeOn(retry: Boolean = true): Observable<T> {
    val subscribed = subscribeOn(Schedulers.io())
    return if (retry) subscribed.retryTransportFailures() else subscribed
}

private fun <T> Observable<T>.retryTransportFailures(): Observable<T> = retryWhen { failures ->
    var attempt = 0
    var delayMs = REST_RETRY_INITIAL_DELAY_MS
    failures.flatMap { failure: Throwable ->
        if (attempt++ < REST_RETRY_MAX_ATTEMPTS && failure.isRetryableTransportFailure()) {
            if (attempt > 1) delayMs *= 2
            Observable.timer(delayMs, TimeUnit.MILLISECONDS)
        } else {
            Observable.error<Long>(failure)
        }
    }
}

/** Reference `RetryWithDelay$restRetry$1`, the `isNetworkError` predicate. */
private fun Throwable.isRetryableTransportFailure(): Boolean {
    when (this) {
        is HttpException -> {
            val method = response()?.raw()?.request?.method
            if (method != null && method != "GET") return false
            if (code() in NON_RETRYABLE_STATUS_CODES) return false
        }

        is TimeoutException -> return false
    }
    return this is IOException
}

private val NON_RETRYABLE_STATUS_CODES = setOf(401, 403, 404, 429, 503)
private const val REST_RETRY_MAX_ATTEMPTS = 3
private const val REST_RETRY_INITIAL_DELAY_MS = 1_000L

/** Drop nulls and re-type the stream as non-null. Reference `filterNull()`. */
fun <T : Any> Observable<T?>.filterNull(): Observable<T> = filter { it != null }.map { it!! }

/** Keep only emissions of type [R]. Reference `filterIs()`. */
@Suppress("UNCHECKED_CAST")
inline fun <reified R : Any> Observable<*>.filterIs(): Observable<R> =
    filter { it is R }.map { it as R }

/**
 * Take a single value, giving up after [milliseconds].
 *
 * When [emitError] is true the timeout propagates a `TimeoutException`; otherwise the stream simply
 * completes empty. Reference `takeSingleUntilTimeout(milliseconds, emitError)`.
 */
@JvmOverloads
fun <T> Observable<T>.takeSingleUntilTimeout(
    milliseconds: Long = 5_000L,
    emitError: Boolean = true,
): Observable<T> {
    val single = take(1)
    val bounded = if (emitError) {
        single.timeout(milliseconds, TimeUnit.MILLISECONDS)
    } else {
        single.takeUntil(Observable.timer(milliseconds, TimeUnit.MILLISECONDS))
    }
    return bounded.computationLatest()
}

/** Collect a stream of pairs into a single map emission. Reference `toMap()`. */
fun <K, V> Observable<Pair<K, V>>.toMap(): Observable<Map<K, V>> =
    toMap({ it.first }, { it.second })

/**
 * Emit no earlier than [delay] milliseconds after subscription and dim [dimmer] while waiting.
 *
 * Reference `withDimmer(dimmer, delay)`, which lifts an `OnDelayedEmissionHandler`. The observable
 * behavior that matters — nothing renders before the delay, and the dimmer is shown for exactly that
 * window — is reproduced with standard operators.
 */
@JvmOverloads
fun <T> Observable<T>.withDimmer(dimmer: DimmerView?, delay: Long = 300L): Observable<T> =
    withDimmer(delay, { dimmer?.setDimmed(it) })

internal fun <T> Observable<T>.withDimmer(
    delay: Long,
    setDimmed: (Boolean) -> Unit,
    dimmerScheduler: Scheduler = AndroidSchedulers.mainThread(),
): Observable<T> =
    doOnSubscribe { dimmerScheduler.schedule { setDimmed(true) } }
        .delaySubscription(delay, TimeUnit.MILLISECONDS)
        .doOnNext { dimmerScheduler.schedule { setDimmed(false) } }
        .doOnError { dimmerScheduler.schedule { setDimmed(false) } }
        .doOnCompleted { dimmerScheduler.schedule { setDimmed(false) } }
        .doOnUnsubscribe { dimmerScheduler.schedule { setDimmed(false) } }

private fun Scheduler.schedule(action: () -> Unit) {
    val worker = createWorker()
    worker.schedule {
        try {
            action()
        } finally {
            worker.unsubscribe()
        }
    }
}

/**
 * Emit the first value immediately, then the newest value received during [windowDuration].
 *
 * This preserves store changes that arrive during a burst instead of dropping them permanently.
 */
fun <T> Observable<T>.leadingEdgeThrottle(windowDuration: Long, timeUnit: TimeUnit): Observable<T> =
    lift(LeadingEdgeThrottle(windowDuration, timeUnit))

/**
 * Subscribe with the app's error funnel.
 *
 * Failures are classified by [RestApiError.handle] under [errorClass]'s simple name: [errorHandler]
 * gets first refusal, anything unclassified is reported to the crash sink, and the user-facing toast
 * is shown against [context] when one was supplied. [onTerminated] runs on both completion and
 * failure so callers can release resources exactly once.
 *
 * [subscriptionHandler] receives the live [Subscription] so the caller can cancel the work later —
 * `MessageQueue` uses it to record the in-flight request.
 */
/**
 * [appSubscribe] for an endpoint declared `Observable<Void>`.
 *
 * A 204 makes Retrofit emit `null`. The generic overload infers `T` as the non-null `Void`, so a
 * caller's `{ ... }` gets an implicit `it: Void` and the compiler plants a null check that throws
 * before the body runs — turning a successful request into a failure toast. This overload takes a
 * value-less callback instead, and being the more specific receiver it wins overload resolution, so
 * existing call sites need no change.
 */
fun Observable<Void>.appSubscribe(
    errorClass: Class<*>,
    context: Context? = null,
    subscriptionHandler: ((Subscription) -> Unit)? = null,
    errorHandler: ((RestApiError) -> Unit)? = null,
    onCompleted: () -> Unit = {},
    onTerminated: () -> Unit = {},
    onNext: () -> Unit,
) {
    appSubscribe(
        errorTag = errorClass.simpleName,
        context = context,
        subscriptionHandler = subscriptionHandler,
        errorHandler = errorHandler,
        onCompleted = onCompleted,
        onTerminated = onTerminated,
        onNext = onNext,
    )
}

/** [appSubscribe] for `Observable<Void>` with an explicit error tag. See the overload above. */
fun Observable<Void>.appSubscribe(
    errorTag: String,
    context: Context? = null,
    subscriptionHandler: ((Subscription) -> Unit)? = null,
    errorHandler: ((RestApiError) -> Unit)? = null,
    onCompleted: () -> Unit = {},
    onTerminated: () -> Unit = {},
    onNext: () -> Unit,
) {
    val contextRef = WeakReference(context)
    val subscription = subscribe(
        { onNext() },
        { failure ->
            RestApiError.handle(failure, errorTag, errorHandler, contextRef.get())
            onTerminated()
        },
        {
            onCompleted()
            onTerminated()
        },
    )
    subscriptionHandler?.invoke(subscription)
}

fun <T> Observable<T>.appSubscribe(
    errorClass: Class<*>,
    context: Context? = null,
    subscriptionHandler: ((Subscription) -> Unit)? = null,
    errorHandler: ((RestApiError) -> Unit)? = null,
    onCompleted: () -> Unit = {},
    onTerminated: () -> Unit = {},
    onNext: (T) -> Unit,
) {
    appSubscribe(
        errorTag = errorClass.simpleName,
        context = context,
        subscriptionHandler = subscriptionHandler,
        errorHandler = errorHandler,
        onCompleted = onCompleted,
        onTerminated = onTerminated,
        onNext = onNext,
    )
}

/**
 * [appSubscribe] with an explicit error tag instead of a class.
 *
 * The [Context] is held weakly: a stream can outlive the screen that started it, and the reference
 * takes the same precaution before showing a toast.
 */
fun <T> Observable<T>.appSubscribe(
    errorTag: String,
    context: Context? = null,
    subscriptionHandler: ((Subscription) -> Unit)? = null,
    errorHandler: ((RestApiError) -> Unit)? = null,
    onCompleted: () -> Unit = {},
    onTerminated: () -> Unit = {},
    onNext: (T) -> Unit,
) {
    val contextRef = WeakReference(context)
    val subscription = subscribe(
        { value -> onNext(value) },
        { failure ->
            RestApiError.handle(failure, errorTag, errorHandler, contextRef.get())
            onTerminated()
        },
        {
            onCompleted()
            onTerminated()
        },
    )
    subscriptionHandler?.invoke(subscription)
}
