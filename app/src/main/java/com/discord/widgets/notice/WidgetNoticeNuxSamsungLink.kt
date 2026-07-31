package com.discord.widgets.notice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.View
import com.discord.R
import com.discord.app.AppDialog
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.takeSingleUntilTimeout
import com.discord.utilities.rx.ui
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.user.UserUtils.getAgeMs
import com.google.android.material.button.MaterialButton
import rx.Observable

/**
 * Offers to link the account to Samsung's Game Tools / Game Home integration.
 *
 * Only shown on Samsung devices from Android 9 that actually have one of the two game apps
 * installed ([isEligible]). Linking is an ordinary OAuth2 authorize flow against Discord's own
 * Samsung application, opened in the browser.
 *
 * The dialog re-checks the account's granted authorizations on bind and closes itself if the link
 * already exists, because the notice queue can hold the prompt from before a link made on desktop.
 */
class WidgetNoticeNuxSamsungLink : AppDialog(R.layout.widget_notice_nux_samsung_link) {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        StoreStream.getNotices().markDialogSeen(NOTICE_NAME)

        RestAPI.api.getOAuthTokens()
            .restSubscribeOn()
            .computationLatest()
            .takeSingleUntilTimeout(emitError = false)
            .ui(this)
            .appSubscribe(WidgetNoticeNuxSamsungLink::class.java) { authorizedApps ->
                val alreadyLinked = authorizedApps.any { it.application.id.toString() == SAMSUNG_APPLICATION_ID }
                if (alreadyLinked) dismiss()
            }

        view.findViewById<MaterialButton>(R.id.enable_button).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, samsungAuthorizeUri()))
            dismiss()
        }
        view.findViewById<MaterialButton>(R.id.cancel_button).setOnClickListener { dismiss() }
    }

    companion object {
        /** Notice-queue identity; must match the name the store dequeues. */
        const val NOTICE_NAME = "NUX/SamsungLink"

        /** Discord's own first-party application used by the Samsung integration. */
        private const val SAMSUNG_APPLICATION_ID = "591317049637339146"

        private const val OAUTH_AUTHORIZE_URI = "discord://action/oauth2/authorize"
        private const val SAMSUNG_SCOPES = "identify activities.read activities.write"
        private const val EXISTING_ACCOUNT_AGE_MS = 2_419_200_000L

        private val SAMSUNG_GAME_PACKAGES = listOf(
            "com.samsung.android.game.gametools",
            "com.samsung.android.game.gamehome",
        )

        private fun samsungAuthorizeUri(): Uri =
            Uri.parse(OAUTH_AUTHORIZE_URI).buildUpon()
                .appendQueryParameter("client_id", SAMSUNG_APPLICATION_ID)
                .appendQueryParameter("prompt", "consent")
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", SAMSUNG_SCOPES)
                .build()

        /**
         * Whether this device can use the Samsung integration at all.
         *
         * The caller that owns the notice queue combines this with "is authenticated" before
         * enqueuing the prompt.
         */
        @JvmStatic
        fun isEligible(context: Context): Boolean {
            val isSupportedSamsung = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            return isSupportedSamsung && SAMSUNG_GAME_PACKAGES.any { context.isPackageInstalled(it) }
        }

        @JvmStatic
        @JvmOverloads
        fun enqueue(context: Context, clock: Clock = ClockFactory.get()) {
            if (!isEligible(context)) return
            Observable.combineLatest(
                StoreStream.getAuthentication().observeIsAuthed(),
                StoreStream.getUsers().observeMe(emitEmpty = true),
            ) { isAuthed, me -> isAuthed to me }
                .takeSingleUntilTimeout()
                .computationLatest()
                .appSubscribe(WidgetNoticeNuxSamsungLink::class.java) { (isAuthed, me) ->
                    if (!isAuthed) StoreStream.getNotices().markSeen(NOTICE_NAME)
                    if (isAccountOldEnough(me.getAgeMs(clock))) internalEnqueue()
                }
        }

        internal fun isAccountOldEnough(ageMs: Long): Boolean = ageMs > EXISTING_ACCOUNT_AGE_MS

        private fun internalEnqueue() {
            StoreStream.getNotices().requestToShow(
                StoreNotices.Notice(
                    name = NOTICE_NAME,
                    priority = StoreNotices.PRIORITY_HIGH,
                    persist = true,
                    validScreens = null,
                    delayPeriodMs = 0L,
                    sinceLastPeriodMs = Long.MAX_VALUE,
                    show = { activity ->
                        WidgetNoticeNuxSamsungLink().show(
                            activity.supportFragmentManager,
                            WidgetNoticeNuxSamsungLink::class.java.name,
                        )
                        true
                    },
                ),
            )
        }

        @Suppress("DEPRECATION")
        private fun Context.isPackageInstalled(packageName: String): Boolean = try {
            packageManager.getPackageInfo(packageName, 0) != null
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
