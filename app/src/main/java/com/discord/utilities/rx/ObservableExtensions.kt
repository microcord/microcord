@file:JvmName("ObservableExtensionsKt")

package com.discord.utilities.rx

import android.content.Context
import com.discord.app.AppComponent
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.error.RestApiError
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import rx.Observable
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
 * Reference `restSubscribeOn(retry)`. The reference delegates the retry policy to
 * `com.discord.restapi.utils.RetryWithDelay`, which lives in a package this agent does not own and
 * has not been reconstructed; the same policy — retry only connectivity-class failures, with a
 * capped exponential delay — is applied inline here and should move to `RetryWithDelay` when that
 * class is built.
 *
 * Only callers that can safely repeat the request pass `retry = true`. `MessageQueue.doSend` sets it
 * from `message.nonce != null`, because a nonce lets the server de-duplicate a repeated send.
 */
@JvmOverloads
fun <T> Observable<T>.restSubscribeOn(retry: Boolean = true): Observable<T> {
    val subscribed = subscribeOn(Schedulers.io())
    return if (retry) subscribed.retryTransportFailures() else subscribed
}

private fun <T> Observable<T>.retryTransportFailures(): Observable<T> = retryWhen { failures ->
    failures.zipWith(Observable.range(1, REST_RETRY_MAX_ATTEMPTS + 1)) { failure, attempt -> failure to attempt }
        .flatMap { (failure, attempt) ->
            val type = RestApiError.from(failure).type
            val retryable = type == RestApiError.Type.NETWORK ||
                type == RestApiError.Type.TIMEOUT ||
                type == RestApiError.Type.INTERMITTENT_CLOUD_FLARE
            if (!retryable || attempt > REST_RETRY_MAX_ATTEMPTS) {
                Observable.error(failure)
            } else {
                val delayMs = (REST_RETRY_BASE_DELAY_MS shl (attempt - 1)).coerceAtMost(REST_RETRY_MAX_DELAY_MS)
                Observable.timer(delayMs, TimeUnit.MILLISECONDS)
            }
        }
}

private const val REST_RETRY_MAX_ATTEMPTS = 3
private const val REST_RETRY_BASE_DELAY_MS = 1_000L
private const val REST_RETRY_MAX_DELAY_MS = 10_000L

/** Drop nulls and re-type the stream as non-null. Reference `filterNull()`. */
fun <T : Any> Observable<T?>.filterNull(): Observable<T> = filter { it != null }.map { it!! }

/** Keep only emissions of type [R]. Reference `filterIs()`. */
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
    doOnSubscribe { dimmer?.setDimmed(true) }
        .delaySubscription(delay, TimeUnit.MILLISECONDS)
        .doOnNext { dimmer?.setDimmed(false) }
        .doOnError { dimmer?.setDimmed(false) }
        .doOnUnsubscribe { dimmer?.setDimmed(false) }

/**
 * Emit the first value of each [windowDuration] window immediately, then ignore the rest of it.
 *
 * Reference `leadingEdgeThrottle`, which lifts a custom `LeadingEdgeThrottle` operator; RxJava 1's
 * `throttleFirst` has exactly this contract.
 */
fun <T> Observable<T>.leadingEdgeThrottle(windowDuration: Long, timeUnit: TimeUnit): Observable<T> =
    throttleFirst(windowDuration, timeUnit)

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
