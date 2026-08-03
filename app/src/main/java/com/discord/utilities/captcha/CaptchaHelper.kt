package com.discord.utilities.captcha

import android.app.Activity
import androidx.fragment.app.FragmentActivity
import com.discord.R
import com.discord.widgets.captcha.HCaptchaWebDialog
import java.util.Locale
import java.util.UUID
import rx.Emitter
import rx.Observable

/**
 * Renders a captcha challenge and emits the provider's token.
 *
 * Deciding a challenge is needed and retrying the request is `CaptchaInterceptor`'s job.
 *
 * hCaptcha is the only provider this app can render; there is no hCaptcha SDK artifact, but the
 * SDK's own recovered page and layout are in the app, so [HCaptchaWebDialog] hosts them and
 * implements the same bridge. reCAPTCHA needs SafetyNet, which is not installed, so it resolves to
 * the same [Failure] the reference produces without Play Services.
 */
object CaptchaHelper {

    private const val FAILED_CAPTCHA_EXPIRED = "expired"
    private const val FAILED_DEVICE_UNSUPPORTED = "unsupported_device"
    private const val FAILED_MISSING_DEPS = "missing_dependencies"
    private const val FAILED_NETWORK = "network"

    class Failure(val errorStringId: Int, val reasonCode: String) : Throwable(reasonCode) {
        override fun toString() = "CaptchaHelper.Failure(errorStringId=$errorStringId, reasonCode=$reasonCode)"
    }

    sealed class CaptchaRequest {
        abstract val activity: Activity

        data class HCaptcha(
            val siteKey: String,
            override val activity: Activity,
            val rqdata: String? = null,
            val invisible: Boolean = false,
        ) : CaptchaRequest()

        data class ReCaptcha(override val activity: Activity) : CaptchaRequest()
    }

    /**
     * Emits the solved token then completes. A challenge the user *closed* completes with no value,
     * so callers must treat that as declined rather than success.
     */
    fun tryShowCaptcha(captchaRequest: CaptchaRequest): Observable<String> = Observable.create<String>(
        { emitter: Emitter<String> ->
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
        emitter.setCancellation { HCaptchaWebDialog.cancel(challengeId) }
        activity.runOnUiThread {
            runCatching {
                HCaptchaWebDialog.show(
                    fragmentManager = activity.supportFragmentManager,
                    challengeId = challengeId,
                    siteKey = request.siteKey,
                    rqdata = request.rqdata,
                    locale = Locale.getDefault().language,
                    invisible = request.invisible,
                ) { outcome ->
                    when (outcome) {
                        is HCaptchaWebDialog.Outcome.Solved -> {
                            emitter.onNext(outcome.token)
                            emitter.onCompleted()
                        }
                        is HCaptchaWebDialog.Outcome.Failed -> when {
                            outcome.isUserClosed -> emitter.onCompleted()
                            outcome.errorCode == HCaptchaWebDialog.NETWORK_ERROR ->
                                emitter.onError(Failure(R.string.captcha_failed, FAILED_NETWORK))
                            else -> emitter.onError(Failure(R.string.captcha_failed, FAILED_CAPTCHA_EXPIRED))
                        }
                    }
                }
            }.onFailure {
                emitter.onError(Failure(R.string.captcha_failed, FAILED_CAPTCHA_EXPIRED))
            }
        }
    }
}
