package com.discord.widgets.guilds.join

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppFragment
import com.discord.utilities.captcha.CaptchaErrorBody
import com.discord.utilities.captcha.CaptchaHelper
import com.discord.utilities.captcha.CaptchaService
import com.discord.utilities.rx.appSubscribe
import com.google.android.material.button.MaterialButton

class WidgetGuildJoinCaptchaBottomSheet : AppBottomSheet() {
    private val requestKey by lazy { requireArguments().getString(ARG_REQUEST_KEY).orEmpty() }
    @Suppress("DEPRECATION")
    private val errorBody by lazy {
        requireArguments().getSerializable(ARG_ERROR) as? CaptchaErrorBody ?: CaptchaErrorBody()
    }

    override fun getContentViewResId() = R.layout.widget_guild_join_captcha_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.guild_join_captcha_title)
            .setText(R.string.guild_join_captcha_header)
        view.findViewById<TextView>(R.id.guild_join_captcha_body)
            .setText(R.string.guild_join_captcha_description)
        view.findViewById<MaterialButton>(R.id.guild_join_captcha_button).apply {
            setText(R.string.confirm)
            setOnClickListener { openCaptcha() }
        }
    }

    override fun onResume() {
        super.onResume()
        finishIfSolved()
    }

    private fun openCaptcha() {
        val sitekey = errorBody.captchaSitekey
        CaptchaHelper.captchaRqtoken = errorBody.captchaRqtoken
        val request = if (sitekey != null && errorBody.captchaService == CaptchaService.HCAPTCHA) {
            CaptchaHelper.CaptchaRequest.HCaptcha(
                sitekey,
                requireActivity(),
                errorBody.captchaRqdata,
            )
        } else {
            CaptchaHelper.CaptchaRequest.ReCaptcha(requireActivity())
        }
        CaptchaHelper.tryShowCaptcha(request).appSubscribe(javaClass) { token ->
            CaptchaHelper.captchaToken = token
            finishIfSolved()
        }
    }

    private fun finishIfSolved() {
        val token = CaptchaHelper.captchaToken ?: return
        val rqtoken = CaptchaHelper.captchaRqtoken
        CaptchaHelper.captchaToken = null
        CaptchaHelper.captchaRqtoken = null
        setFragmentResult(
            requestKey,
            Bundle().apply {
                putString(RESULT_TOKEN, token)
                putString(RESULT_RQTOKEN, rqtoken)
            },
        )
        dismiss()
    }

    companion object {
        private const val ARG_REQUEST_KEY = "INTENT_EXTRA_REQUEST_CODE"
        private const val ARG_ERROR = "INTENT_EXTRA_CAPTCHA_ERROR_BODY"
        private const val RESULT_TOKEN = "INTENT_EXTRA_CAPTCHA_TOKEN"
        private const val RESULT_RQTOKEN = "INTENT_EXTRA_CAPTCHA_RQTOKEN"

        @JvmStatic
        fun show(fragmentManager: FragmentManager, requestKey: String, error: CaptchaErrorBody?) {
            WidgetGuildJoinCaptchaBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_REQUEST_KEY, requestKey)
                    putSerializable(ARG_ERROR, error)
                }
            }.show(fragmentManager, WidgetGuildJoinCaptchaBottomSheet::class.java.name)
        }

        fun registerForResult(
            fragment: AppFragment,
            requestKey: String,
            onCaptchaPayloadReceived: (AppFragment, CaptchaHelper.CaptchaPayload) -> Unit,
        ) {
            fragment.setFragmentResultListener(requestKey) { key, result ->
                val token = result.getString(RESULT_TOKEN).orEmpty()
                if (key == requestKey && token.isNotEmpty()) {
                    onCaptchaPayloadReceived(
                        fragment,
                        CaptchaHelper.CaptchaPayload(token, result.getString(RESULT_RQTOKEN)),
                    )
                }
            }
        }

        fun enqueue(
            requestKey: String,
            onCaptchaPayloadReceived: (AppFragment, CaptchaHelper.CaptchaPayload) -> Unit,
            error: CaptchaErrorBody?,
        ) {
            com.discord.widgets.captcha.WidgetCaptchaBottomSheet.enqueue(
                requestKey,
                onCaptchaPayloadReceived,
                error = error,
            )
        }
    }
}
