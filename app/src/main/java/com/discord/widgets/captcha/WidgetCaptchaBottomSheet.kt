package com.discord.widgets.captcha

import android.app.Dialog
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import org.json.JSONObject

class WidgetCaptchaBottomSheet : DialogFragment() {
    private var webView: WebView? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val siteKey = requireNotNull(requireArguments().getString(ARG_SITE_KEY))
        val view = WebView(requireContext()).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "Android")
            loadDataWithBaseURL(
                "https://hcaptcha.com/",
                html(JSONObject.quote(siteKey)),
                "text/html",
                "UTF-8",
                null,
            )
        }
        webView = view
        return Dialog(requireContext()).apply {
            setTitle(com.discord.R.string.verify_by_hcaptcha)
            setContentView(view)
        }
    }

    override fun onDestroyView() {
        webView?.apply {
            removeJavascriptInterface("Android")
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroyView()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onToken(token: String) {
            if (token.isBlank()) return
            val requestId = requireArguments().getString(ARG_REQUEST_ID) ?: return
            val rqtoken = requireArguments().getString(ARG_RQTOKEN)
            activity?.runOnUiThread {
                callbacks.remove(requestId)?.invoke(token, rqtoken) ?: onSolved?.invoke(requestId, token, rqtoken)
                dismissAllowingStateLoss()
            }
        }
    }

    companion object {
        @JvmField var onSolved: ((requestId: String, token: String, rqtoken: String?) -> Unit)? = null
        private val callbacks = java.util.concurrent.ConcurrentHashMap<String, (String, String?) -> Unit>()
        private const val ARG_REQUEST_ID = "request_id"
        private const val ARG_SITE_KEY = "site_key"
        private const val ARG_RQTOKEN = "rqtoken"

        fun show(manager: FragmentManager, requestId: String, siteKey: String, rqtoken: String?) {
            WidgetCaptchaBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_REQUEST_ID, requestId)
                    putString(ARG_SITE_KEY, siteKey)
                    putString(ARG_RQTOKEN, rqtoken)
                }
            }.show(manager, WidgetCaptchaBottomSheet::class.java.name)
        }

        fun show(
            manager: FragmentManager,
            requestId: String,
            siteKey: String,
            rqtoken: String?,
            solved: (String, String?) -> Unit,
        ) {
            callbacks[requestId] = solved
            show(manager, requestId, siteKey, rqtoken)
        }

        private fun html(siteKey: String) = """
            <!doctype html><html><head>
            <meta name="viewport" content="width=device-width,initial-scale=1" />
            <script src="https://js.hcaptcha.com/1/api.js" async defer></script>
            </head><body style="margin:0;background:#202225;display:flex;align-items:center;justify-content:center">
            <div class="h-captcha" data-sitekey=$siteKey data-callback="captchaSolved"></div>
            <script>function captchaSolved(token){ Android.onToken(token); }</script>
            </body></html>
        """.trimIndent()
    }
}
