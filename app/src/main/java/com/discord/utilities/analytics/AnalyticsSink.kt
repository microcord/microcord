package com.discord.utilities.analytics

import com.discord.api.science.Science
import com.discord.app.AppLog
import com.discord.restapi.RestAPIInterface
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn

/**
 * Destination for a batch of analytics events.
 *
 * This is the seam that decides whether reconstructed-client telemetry leaves the device at all.
 * The reference has no such seam — `AnalyticsUtils.Tracker` posts unconditionally to Discord's
 * `POST /science` endpoint. Manticord is a reconstruction running against a live consumer backend
 * under a different application identity, so shipping it with the production analytics transport
 * wired up would silently send this client's telemetry to Discord. The default is therefore
 * [Disabled], and turning it on is one explicit assignment (see [AnalyticsSink.install]).
 *
 * See `PLAN.md` → "Live Backend Safety" and Phase 13, which registers Manticord-owned analytics
 * configuration.
 */
interface AnalyticsSink {

    /**
     * Delivers [events] for the session identified by [token].
     *
     * [onFailure] is invoked when delivery failed and the batch should be re-queued; a sink that
     * cannot fail never calls it.
     */
    fun deliver(token: String?, events: List<Science.Event>, onFailure: () -> Unit)

    /**
     * Drops every event.
     *
     * This is the default. `AnalyticsTracker` call sites, the throttling, the queue and
     * `StoreAnalytics` all behave exactly as they do with a live sink — the batch is simply
     * discarded at the last step, so no reconstructed-client telemetry is transmitted anywhere.
     */
    object Disabled : AnalyticsSink {
        override fun deliver(token: String?, events: List<Science.Event>, onFailure: () -> Unit) = Unit
    }

    /**
     * Records event *names* to [AppLog] and drops the batch.
     *
     * Useful when checking that a screen raises the events it should. Property values are never
     * written: analytics properties legitimately carry usernames, search queries, custom status
     * text and invite codes, and `AppLog` is a persisted in-app buffer.
     */
    object LocalLog : AnalyticsSink {
        override fun deliver(token: String?, events: List<Science.Event>, onFailure: () -> Unit) {
            events.forEach { event -> AppLog.recordBreadcrumb(event.type, "analytics") }
        }
    }

    /**
     * Posts the batch to `POST /science` on whatever backend [api] is configured for.
     *
     * **Not installed by default and must not be installed against Discord's production
     * backend.** It exists so the pipeline can be exercised end to end against a Manticord-owned
     * endpoint once Phase 13 registers one.
     */
    class ScienceEndpoint(private val api: RestAPIInterface) : AnalyticsSink {
        override fun deliver(token: String?, events: List<Science.Event>, onFailure: () -> Unit) {
            api.science(Science(token, events))
                .restSubscribeOn(retry = false)
                .appSubscribe(
                    errorTag = "AnalyticsSink",
                    errorHandler = {
                        // Event *names* only; see LocalLog.
                        AppLog.w("failed to log events: ${events.map(Science.Event::type)}")
                        onFailure()
                    },
                    onNext = {},
                )
        }
    }

    companion object {
        /**
         * The process-wide sink. **Analytics delivery is disabled by default**; assign a different
         * sink here to enable it, and nowhere else:
         *
         * ```
         * AnalyticsSink.install(AnalyticsSink.LocalLog)                    // names to AppLog
         * AnalyticsSink.install(AnalyticsSink.ScienceEndpoint(myOwnApi))   // real transport
         * ```
         *
         * Nothing in this project performs that assignment.
         */
        @Volatile
        var current: AnalyticsSink = Disabled
            private set

        fun install(sink: AnalyticsSink) {
            current = sink
        }

        /** Restores the default disabled sink. Used by tests so one case cannot leak into the next. */
        fun reset() {
            current = Disabled
        }
    }
}
