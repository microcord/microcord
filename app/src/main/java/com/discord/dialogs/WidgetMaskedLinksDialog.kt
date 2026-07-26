package com.discord.dialogs

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.i18n.setI18nText
import com.discord.utilities.string.toPunyCodeASCIIUrl
import com.discord.utilities.uri.UriHandler
import com.google.android.material.button.MaterialButton

/**
 * The phishing guard shown before an untrusted masked link is opened.
 *
 * A masked link renders as `[some text](https://elsewhere.example)`, so the destination the user
 * sees and the destination they get can differ arbitrarily. [com.discord.stores.StoreMaskedLinks]
 * decides which destinations may be opened silently; every other one lands here, where the real URL
 * is spelled out and the user picks one of three outcomes:
 *
 * - **Cancel** — nothing happens.
 * - **Confirm** — the link opens this once.
 * - **Trust this domain** — the domain is remembered, then the link opens.
 *
 * The URL is punycode-encoded for display ([toPunyCodeASCIIUrl]), which is the whole point of the
 * dialog: a homograph domain that renders identically to a legitimate one shows its `xn--` form
 * here. A URL that cannot be encoded is not one we can honestly describe, so the dialog dismisses
 * itself rather than showing something misleading.
 */
class WidgetMaskedLinksDialog : AppDialog(R.layout.widget_masked_links_dialog) {

    private val url: String
        get() = argumentsOrDefault.getString(ARG_URL).orEmpty()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val url = this.url

        val displayUrl = try {
            url.toPunyCodeASCIIUrl()
        } catch (e: Exception) {
            dismiss()
            return
        }

        view.findViewById<TextView>(R.id.masked_links_body_text)
            .setI18nText(R.string.masked_link_body, "url" to displayUrl)

        view.findViewById<MaterialButton>(R.id.masked_links_cancel).setOnClickListener { dismiss() }
        view.findViewById<MaterialButton>(R.id.masked_links_confirm).setOnClickListener { v ->
            openAndDismiss(v.context, url)
        }
        view.findViewById<TextView>(R.id.masked_links_trust_domain).setOnClickListener { v ->
            trustDomainHandler?.invoke(url)
            openAndDismiss(v.context, url)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The notice queue holds the next notice back while this one is on screen.
        noticeSeenHandler?.invoke(NOTICE_NAME)
    }

    /**
     * Opens the link through the plain [UriHandler.handle] path, not `handleOrUntrusted` — the
     * trust decision has already been made and re-checking it would loop straight back here.
     */
    private fun openAndDismiss(context: Context, url: String) {
        UriHandler.handle(context, url)
        dismiss()
    }

    companion object {
        /** `StoreNotices` notice name; kept verbatim so a persisted "seen" record still matches. */
        const val NOTICE_NAME: String = "WIDGET_SPOOPY_LINKS_DIALOG"

        private const val ARG_URL = "WIDGET_SPOOPY_LINKS_DIALOG_URL"

        /**
         * Remembers a domain the user chose to trust.
         *
         * Installed by whoever publishes `StoreMaskedLinks` on `StoreStream`; the reference calls
         * `StoreStream.getMaskedLinks().trustDomain(url)` directly. Until it is installed the
         * "Trust this Domain" row still opens the link, it just does not persist the decision.
         */
        @JvmStatic
        var trustDomainHandler: ((url: String) -> Unit)? = null

        /**
         * Records that this notice was shown. Installed by `StoreNotices` when it lands; it is the
         * same hook `WidgetNoticeDialog.dialogSeenHandler` uses, and both take the notice name.
         */
        @JvmStatic
        var noticeSeenHandler: ((noticeName: String) -> Unit)? = null

        fun show(fragmentManager: FragmentManager, url: String) {
            WidgetMaskedLinksDialog().apply {
                arguments = Bundle().apply { putString(ARG_URL, url) }
            }.show(fragmentManager, NOTICE_NAME)
        }

        /**
         * Installs this dialog as [UriHandler]'s untrusted-link confirmation step.
         *
         * The reference does not need an installer: `UriHandler.handleOrUntrusted` enqueues the
         * notice itself. This project's `UriHandler` has no dependency on the widget tree, so the
         * wiring is inverted and has to be performed once during app start-up. **Until it is called,
         * an untrusted link is silently dropped.**
         */
        @JvmStatic
        fun installUntrustedLinkHandler() {
            UriHandler.untrustedLinkHandler = { context, url ->
                val fragmentManager = context.findFragmentManager()
                if (fragmentManager != null) {
                    show(fragmentManager, url)
                } else {
                    // No host to show a dialog on. Dropping the link is the safe failure: the
                    // alternative is opening a destination the user never confirmed.
                    com.discord.app.AppLog.w("Dropped untrusted link: no FragmentManager for $context")
                }
            }
        }

        /** Unwraps `ContextWrapper`s until a [FragmentActivity] appears, or gives up. */
        private fun Context.findFragmentManager(): FragmentManager? {
            var current: Context? = this
            while (current != null) {
                if (current is FragmentActivity) return current.supportFragmentManager
                current = (current as? android.content.ContextWrapper)?.baseContext
            }
            return null
        }
    }
}
