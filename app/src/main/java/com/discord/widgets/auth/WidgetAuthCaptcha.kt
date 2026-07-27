package com.discord.widgets.auth

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.utilities.captcha.CaptchaErrorBody
import com.discord.utilities.captcha.CaptchaHelper
import com.discord.utilities.error.RestApiError
import com.discord.widgets.captcha.WidgetCaptcha

/**
 * The captcha screen as it appears inside the registration/login funnel.
 *
 * Behaviourally identical to [WidgetCaptcha]; it exists so the funnel's step-transition analytics
 * are attributed to the "Captcha" step. Keeping it a distinct class also keeps the auth screens'
 * launcher separate from any other captcha in flight.
 *
 * Reference: `com.discord.widgets.auth.WidgetAuthCaptcha`, which reports through
 * `RegistrationFlowRepo.trackTransition("Captcha", action, details)`. `RegistrationFlowRepo` has
 * not been reconstructed, so the transition is recorded to the app log instead of the analytics
 * pipeline; the call shape is unchanged, only its sink.
 */
class WidgetAuthCaptcha : WidgetCaptcha() {

    override fun trackTransition(action: String, details: List<String>?) {
        AppLog.v("RegistrationFlow", "Captcha -> $action${details?.let { " $it" }.orEmpty()}")
    }

    companion object {
        fun launch(
            context: Context,
            launcher: ActivityResultLauncher<Intent>,
            captchaErrorBody: CaptchaErrorBody?,
        ) = launcher.launch(
            WidgetCaptcha.createLaunchIntent(context, WidgetAuthCaptcha::class.java, captchaErrorBody),
        )

        /**
         * Consumes a `captcha_key` failure and opens the funnel captcha screen.
         *
         * @return whether the failure was a captcha challenge.
         */
        fun processErrorsForCaptcha(
            context: Context,
            launcher: ActivityResultLauncher<Intent>,
            unhandledErrors: MutableCollection<String>,
            error: RestApiError,
        ): Boolean {
            if (!unhandledErrors.remove(CaptchaHelper.CAPTCHA_KEY)) return false
            launch(context, launcher, CaptchaErrorBody.createFromError(error))
            error.showErrorToasts = false
            return true
        }

        fun registerForResult(
            fragment: AppFragment,
            callback: (CaptchaHelper.CaptchaPayload) -> Unit,
        ): ActivityResultLauncher<Intent> = WidgetCaptcha.registerForResult(fragment, callback)
    }
}
