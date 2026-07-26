package com.discord.widgets.debugging

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.app.AppScreenRouter
import com.discord.i18n.setI18nText
import com.discord.utilities.view.text.LinkifiedTextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The recovery screen shown after an unhandled crash.
 *
 * It reports only what a bug report needs and what the user could read off their own device
 * anyway: app version, OS level, device model, where the crash came from and when. **The throwable
 * itself is logged, never rendered** — a stack trace on screen can carry tokens, ids and message
 * content, which the project's security contract forbids surfacing.
 */
class WidgetFatalCrash : AppFragment(R.layout.widget_fatal_crash) {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val testersInvite: LinkifiedTextView = view.findViewById(R.id.fatal_crash_testers_invite)
        val appVersion: TextView = view.findViewById(R.id.fatal_crash_app_version)
        val device: TextView = view.findViewById(R.id.fatal_crash_device)
        val osVersion: TextView = view.findViewById(R.id.fatal_crash_os_version)
        val source: TextView = view.findViewById(R.id.fatal_crash_source)
        val time: TextView = view.findViewById(R.id.fatal_crash_time)

        val extras: Bundle? = mostRecentIntent.extras

        testersInvite.setI18nText(R.string.crash_testers_invite, "testers" to TESTERS_INVITE_URL)
        source.setI18nText(
            R.string.crash_source,
            "source" to (
                extras?.getString(INTENT_EXTRA_CRASH_SOURCE, getString(R.string.status_unknown))
                    ?: getString(R.string.status_unknown)
                ),
        )
        time.setI18nText(
            R.string.crash_timestamp,
            "timestamp" to extras?.getString(INTENT_EXTRA_CRASH_TIME).orEmpty(),
        )
        appVersion.setI18nText(R.string.crash_app_version, "version" to APP_VERSION)
        osVersion.setI18nText(R.string.crash_device_version, "version" to Build.VERSION.SDK_INT.toString())
        device.setI18nText(R.string.crash_device, "device" to "${Build.MODEL} ${Build.PRODUCT}")
    }

    companion object {
        private const val INTENT_EXTRA_CRASH_SOURCE = "INTENT_EXTRA_CRASH_SOURCE"
        private const val INTENT_EXTRA_CRASH_TIME = "INTENT_EXTRA_CRASH_TIME"
        private const val TESTERS_INVITE_URL = "https://discord.gg/discord-testers"

        /**
         * The reference hardcodes this rather than reading `BuildConfig`, and the reconstruction
         * targets that exact build, so the literal is kept.
         */
        private const val APP_VERSION = "126.21 - Stable"

        fun launch(context: Context, throwable: Throwable, crashSource: String) {
            // The cause goes to the log sink only — see the class doc.
            AppLog.e(WidgetFatalCrash::class.java.simpleName, Throwable(crashSource, throwable))
            AppScreenRouter.launch(context, WidgetFatalCrash::class.java, createIntent(crashSource))
        }

        /** Timestamps are recorded in GMT so reports from different devices are comparable. */
        @SuppressLint("SimpleDateFormat")
        private fun createIntent(source: String): Intent {
            val formatter = SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            return Intent().putExtras(
                Bundle().apply {
                    putString(INTENT_EXTRA_CRASH_SOURCE, source)
                    putString(INTENT_EXTRA_CRASH_TIME, "${formatter.format(Date())} GMT")
                },
            )
        }
    }
}
