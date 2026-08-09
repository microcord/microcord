package com.discord.widgets.safetyhub

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.safetyhub.AppealIngestionSignal
import com.discord.app.AppBottomSheet
import com.discord.app.AppToast
import com.discord.i18n.I18nSpans
import com.discord.stores.StoreStream
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.uri.UriHandler
import com.google.android.material.button.MaterialButton

/**
 * Collects the reason and free text behind a review request.
 *
 * The reference splits this across a speed bump, a signal picker, a free-text sheet and a
 * confirmation; the request they build is the same one this sends in a single pass.
 */
class WidgetSafetyHubAppeal : AppBottomSheet(shouldAvoidKeyboard = true) {

    override fun getContentViewResId(): Int = R.layout.widget_safety_hub_appeal

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val classificationId = argumentsOrDefault.getString(ARG_CLASSIFICATION_ID) ?: return dismiss()
        val submit = view.findViewById<MaterialButton>(R.id.appeal_submit)
        val signals = view.findViewById<RadioGroup>(R.id.appeal_signals)

        view.findViewById<TextView>(R.id.appeal_disclaimer).text =
            I18nSpans.render(getString(R.string.safety_hub_appeal_collect_signal_disclaimer))

        if (argumentsOrDefault.getBoolean(ARG_IS_SPAM)) {
            renderSpam(view)
            return
        }

        signals.setOnCheckedChangeListener { _, _ -> submit.isEnabled = true }
        submit.setOnClickListener {
            val signal = SIGNALS[signals.checkedRadioButtonId] ?: return@setOnClickListener
            val userInput = view.findViewById<EditText>(R.id.appeal_input).text.toString()
            send(classificationId, signal, userInput, submit)
        }
    }

    /** A spam violation is not appealed in app; the reference sends it to a support ticket. */
    private fun renderSpam(view: View) {
        view.findViewById<TextView>(R.id.appeal_header).setText(R.string.safety_hub_appeal_modal_spam_header)
        view.findViewById<View>(R.id.appeal_subheader).isVisible = false
        view.findViewById<View>(R.id.appeal_signals).isVisible = false
        view.findViewById<View>(R.id.appeal_free_text_subheader).isVisible = false
        view.findViewById<View>(R.id.appeal_input).isVisible = false
        view.findViewById<View>(R.id.appeal_footer).isVisible = false
        view.findViewById<MaterialButton>(R.id.appeal_submit).apply {
            isEnabled = true
            setText(R.string.safety_hub_appeal_modal_spam_link)
            setOnClickListener {
                UriHandler.handle(context, SPAM_LINK)
                dismiss()
            }
        }
    }

    private fun send(
        classificationId: String,
        signal: AppealIngestionSignal,
        userInput: String,
        submit: MaterialButton,
    ) {
        submit.isEnabled = false
        StoreStream.getSafetyHub()
            .requestReview(classificationId, signal, userInput)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                WidgetSafetyHubAppeal::class.java,
                errorHandler = { error ->
                    error.showErrorToasts = false
                    submit.isEnabled = true
                    AppToast.show(requireContext(), getString(errorMessage(error)))
                },
            ) {
                AppToast.show(requireContext(), getString(R.string.safety_hub_appeal_request_sent_subheader))
                dismiss()
            }
    }

    private fun errorMessage(error: RestApiError): Int =
        if (error.code == CODE_APPEAL_REQUEST_DEFLECTION) {
            R.string.safety_hub_appeal_confirm_submission_deflection_error
        } else {
            R.string.safety_hub_appeal_confirm_submission_error
        }

    companion object {
        private const val ARG_CLASSIFICATION_ID = "classification_id"
        private const val ARG_IS_SPAM = "is_spam"
        private const val CODE_APPEAL_REQUEST_DEFLECTION = 522001
        private const val SPAM_LINK =
            "https://support.discord.com/hc/requests/new?ticket_form_id=360000029731" +
                "&tf_360055270593=__dc.ticket_form-tnsv1_appeals_other_questions__"

        private val SIGNALS = mapOf(
            R.id.appeal_signal_didnt_violate to AppealIngestionSignal.DIDNT_VIOLATE_POLICY,
            R.id.appeal_signal_too_strict to AppealIngestionSignal.TOO_STRICT_UNFAIR,
            R.id.appeal_signal_dont_agree to AppealIngestionSignal.DONT_AGREE_PENALTY,
            R.id.appeal_signal_something_else to AppealIngestionSignal.SOMETHING_ELSE,
        )

        fun show(fragmentManager: FragmentManager, classificationId: String, isSpam: Boolean) {
            WidgetSafetyHubAppeal()
                .apply {
                    arguments = Bundle().apply {
                        putString(ARG_CLASSIFICATION_ID, classificationId)
                        putBoolean(ARG_IS_SPAM, isSpam)
                    }
                }
                .show(fragmentManager, WidgetSafetyHubAppeal::class.java.name)
        }
    }
}
