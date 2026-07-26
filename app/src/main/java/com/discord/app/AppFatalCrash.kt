package com.discord.app

import android.annotation.SuppressLint
import android.os.Build
import android.view.View
import android.widget.TextView
import com.discord.BuildConfig
import com.discord.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Last-resort screen shown when [AppActivity] cannot finish creating itself.
 *
 * The reference launches a dedicated crash screen activity. Doing that here would risk a restart
 * loop, because the failures this catches are boot failures that would repeat immediately, so the
 * fallback is rendered in place instead.
 */
object AppFatalCrash {

    /**
     * Replaces the activity's content with the crash report layout.
     *
     * Only device and build metadata is shown; the throwable is logged, never rendered, so nothing
     * a stack trace happened to capture ends up on screen.
     */
    @SuppressLint("SimpleDateFormat")
    fun render(activity: AppActivity, throwable: Throwable, crashSource: String) {
        AppLog.e("Fatal crash in $crashSource", throwable)
        val view = activity.layoutInflater.inflate(R.layout.widget_fatal_crash, null)
        activity.setContentView(view)

        view.setText(R.id.fatal_crash_source, R.string.crash_source, crashSource)
        view.setText(R.id.fatal_crash_time, R.string.crash_timestamp, gmtTimestamp())
        view.setText(R.id.fatal_crash_app_version, R.string.crash_app_version, BuildConfig.VERSION_NAME)
        view.setText(R.id.fatal_crash_device, R.string.crash_device, "${Build.MODEL} ${Build.PRODUCT}")
        view.setText(R.id.fatal_crash_os_version, R.string.crash_device_version, Build.VERSION.SDK_INT.toString())
    }

    private fun gmtTimestamp(): String {
        val format = SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT")
        return "${format.format(Date())} GMT"
    }

    private fun View.setText(viewId: Int, stringResId: Int, argument: String) {
        findViewById<TextView>(viewId)?.text = context.getNamedFormatString(stringResId, argument)
    }
}
