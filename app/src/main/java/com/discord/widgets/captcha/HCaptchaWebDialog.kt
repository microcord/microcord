package com.discord.widgets.captcha

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppLog
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * The actual hCaptcha challenge, hosted in the WebView the recovered hCaptcha Android SDK ships.
 *
 * `assets/hcaptcha-form.html` and `res/layout/hcaptcha_fragment.xml` are that SDK's own recovered
 * assets, so this dialog implements exactly the bridge contract the page expects: a JavaScript
 * object named `JSInterface` with [Bridge.getConfig], [Bridge.onPass], [Bridge.onError] and
 * [Bridge.onLoaded]. Reusing the recovered page rather than hand-writing an embed is what keeps
 * enterprise `rqdata`, the locale, and the close/expiry callbacks working.
 *
 * This is deliberately *not* `WidgetCaptchaBottomSheet`. In the reference those are two different
 * things: the bottom sheet is Discord's "you need to solve a captcha" prompt with a button, and the
 * SDK dialog is what that button opens. Merging them — which an earlier reconstruction did — is why
 * the sheet had no Discord chrome and no way to report that the user closed the challenge.
 *
 * **The dismissal hook matters.** `hcaptcha-form.html` reports a user-closed challenge as
 * [CHALLENGE_CLOSED]; anything that dismisses the dialog without a token (back press, rotation,
 * host teardown) is reported the same way through [onOutcome]. `CaptchaHelper` turns that into a
 * completed-without-a-value stream, which is what lets a queued message send finish instead of
 * hanging forever on a challenge the user walked away from.
 *
 * The solved token is a credential for one retry: it is handed straight to the waiting caller and
 * never logged, persisted, or written into saved instance state.
 */
class HCaptchaWebDialog : DialogFragment() {

    private var webView: WebView? = null

    /** Set once a token or an explicit error code has been delivered, so dismissal is not double-reported. */
    private var outcomeDelivered: Boolean = false

    /** Result of the challenge. Exactly one of these is delivered per dialog. */
    sealed class Outcome {
        /** The user solved it. [token] is single-use and must not be stored. */
        class Solved(val token: String) : Outcome() {
            override fun toString() = "Solved(token=<redacted>)"
        }

        /** The challenge ended without a token; [errorCode] is an `hcaptcha-form.html` code. */
        data class Failed(val errorCode: Int) : Outcome() {
            val isUserClosed: Boolean get() = errorCode == CHALLENGE_CLOSED
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.hcaptcha_fragment, container, false)

    /**
     * `hcaptcha_fragment.xml` is a `match_parent` WebView, and the page centres the widget in the
     * viewport itself. That only works over a transparent, full-width window: left floating, the
     * dialog draws its own opaque background behind a mostly empty page and then clips the widget
     * at the right edge. Full width also gives the image-grid challenge room when one is served.
     */
    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val loading = view.findViewById<View>(R.id.loadingContainer)
        val web = view.findViewById<WebView>(R.id.webView)
        webView = web
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // The page is loaded from the app's own assets and only ever talks to hCaptcha. Nothing
        // here relaxes TLS validation or mixed-content policy.
        web.setBackgroundColor(0)
        web.addJavascriptInterface(Bridge({ loading.visibility = View.GONE }), BRIDGE_NAME)
        web.loadUrl(FORM_URL)
    }

