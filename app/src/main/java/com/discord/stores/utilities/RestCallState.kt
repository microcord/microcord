package com.discord.stores.utilities

import android.content.Context
import android.widget.Toast
import androidx.annotation.MainThread
import com.discord.utilities.error.RestApiError
import rx.Observable
import rx.Scheduler
import rx.Subscription
import rx.schedulers.Schedulers

/**
 * Shared lifecycle of a single REST-backed value.
 *
 * A screen or store that owns a request keeps one [RestCallState] rather than a private
 * "isLoading"/"error" pair. Invoking the state yields the successful response, or `null`
 * while the call has not produced one.
 */
sealed class RestCallState<out Response> {
    open operator fun invoke(): Response? = null
}

/** No request has been started yet. */
object Default : RestCallState<Nothing>()

/** A request is in flight. */
object Loading : RestCallState<Nothing>()

/** The request completed and produced [response]. */
data class Success<out T>(val response: T) : RestCallState<T>() {
    override fun invoke(): T = response
}

/** The request terminated with [error]. */
data class Failure<out T>(val error: RestApiError) : RestCallState<T>()

/**
 * Subscribes to a REST observable and pushes every state transition into [resultHandler]:
 * [Loading] synchronously, then either [Success] or [Failure].
 */
fun <T> Observable<T>.executeRequest(
    scheduler: Scheduler = Schedulers.io(),
    resultHandler: (RestCallState<T>) -> Unit,
): Subscription {
    resultHandler(Loading)
    return subscribeOn(scheduler).subscribe(
        { response -> resultHandler(Success<T>(response)) },
        { throwable -> resultHandler(Failure<T>(RestApiError.from(throwable))) },
    )
}

/**
 * Dispatches a [RestCallState] to the matching handler. [Default] is intentionally ignored so
 * a screen that has not started its request renders nothing.
 *
 * By default a failure surfaces the server-provided message as a toast, mirroring the
 * reference behaviour of showing the error's toasts.
 */
@MainThread
fun <T> RestCallState<T>.handleResponse(
    context: Context,
    onLoading: (Loading) -> Unit = {},
    onFailure: (Failure<T>) -> Unit = { failure -> showErrorToast(context, failure.error) },
    onSuccess: (Success<T>) -> Unit = {},
) {
    when (this) {
        is Loading -> onLoading(this)
        is Success -> onSuccess(this)
        is Failure -> onFailure(this)
        is Default -> Unit
    }
}

private fun showErrorToast(context: Context, error: RestApiError) {
    val message = error.message ?: error.messages.values.firstOrNull()?.firstOrNull() ?: return
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
