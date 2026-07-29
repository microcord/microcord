package com.discord.widgets.captcha

import android.app.Activity
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppFragment
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import com.discord.utilities.captcha.CaptchaErrorBody
import com.discord.utilities.captcha.CaptchaHelper
import com.discord.utilities.captcha.CaptchaService
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.view.ToastManager
import com.discord.widgets.share.WidgetIncomingShare
import com.discord.widgets.tabs.WidgetTabsHost
import com.google.android.material.button.MaterialButton
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

private const val ARG_CAPTCHA_ERROR_BODY = "INTENT_EXTRA_CAPTCHA_ERROR_BODY"

/**
 * "You need to solve a captcha" prompt: an explanation and one button that opens the challenge.
 *
 * This sheet is *not* the challenge — [HCaptchaWebDialog] is. Keeping them separate is what the
 * reference does and it matters: the sheet is Discord chrome that can be queued behind other
 * notices, while the challenge is a live provider-hosted interaction.
 *
 * Results travel back as a **fragment result** ([setFragmentResult]) keyed by the caller's request
 * key, not through a static callback, so a screen that was recreated while the sheet was up still
 * receives the payload. [Companion.registerForResult] installs the listener;
 * [Companion.enqueue] queues the sheet through [StoreNotices] so it waits for a screen that can
 * actually resolve it instead of being dropped.
 *
 * **Dismissal is a real outcome.** If the sheet closes without a solved captcha,
 * [onCaptchaAttemptFailed] fires. Whatever the captcha gated — a queued message send, an upload —
 * is then free to give up rather than wait forever. [Companion.onAbandoned] is the process-wide
 * form of the same signal for callers that used the legacy [show] overload.
 *
 * Captcha tokens are single-use credentials: they are passed straight to the waiting caller through
 * a result bundle, cleared from [CaptchaHelper] as soon as they are read, and never logged or
 * written into saved instance state.
 *
 * Reference: `com.discord.widgets.captcha.WidgetCaptchaBottomSheet`.
 */
class WidgetCaptchaBottomSheet : AppBottomSheet() {

    private val toastManager = ToastManager()

    private val captchaErrorBody: CaptchaErrorBody? by lazy {
        @Suppress("DEPRECATION")
        argumentsOrDefault.getSerializable(ARG_CAPTCHA_ERROR_BODY) as? CaptchaErrorBody
    }

    private val requestKey: String by lazy { argumentsOrDefault.getString(ARG_REQUEST_KEY, "") }

    /** Set once a token has been delivered, so [onDismiss] does not report a failure over a success. */
    private var captchaPassed: Boolean = false

    /** Invoked from [onDismiss] when the sheet closed without a solved captcha. */
    var onCaptchaAttemptFailed: (() -> Unit)? = null

