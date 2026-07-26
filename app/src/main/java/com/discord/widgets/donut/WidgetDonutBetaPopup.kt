package com.discord.widgets.donut

import android.content.Context
import android.view.View
import android.widget.ImageView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.i18n.setI18nText
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.view.text.LinkifiedTextView
import com.google.android.material.button.MaterialButton

/**
 * "You need the beta app" — shown when a server-subscription ("donut") surface is reached from a
 * build that cannot render it.
 *
 * There is nothing to configure: the only action is opening the Play Store beta opt-in page, and
 * both dismiss affordances finish the hosting activity.
 */
class WidgetDonutBetaPopup : AppFragment(R.layout.widget_donut_beta_popup) {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val body: LinkifiedTextView = view.findViewById(R.id.donut_beta_body)
        val close: ImageView = view.findViewById(R.id.donut_beta_close)
        val openButton: MaterialButton = view.findViewById(R.id.donut_beta_open_button)
        val closeButton: MaterialButton = view.findViewById(R.id.donut_beta_close_button)

        body.setI18nText(R.string.donut_get_android_beta_body, "helpArticle" to BETA_HELP_URL)
        openButton.setOnClickListener { UriHandler.handle(it.context, BETA_OPT_IN_URL) }
        close.setOnClickListener { activity?.finish() }
        closeButton.setOnClickListener { activity?.finish() }
    }

    companion object {
        /** Zendesk article 360003953831, "How do I join the beta?". */
        private const val BETA_HELP_URL =
            "https://support.discord.com/hc/en-us/articles/360003953831"

        private const val BETA_OPT_IN_URL = "https://play.google.com/apps/testing/com.discord"

        private const val MODAL_NAME = "Project Donut Android Beta Required"

        /** [source] is the analytics entry point this popup was reached from. */
        fun show(context: Context, source: String) {
            AnalyticsTracker.openModal(MODAL_NAME, source)
            AppScreenRouter.launch(context, WidgetDonutBetaPopup::class.java)
        }
    }
}
