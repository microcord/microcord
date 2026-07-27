package com.discord.widgets.captcha

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.discord.R
import com.discord.app.AppActivity
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.utilities.captcha.CaptchaErrorBody
import com.discord.utilities.captcha.CaptchaHelper
import com.discord.utilities.captcha.CaptchaService
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.view.ToastManager
import com.discord.views.ScreenTitleView
import com.google.android.material.button.MaterialButton

/**
 * Full-screen captcha step, used when a captcha interrupts a flow that owns the whole screen —
 * registration, login, OAuth authorization — rather than something that can be interrupted by a
 * bottom sheet.
 *
 * It runs as its own activity so the caller can wait on an activity result: [registerForResult]
 * hands back a [CaptchaHelper.CaptchaPayload], and [processErrorsForCaptcha] turns a REST failure
 * carrying `captcha_key` into a launch. That is the whole contract every auth screen uses.
 *
 * The solved token travels in the result intent and nowhere else: it is cleared out of
 * [CaptchaHelper]'s hand-off slots as soon as it is read, and is never logged or saved.
 *
 * Reference: `com.discord.widgets.captcha.WidgetCaptcha`.
 */
open class WidgetCaptcha : AppFragment(R.layout.widget_captcha) {

    private val toastManager = ToastManager()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val errorBody = captchaErrorBodyOf(mostRecentIntent)
        val siteKey = errorBody?.captchaSitekey
        val activity = requireActivity()
        CaptchaHelper.captchaRqtoken = errorBody?.captchaRqtoken

        val request = if (siteKey != null && errorBody?.captchaService != CaptchaService.RECAPTCHA) {
            CaptchaHelper.CaptchaRequest.HCaptcha(siteKey, activity, errorBody?.captchaRqdata)
        } else {
            CaptchaHelper.CaptchaRequest.ReCaptcha(activity)
        }

        view.findViewById<ScreenTitleView>(R.id.screen_title).setTitle(
            getString(
                if (request is CaptchaHelper.CaptchaRequest.HCaptcha) {
                    R.string.verify_by_hcaptcha
                } else {
                    R.string.verify_by_recaptcha
                },
            ),
        )
        trackTransition(TRANSITION_VIEWED)

        view.findViewById<MaterialButton>(R.id.captcha_verify).setOnClickListener {
            CaptchaHelper.tryShowCaptcha(request).appSubscribe(
                errorClass = javaClass,
                errorHandler = { error ->
                    trackTransition(TRANSITION_SUBMITTED)
                    trackTransition(TRANSITION_RESPONSE_ERROR, listOf("captcha"))
                    (error.cause as? CaptchaHelper.Failure)?.let {
                        toastManager.show(requireContext(), it.errorStringId)
                    }
                },
            ) { token ->
                CaptchaHelper.captchaToken = token
                appActivity?.let(::finishIfCaptchaTokenReceived)
            }
        }

        view.findViewById<View>(R.id.captcha_help).setOnClickListener {
            CaptchaHelper.showCaptchaHelpDialog(appActivity) { activity.finish() }
        }
    }

    override fun onResume() {
        super.onResume()
        // A challenge solved in a browser tab parks its token in CaptchaHelper; picking it up here
        // is how the out-of-app path completes.
        appActivity?.let(::finishIfCaptchaTokenReceived)
    }

    override fun onDestroyView() {
        toastManager.close()
        super.onDestroyView()
    }

    private fun finishIfCaptchaTokenReceived(activity: Activity) {
        val token = CaptchaHelper.captchaToken ?: return
        val rqtoken = CaptchaHelper.captchaRqtoken
        CaptchaHelper.captchaToken = null
        CaptchaHelper.captchaRqtoken = null
        trackTransition(TRANSITION_SUBMITTED)
        activity.setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(RESULT_EXTRA_TOKEN, token)
                .putExtra(RESULT_EXTRA_RQTOKEN, rqtoken),
        )
        activity.finish()
    }

    /**
     * Registration-funnel analytics hook.
     *
     * A no-op in the reference base class too — only `WidgetAuthCaptcha` overrides it.
     */
    protected open fun trackTransition(action: String, details: List<String>? = null) = Unit

    companion object {
        const val EXTRA_CAPTCHA_ERROR_BODY = "EXTRA_CAPTCHA_ERROR_BODY"
        private const val RESULT_EXTRA_TOKEN = "RESULT_EXTRA_TOKEN"
        private const val RESULT_EXTRA_RQTOKEN = "RESULT_EXTRA_RQTOKEN"

        private const val TRANSITION_VIEWED = "viewed"
        private const val TRANSITION_SUBMITTED = "submitted"
        private const val TRANSITION_RESPONSE_ERROR = "response_error"

        @Suppress("DEPRECATION")
        internal fun captchaErrorBodyOf(intent: Intent): CaptchaErrorBody? =
            intent.getSerializableExtra(EXTRA_CAPTCHA_ERROR_BODY) as? CaptchaErrorBody

        /**
         * Builds the launch intent.
         *
         * It targets the **base** [AppActivity] rather than `AppActivity.Main`: `Main` is
         * `singleTask`, which would deliver the screen to the existing task instead of starting the
         * new activity an activity result needs.
         */
        internal fun createLaunchIntent(
            context: Context,
            screen: Class<out AppFragment>,
            captchaErrorBody: CaptchaErrorBody?,
        ): Intent = Intent(context, AppActivity::class.java)
            .putExtra(AppScreenRouter.EXTRA_SCREEN, screen)
            .putExtra(EXTRA_CAPTCHA_ERROR_BODY, captchaErrorBody)

        fun launch(
            context: Context,
            launcher: ActivityResultLauncher<Intent>,
            captchaErrorBody: CaptchaErrorBody?,
        ) = launcher.launch(createLaunchIntent(context, WidgetCaptcha::class.java, captchaErrorBody))

        /**
         * Consumes a `captcha_key` failure: removes it from [unhandledErrors] so the screen does not
         * also render it as a field error, launches the challenge, and silences the error toast.
         *
         * Returns whether the failure was a captcha challenge.
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

        /** Registers the result contract; [callback] receives the solved payload. */
        fun registerForResult(
            fragment: AppFragment,
            callback: (CaptchaHelper.CaptchaPayload) -> Unit,
        ): ActivityResultLauncher<Intent> = fragment.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { activityResult ->
            if (activityResult.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = activityResult.data ?: return@registerForActivityResult
            val token = data.getStringExtra(RESULT_EXTRA_TOKEN) ?: return@registerForActivityResult
            callback(CaptchaHelper.CaptchaPayload(token, data.getStringExtra(RESULT_EXTRA_RQTOKEN)))
        }
    }
}

/**
 * Whether the API answered this request with a captcha challenge.
 *
 * Reference: `WidgetCaptchaKt.isCaptchaError`, which tests for the `captcha_key` entry in the
 * response's error map.
 */
fun RestApiError.isCaptchaError(): Boolean = CaptchaHelper.CAPTCHA_KEY in messages