    override fun getContentViewResId(): Int = R.layout.widget_captcha_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.captcha_title).setText(R.string.generic_captcha_header)
        view.findViewById<TextView>(R.id.captcha_body).setText(R.string.generic_captcha_description)
        view.findViewById<MaterialButton>(R.id.captcha_button).apply {
            setText(R.string.confirm)
            setOnClickListener { openCaptcha() }
        }
    }

    override fun onDestroyView() {
        toastManager.close()
        super.onDestroyView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!captchaPassed) {
            onCaptchaAttemptFailed?.invoke()
            onAbandoned?.invoke(requestKey)
            legacyCallbacks.remove(requestKey)
        }
    }

    private fun openCaptcha() {
        val errorBody = captchaErrorBody
        val siteKey = errorBody?.captchaSitekey
        val activity = activity ?: return
        // Parked for the retry that follows; cleared again the moment it is read back.
        CaptchaHelper.captchaRqtoken = errorBody?.captchaRqtoken

        // hCaptcha is the normal path; reCAPTCHA is only chosen when the server names it, or when
        // no site key came back at all and there is nothing to run hCaptcha against.
        val request = if (siteKey != null && errorBody?.captchaService != CaptchaService.RECAPTCHA) {
            CaptchaHelper.CaptchaRequest.HCaptcha(siteKey, activity, errorBody?.captchaRqdata)
        } else {
            CaptchaHelper.CaptchaRequest.ReCaptcha(activity)
        }

        CaptchaHelper.tryShowCaptcha(request).appSubscribe(
            errorClass = WidgetCaptchaBottomSheet::class.java,
            errorHandler = { error ->
                (error.cause as? CaptchaHelper.Failure)?.let {
                    toastManager.show(requireContext(), it.errorStringId)
                }
            },
        ) { token ->
            CaptchaHelper.captchaToken = token
            captchaPassed = true
            finishIfCaptchaTokenReceived(activity)
        }
    }

    /**
     * Publishes the solved token, if there is one, and closes.
     *
     * Reads and immediately clears [CaptchaHelper]'s hand-off slots, so the pair cannot be replayed
     * by a second sheet or leak into an unrelated request.
     */
    private fun finishIfCaptchaTokenReceived(activity: Activity) {
        val token = CaptchaHelper.captchaToken ?: return
        val rqtoken = CaptchaHelper.captchaRqtoken
        CaptchaHelper.captchaToken = null
        CaptchaHelper.captchaRqtoken = null
        if (activity.isFinishing) return

        setFragmentResult(
            requestKey,
            Bundle().apply {
                putString(RESULT_EXTRA_CAPTCHA_TOKEN, token)
                putString(RESULT_EXTRA_CAPTCHA_RQTOKEN, rqtoken)
            },
        )
        legacyCallbacks.remove(requestKey)?.invoke(token, rqtoken)
        onSolved?.invoke(requestKey, token, rqtoken)
        dismiss()
    }

    companion object {
        private const val ARG_REQUEST_KEY = "INTENT_EXTRA_REQUEST_CODE"
        private const val RESULT_EXTRA_CAPTCHA_TOKEN = "INTENT_EXTRA_CAPTCHA_TOKEN"
        private const val RESULT_EXTRA_CAPTCHA_RQTOKEN = "INTENT_EXTRA_CAPTCHA_RQTOKEN"
        private const val NOTICE_NAME = "captcha notice"

        /**
         * Screens this sheet may appear over, and the screens searched for a result listener when
         * the notice fires. Matches the reference's static list exactly.
         */
        private val resolvableFragments: List<KClass<out Fragment>> =
            listOf(WidgetTabsHost::class, WidgetIncomingShare::class)

        /**
         * Process-wide "a captcha was solved" hook, kept for callers that predate the fragment
         * result contract. `StoreStream` installs it to retry a captcha-blocked message send.
         */
        @JvmField
        var onSolved: ((requestId: String, token: String, rqtoken: String?) -> Unit)? = null

        /**
         * Process-wide "the user walked away from the challenge" hook, the counterpart to
         * [onSolved]. Without it a captcha-blocked send stays pending forever.
         */
        @JvmField
        var onAbandoned: ((requestId: String) -> Unit)? = null

        private val legacyCallbacks = ConcurrentHashMap<String, (String, String?) -> Unit>()

        /**
         * Queues the sheet through [StoreNotices] rather than showing it immediately.
         *
         * The store decides *when*: the sheet only appears once the user is on a screen listed in
         * [resolvableFragments], which is also where the result listener is installed. That is why
         * a captcha demanded while the user is deep in settings is not silently lost.
         */
        @JvmOverloads
        fun enqueue(
            requestKey: String,
            onCaptchaPayloadReceived: (AppFragment, CaptchaHelper.CaptchaPayload) -> Unit,
            onCaptchaAttemptFailed: (() -> Unit)? = null,
            error: CaptchaErrorBody?,
        ) {
            StoreStream.getNotices().requestToShow(
                StoreNotices.Notice(
                    name = NOTICE_NAME,
                    validScreens = null,
                    show = { activity ->
                        show(activity.supportFragmentManager, requestKey, error, onCaptchaAttemptFailed)
                        StoreStream.getNotices().markSeen(NOTICE_NAME)
                        activity.supportFragmentManager.fragments
                            .filterIsInstance<AppFragment>()
                            .firstOrNull { fragment -> resolvableFragments.any { it.isInstance(fragment) } }
                            ?.let { registerForResult(it, requestKey, onCaptchaPayloadReceived) }
                        true
                    },
                ),
            )
        }

        /**
         * Installs the fragment-result listener that delivers the solved payload to [fragment].
         *
         * Survives the fragment being destroyed and recreated while the sheet is up, which the
         * static-callback approach it replaces did not.
         */
        fun registerForResult(
            fragment: AppFragment,
            requestKey: String,
            onCaptchaPayloadReceived: (AppFragment, CaptchaHelper.CaptchaPayload) -> Unit,
        ) {
            fragment.setFragmentResultListener(requestKey) { resultRequestKey, bundle ->
                if (resultRequestKey != requestKey) return@setFragmentResultListener
                val token = bundle.getString(RESULT_EXTRA_CAPTCHA_TOKEN, "")
                val rqtoken = bundle.getString(RESULT_EXTRA_CAPTCHA_RQTOKEN, "")
                if (token.isNotEmpty()) {
                    onCaptchaPayloadReceived(fragment, CaptchaHelper.CaptchaPayload(token, rqtoken))
                }
            }
        }

        @JvmOverloads
        fun show(
            fragmentManager: FragmentManager,
            requestKey: String,
            error: CaptchaErrorBody?,
            onCaptchaAttemptFailed: (() -> Unit)? = null,
        ) {
            WidgetCaptchaBottomSheet().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_CAPTCHA_ERROR_BODY, error)
                    putString(ARG_REQUEST_KEY, requestKey)
                }
                this.onCaptchaAttemptFailed = onCaptchaAttemptFailed
            }.show(fragmentManager, WidgetCaptchaBottomSheet::class.java.name)
        }

        /**
         * Convenience for callers that only hold a site key and an rqtoken — the message send and
         * upload paths, which get theirs from [com.discord.utilities.messagesend.MessageResult].
         */
        @JvmOverloads
        fun show(
            fragmentManager: FragmentManager,
            requestId: String,
            siteKey: String,
            rqtoken: String?,
            solved: ((String, String?) -> Unit)? = null,
        ) {
            if (solved != null) legacyCallbacks[requestId] = solved
            show(
                fragmentManager,
                requestId,
                CaptchaErrorBody(captchaSitekey = siteKey, captchaRqtoken = rqtoken),
            )
        }

        /** Builds the sheet's argument body from a classified REST failure. */
        fun errorBodyOf(error: RestApiError): CaptchaErrorBody? = CaptchaErrorBody.createFromError(error)
    }
}
