package com.discord.utilities.captcha

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.discord.R
import com.discord.widgets.captcha.WidgetCaptchaBottomSheet
import java.util.UUID
import rx.Emitter
import rx.Observable

/**
 * Runs a captcha challenge and hands the solved token back to whatever REST call demanded it.
 *
 * Reconstruction of the reference `com.discord.utilities.captcha.CaptchaHelper`. The reference
 * supports two challenge kinds and the same split is preserved here:
 *
 * - [CaptchaRequest.HCaptcha] — the normal path. The reference drives the hCaptcha Android SDK's
 *   WebView dialog with `siteKey`, the user's locale and an optional `rqdata` blob. This project has
 *   no hCaptcha SDK dependency, so the equivalent WebView challenge already recovered as
 *   `WidgetCaptchaBottomSheet` is driven instead. Observable semantics are unchanged: emit the
 *   token, then complete.
 * - [CaptchaRequest.ReCaptcha] — the reference fallback, which requires SafetyNet from Google Play
 *   Services. That dependency is not installed in this project, so the request resolves to the same
 *   [Failure] the reference produces on a device without usable Play Services rather than silently
 *   doing nothing.
 *
 * [captchaToken] and [captchaRqtoken] are the reference's process-wide hand-off slots: a flow that
 * cannot thread the token through its own call chain parks it here for the immediately following
 * retry. Neither is ever logged or rendered.
 */
object CaptchaHelper {

    /** Key the API uses in `errors` to signal "solve a captcha and retry". */
    const val CAPTCHA_KEY = "captcha_key"

    private const val FAILED_CAPTCHA_EXPIRED = "expired"
    private const val FAILED_DEVICE_UNSUPPORTED = "unsupported_device"
    private const val FAILED_MISSING_DEPS = "missing_dependencies"

    private const val CAPTCHA_HELP_URL = "https://support.discord.com/hc/en-us/articles/360045138471"

    /** Most recently solved captcha token, parked for the retry that follows. Never logged. */
    @Volatile
    @JvmStatic
    var captchaToken: String? = null

    /** Request token that pairs with [captchaToken]. Never logged. */
    @Volatile
    @JvmStatic
    var captchaRqtoken: String? = null

    /**
     * The `captcha_key` / `captcha_rqtoken` pair attached to a retried request body.
     *
     * `toString` is overridden because these values are credentials for a single retry and the
     * project forbids them from reaching logs or crash reports.
     */
    data class CaptchaPayload(
        val captchaKey: String,
        val captchaRqtoken: String? = null,
    ) {
        override fun toString() = "CaptchaPayload(captchaKey=<redacted>, captchaRqtoken=<redacted>)"
    }

    /** Reference `CaptchaHelper.Failure`: a user-facing string plus a machine-readable reason. */
    class Failure(val errorStringId: Int, val reasonCode: String) : Throwable(reasonCode) {
        override fun toString() = "CaptchaHelper.Failure(errorStringId=$errorStringId, reasonCode=$reasonCode)"
    }

    sealed class CaptchaRequest {
        abstract val activity: Activity

        data class HCaptcha(
            val siteKey: String,
            override val activity: Activity,
            val rqdata: String? = null,
        ) : CaptchaRequest()

        data class ReCaptcha(override val activity: Activity) : CaptchaRequest()
    }

    /**
     * Shows the challenge and emits the solved token, then completes.
     *
     * Emits [Failure] through `onError` when the challenge itself fails. Matching the reference, a
     * challenge the user simply closed completes without emitting a token — callers must treat
     * "completed with no value" as "user declined", not as success.
     *
     * Known gap: `WidgetCaptchaBottomSheet` exposes no dismissal callback, so the "user closed the
     * challenge" completion currently only happens when the subscriber unsubscribes. Restoring the
     * reference's `CHALLENGE_CLOSED` completion needs a dismissal hook on that sheet, which is owned
     * by the widget layer.
     */
    fun tryShowCaptcha(captchaRequest: CaptchaRequest): Observable<String> = Observable.create(
        { emitter ->
            when (captchaRequest) {
                is CaptchaRequest.HCaptcha -> showHCaptcha(captchaRequest, emitter)
                is CaptchaRequest.ReCaptcha -> emitter.onError(
                    Failure(R.string.captcha_failed_play_services, FAILED_MISSING_DEPS),
                )
            }
        },
        Emitter.BackpressureMode.BUFFER,
    )

    private fun showHCaptcha(request: CaptchaRequest.HCaptcha, emitter: Emitter<String>) {
        val activity = request.activity as? FragmentActivity
        if (activity == null || activity.isFinishing) {
            emitter.onError(Failure(R.string.captcha_failed_unsupported, FAILED_DEVICE_UNSUPPORTED))
            return
        }
        val challengeId = UUID.randomUUID().toString()
        activity.runOnUiThread {
            runCatching {
                WidgetCaptchaBottomSheet.show(
                    activity.supportFragmentManager,
                    challengeId,
                    request.siteKey,
                    request.rqdata,
                ) { token, _ ->
                    if (token.isBlank()) {
                        emitter.onError(Failure(R.string.captcha_failed, FAILED_CAPTCHA_EXPIRED))
                    } else {
                        emitter.onNext(token)
                        emitter.onCompleted()
                    }
                }
            }.onFailure {
                emitter.onError(Failure(R.string.captcha_failed, FAILED_CAPTCHA_EXPIRED))
            }
        }
    }

    /**
     * Reference `CaptchaHelper.showCaptchaHelpDialog`: offers to retry the challenge in a browser
     * when the in-app WebView cannot complete it.
     *
     * The reference builds this with `WidgetNoticeDialog`, which is not reconstructed yet, so a
     * plain [AlertDialog] with the same recovered strings and button order is used.
     */
    @JvmOverloads
    fun showCaptchaHelpDialog(activity: Activity?, onOpenBrowser: (() -> Unit)? = null) {
        if (activity == null || activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.captcha_problems)
            .setMessage(R.string.captcha_problems_info)
            .setPositiveButton(R.string.captcha_open_browser) { dialog, _ ->
                dialog.dismiss()
                if (onOpenBrowser != null) onOpenBrowser() else openCaptchaHelpInBrowser(activity)
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun openCaptchaHelpInBrowser(activity: Activity) {
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CAPTCHA_HELP_URL)))
        }
    }
}
