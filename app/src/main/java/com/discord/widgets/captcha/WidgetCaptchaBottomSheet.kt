package com.discord.widgets.captcha

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.utilities.captcha.CaptchaErrorBody
import com.discord.utilities.captcha.CaptchaHelper
import com.discord.utilities.captcha.CaptchaService
import com.discord.utilities.captcha.CaptchaSolution
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.view.ToastManager
import com.google.android.material.button.MaterialButton
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

private const val ARG_CHALLENGE = "INTENT_EXTRA_CAPTCHA_ERROR_BODY"
private const val ARG_CHALLENGE_ID = "INTENT_EXTRA_CHALLENGE_ID"

/**
 * "You need to solve a captcha" prompt. The challenge itself is [HCaptchaWebDialog].
 *
 * A request is parked inside `CaptchaInterceptor` waiting on this sheet, so every path out of here
 * reports exactly once — a solution, or a decline. Outcomes go through a process-local map keyed by
 * challenge id so they survive the fragment being recreated.
 *
 * A challenge the user *closed* leaves the sheet up so they can retry; only dismissing the sheet
 * declines.
 */
class WidgetCaptchaBottomSheet : AppBottomSheet() {

    private val toastManager = ToastManager()

    private val challenge: CaptchaErrorBody? by lazy {
        @Suppress("DEPRECATION")
        argumentsOrDefault.getSerializable(ARG_CHALLENGE) as? CaptchaErrorBody
    }

    private val challengeId: String by lazy { argumentsOrDefault.getString(ARG_CHALLENGE_ID, "") }

    private var outcomeDelivered: Boolean = false

    override fun getContentViewResId(): Int = R.layout.widget_captcha_bottom_sheet

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        live[challengeId] = WeakReference(this)
    }

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

    override fun onDestroy() {
        if (live[challengeId]?.get() === this) live.remove(challengeId)
        super.onDestroy()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        deliver(null)
    }

    private fun openCaptcha() {
        val activity = activity ?: return
        val challenge = challenge ?: return deliver(null)
        val siteKey = challenge.effectiveSiteKey

        val request = if (siteKey != null && challenge.captchaService == CaptchaService.HCAPTCHA) {
            CaptchaHelper.CaptchaRequest.HCaptcha(
                siteKey = siteKey,
                activity = activity,
                rqdata = challenge.captchaRqdata,
                invisible = challenge.shouldServeInvisible,
            )
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
            deliver(CaptchaSolution.of(challenge, token))
            dismiss()
        }
    }

    private fun deliver(solution: CaptchaSolution?) {
        if (outcomeDelivered) return
        outcomeDelivered = true
        pending.remove(challengeId)?.invoke(solution)
    }

    companion object {
        private val pending = ConcurrentHashMap<String, (CaptchaSolution?) -> Unit>()
        private val live = ConcurrentHashMap<String, WeakReference<WidgetCaptchaBottomSheet>>()

        /** [onOutcome] runs exactly once, on the main thread; null means the user declined. */
        @MainThread
        fun show(
            fragmentManager: FragmentManager,
            challengeId: String,
            challenge: CaptchaErrorBody,
            onOutcome: (CaptchaSolution?) -> Unit,
        ) {
            pending[challengeId] = onOutcome
            WidgetCaptchaBottomSheet().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_CHALLENGE, challenge)
                    putString(ARG_CHALLENGE_ID, challengeId)
                }
            }.show(fragmentManager, WidgetCaptchaBottomSheet::class.java.name)
        }

        /** Drops a challenge nobody is waiting for any more, closing the prompt if it is still up. */
        @MainThread
        fun cancel(challengeId: String) {
            pending.remove(challengeId)
            live.remove(challengeId)?.get()?.dismissAllowingStateLoss()
        }
    }
}