    override fun onDestroyView() {
        webView?.apply {
            removeJavascriptInterface(BRIDGE_NAME)
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroyView()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        // Back press, touch-outside, or the host tearing us down: the caller is waiting and has to
        // be told the challenge is over, otherwise whatever it gated stays pending forever.
        if (!outcomeDelivered) deliver(Outcome.Failed(CHALLENGE_CLOSED))
    }

    private fun deliver(outcome: Outcome) {
        if (outcomeDelivered) return
        outcomeDelivered = true
        val challengeId = arguments?.getString(ARG_CHALLENGE_ID) ?: return
        outcomes.remove(challengeId)?.invoke(outcome)
    }

    /**
     * Bridge object the recovered form expects under the name `JSInterface`.
     *
     * Every method runs on the WebView's JavaScript thread, so anything touching the fragment is
     * posted back to the main thread.
     */
    private inner class Bridge(private val onLoadedUi: () -> Unit) {

        @JavascriptInterface
        fun getConfig(): String {
            val arguments = arguments ?: return "{}"
            return JSONObject()
                .put("siteKey", arguments.getString(ARG_SITE_KEY))
                .put("locale", arguments.getString(ARG_LOCALE))
                // `should_serve_invisible` from the challenge body. Invisible mode still shows a
                // challenge when the provider decides the request needs one; it only skips the
                // "I am human" checkbox for sessions hCaptcha already trusts.
                .put("size", if (arguments.getBoolean(ARG_INVISIBLE)) "invisible" else "normal")
                .put("theme", "dark")
                .put("sentry", false)
                .put("rqdata", arguments.getString(ARG_RQDATA) ?: JSONObject.NULL)
                .put("apiEndpoint", API_ENDPOINT)
                .put("endpoint", JSONObject.NULL)
                .put("assethost", JSONObject.NULL)
                .put("imghost", JSONObject.NULL)
                .put("reportapi", JSONObject.NULL)
                .toString()
        }

        @JavascriptInterface
        fun onPass(token: String) {
            activity?.runOnUiThread {
                deliver(if (token.isBlank()) Outcome.Failed(SESSION_TIMEOUT) else Outcome.Solved(token))
                dismissAllowingStateLoss()
            }
        }

        @JavascriptInterface
        fun onError(errorCode: Int) {
            activity?.runOnUiThread {
                AppLog.d("hCaptcha challenge ended with code $errorCode")
                deliver(Outcome.Failed(errorCode))
                dismissAllowingStateLoss()
            }
        }

        @JavascriptInterface
        fun onLoaded() {
            activity?.runOnUiThread { onLoadedUi() }
        }
    }

    companion object {
        /** `hcaptcha-form.html` codes. Names follow the hCaptcha Android SDK's `HCaptchaError`. */
        const val NETWORK_ERROR = 7
        const val SESSION_TIMEOUT = 15
        const val INTERNAL_ERROR = 29
        const val CHALLENGE_CLOSED = 30
        const val RATE_LIMITED = 31

        private const val BRIDGE_NAME = "JSInterface"
        private const val FORM_URL = "file:///android_asset/hcaptcha-form.html"
        private const val API_ENDPOINT = "https://hcaptcha.com/1/api.js"

        private const val ARG_CHALLENGE_ID = "challenge_id"
        private const val ARG_SITE_KEY = "site_key"
        private const val ARG_RQDATA = "rqdata"
        private const val ARG_LOCALE = "locale"
        private const val ARG_INVISIBLE = "invisible"

        /**
         * Pending outcome handlers, keyed by challenge id.
         *
         * Process-local by design: a captcha challenge is a live interaction that cannot survive
         * process death, and the reference SDK behaves the same way. A dialog restored with no
         * handler simply reports [CHALLENGE_CLOSED] into the void and closes.
         */
        private val outcomes = ConcurrentHashMap<String, (Outcome) -> Unit>()

        /**
         * Shows the challenge. [onOutcome] is invoked exactly once, on the main thread.
         *
         * @param challengeId identifies this challenge so the handler survives the dialog being
         *   re-instantiated by the fragment manager within the same process.
         */
        fun show(
            fragmentManager: FragmentManager,
            challengeId: String,
            siteKey: String,
            rqdata: String?,
            locale: String,
            invisible: Boolean = false,
            onOutcome: (Outcome) -> Unit,
        ) {
            outcomes[challengeId] = onOutcome
            HCaptchaWebDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_CHALLENGE_ID, challengeId)
                    putString(ARG_SITE_KEY, siteKey)
                    putString(ARG_RQDATA, rqdata)
                    putString(ARG_LOCALE, locale)
                    putBoolean(ARG_INVISIBLE, invisible)
                }
            }.show(fragmentManager, "$TAG:$challengeId")
        }

        /** Drops a pending handler, e.g. when the subscriber that asked for the challenge went away. */
        fun cancel(challengeId: String) {
            outcomes.remove(challengeId)
        }

        private val TAG: String = HCaptchaWebDialog::class.java.name
    }
}
