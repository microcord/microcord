package com.discord.utilities.analytics

import android.net.Uri
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import java.util.UUID

/**
 * Cold-start timing: how the app was opened, and how long it took to show something.
 *
 * One [openAppLoadId] ties `app_opened` to the `app_ui_viewed` that follows it, so a slow start can
 * be attributed to the route that caused it. [appUiViewed] is raised exactly once per process, from
 * `StoreAnalytics.onScreenViewed`.
 */
class AppStartAnalyticsTracker(
    private val tracker: AnalyticsUtils.Tracker = AnalyticsUtils.Tracker.instance,
    private val clock: Clock = ClockFactory.get(),
    private val themeProvider: () -> String = { "" },
) {

    private val openAppLoadId: String = UUID.randomUUID().toString()

    @Volatile
    private var appOpenTimestamp: Long? = null

    /**
     * Raised when the app is brought up, throttled to once per five minutes so returning to a
     * backgrounded process does not look like a fresh launch.
     */
    fun appOpen(uri: Uri, uriCanBeRouted: Boolean, isNotificationRoute: Boolean) {
        tracker.track(APP_OPENED to null, THROTTLE_MS) {
            val openedFrom = when {
                isNotificationRoute -> "notification"
                uriCanBeRouted -> "deeplink"
                else -> "launcher"
            }
            val properties = mutableMapOf<String, Any>(
                "opened_from" to openedFrom,
                "theme" to themeProvider(),
            )
            insertUriProperties(properties, uri)
            if (appOpenTimestamp == null) {
                properties["load_id"] = openAppLoadId
                appOpenTimestamp = clock.currentTimeMillis()
            }
            properties
        }

        val firstLaunch = Persister(APP_FIRST_LAUNCHED, true)
        if (firstLaunch.get()) {
            tracker.track(APP_FIRST_LAUNCHED, mapOf("platform" to "Android"))
            firstLaunch.set(false, persist = true)
        }
    }

    /** Raised for the first screen the user actually sees. */
    fun appUiViewed(screenName: String, time: Long) {
        val durationMs = appOpenTimestamp?.let { time - it } ?: UNKNOWN_DURATION_MS
        tracker.track(
            "app_ui_viewed",
            mapOf(
                "screen_name" to screenName,
                "load_id" to openAppLoadId,
                "duration_ms_since_app_opened" to durationMs,
                "has_cached_data" to true,
                "theme" to themeProvider(),
            ),
        )
    }

    /**
     * Records only the parts of [uri] that identify the *route*.
     *
     * The query string is deliberately excluded and the path is capped: deep links carry invite
     * codes, OAuth state and remote-auth fingerprints.
     */
    private fun insertUriProperties(properties: MutableMap<String, Any>, uri: Uri): MutableMap<String, Any> {
        uri.host?.let { properties["uri_host"] = it }
        uri.scheme?.let { properties["uri_scheme"] = it }
        uri.path?.takeUnless(String::isEmpty)?.let { path ->
            properties["uri_path"] = if (path.length > MAX_URI_PATH_LENGTH) path.substring(0, MAX_URI_PATH_LENGTH - 1) else path
        }
        return properties
    }

    companion object {
        private const val APP_OPENED = "app_opened"
        private const val APP_FIRST_LAUNCHED = "app_first_launched"
        private const val THROTTLE_MS = 300_000L
        private const val MAX_URI_PATH_LENGTH = 100

        /** Reported when the first screen appeared before `app_opened` was ever raised. */
        private const val UNKNOWN_DURATION_MS = -1L

        @Volatile
        @JvmStatic
        var instance: AppStartAnalyticsTracker = AppStartAnalyticsTracker()
            private set

        /** `StoreAnalytics.init` installs the store-backed instance. */
        @JvmStatic
        fun install(tracker: AppStartAnalyticsTracker) {
            instance = tracker
        }
    }
}
