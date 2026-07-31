package com.discord.dialogs

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppComponent
import com.discord.app.AppDialog
import com.discord.i18n.setI18nText
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import com.discord.utilities.string.toPunyCodeASCIIUrl
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.chat.pins.WidgetChannelPinnedMessages
import com.discord.widgets.home.WidgetHome
import com.discord.widgets.media.WidgetMedia
import com.discord.widgets.search.WidgetSearch
import com.discord.widgets.user.WidgetUserMentions
import com.google.android.material.button.MaterialButton
import kotlin.reflect.KClass

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
            StoreStream.getMaskedLinks().trustDomain(url)
            openAndDismiss(v.context, url)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The notice queue holds the next notice back until this one is marked seen.
        StoreStream.getNotices().markSeen(NOTICE_NAME)
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
         * Only screens that can actually contain a link may interrupt with this dialog; anywhere
         * else the notice waits in the queue.
         */
        private val VALID_SCREENS: List<KClass<out AppComponent>> = listOf(
            WidgetHome::class,
            WidgetUserMentions::class,
            WidgetSearch::class,
            WidgetChannelPinnedMessages::class,
            WidgetMedia::class,
        )

        fun show(fragmentManager: FragmentManager, url: String) {
            WidgetMaskedLinksDialog().apply {
                arguments = Bundle().apply { putString(ARG_URL, url) }
            }.show(fragmentManager, NOTICE_NAME)
        }

        /**
         * Installs this dialog as [UriHandler]'s untrusted-link confirmation step.
         *
         * The reference does not need an installer: `UriHandler.handleOrUntrusted` enqueues the
         * notice itself. This project's `UriHandler` deliberately has no dependency on the widget
         * tree or the store graph, so the wiring is inverted and has to be performed once during
         * app start-up. **Until it is called, an untrusted link is silently dropped.**
         */
        @JvmStatic
        fun installUntrustedLinkHandler() {
            UriHandler.untrustedLinkHandler = { _, url ->
                StoreStream.getNotices().requestToShow(
                    StoreNotices.Notice(
                        name = NOTICE_NAME,
                        priority = StoreNotices.PRIORITY_HIGH,
                        persist = false,
                        validScreens = VALID_SCREENS,
                        delayPeriodMs = 0L,
                        // The user asked for this link now; a previous confirmation must not
                        // suppress the next one.
                        sinceLastPeriodMs = 0L,
                        show = { activity ->
                            show(activity.supportFragmentManager, url)
                            true
                        },
                    ),
                )
            }
        }
    }
}
