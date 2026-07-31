package com.discord.screenshot_detection

import android.app.Activity
import android.app.Application
import android.content.ContentResolver
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.discord.app.AppLog
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import java.util.Locale
import rx.Observable
import rx.subjects.PublishSubject

/** Detects recent images written to a Screenshots directory while an activity is resumed. */
class ScreenshotDetector(
    application: Application,
    private val logger: AppLog,
    private val cache: SharedPreferences,
    clock: Clock = ClockFactory.get(),
) {
    data class Screenshot(val uri: Uri, val filename: String)

    private val publishSubject = PublishSubject.create<Screenshot>()
    private val observer = ScreenshotContentObserver(application.contentResolver, clock, logger) { uri, filename ->
        if (enabled) publishSubject.onNext(Screenshot(uri, filename))
    }
    private val activityCallbacks = ActivityCallbacks(application.contentResolver, observer, logger) { enabled }

    var enabled: Boolean = cache.getBoolean(PREF_ENABLED, false)
        private set

    val screenshots: Observable<Screenshot> = publishSubject.asObservable()

    init {
        application.registerActivityLifecycleCallbacks(activityCallbacks)
        activityCallbacks.updateRegistration()
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        cache.edit().putBoolean(PREF_ENABLED, value).apply()
        activityCallbacks.updateRegistration()
    }

    private class ActivityCallbacks(
        private val resolver: ContentResolver,
        private val observer: ContentObserver,
        private val logger: AppLog,
        private val isEnabled: () -> Boolean,
    ) : Application.ActivityLifecycleCallbacks {
        private var resumedActivities = 0
        private var registered = false

        fun updateRegistration() {
            val shouldRegister = isEnabled() && resumedActivities > 0
            if (shouldRegister == registered) return
            try {
                if (shouldRegister) {
                    resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
                } else {
                    resolver.unregisterContentObserver(observer)
                }
                registered = shouldRegister
            } catch (failure: RuntimeException) {
                logger.w("Could not update screenshot observer registration", failure)
                registered = false
            }
        }

        override fun onActivityResumed(activity: Activity) {
            resumedActivities++
            updateRegistration()
        }

        override fun onActivityPaused(activity: Activity) {
            resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
            updateRegistration()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private class ScreenshotContentObserver(
        private val resolver: ContentResolver,
        private val clock: Clock,
        private val logger: AppLog,
        private val onScreenshot: (Uri, String) -> Unit,
    ) : ContentObserver(null) {
        private var previousPath: String? = null

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            if (uri == null || !uri.toString().startsWith(MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString())) return
            try {
                process(uri)
            } catch (failure: Exception) {
                logger.e("Error processing screenshot", failure)
            }
        }

        private fun process(uri: Uri) {
            // ponytail: API 33+ MediaStore reads need READ_MEDIA_IMAGES; add it only when manifest
            // scope permits, otherwise the caller gets the logged SecurityException path.
            val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            val projection = buildList {
                add(MediaStore.Images.Media.DISPLAY_NAME)
                add(MediaStore.Images.Media.DATE_ADDED)
                add(MediaStore.Images.Media._ID)
                if (modern) {
                    add(MediaStore.Images.Media.IS_PENDING)
                    add(MediaStore.Images.Media.RELATIVE_PATH)
                } else {
                    add(MediaStore.Images.Media.DATA)
                }
            }.toTypedArray()

            resolver.query(uri, projection, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
                if (!cursor.moveToFirst()) return
                if (modern && cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING)) == 1) return

                val filename = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                val directory = if (modern) {
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH))
                } else {
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)).substringBeforeLast('/')
                }
                val path = "$directory/$filename"
                val dateAddedSeconds = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                if (!isRecentScreenshot(path, dateAddedSeconds, clock.currentTimeMillis(), previousPath)) return

                previousPath = path
                onScreenshot(uri, filename)
            }
        }
    }

    companion object {
        private const val PREF_ENABLED = "screenshot_detection_enabled"
        private const val SCREENSHOT_MAX_AGE_SECONDS = 10L

        @Volatile
        var instance: ScreenshotDetector? = null

        internal fun isRecentScreenshot(
            path: String,
            dateAddedSeconds: Long,
            nowMillis: Long,
            previousPath: String?,
        ): Boolean = path != previousPath &&
            path.lowercase(Locale.getDefault()).contains("screenshots/") &&
            kotlin.math.abs(nowMillis / 1_000L - dateAddedSeconds) <= SCREENSHOT_MAX_AGE_SECONDS
    }
}
