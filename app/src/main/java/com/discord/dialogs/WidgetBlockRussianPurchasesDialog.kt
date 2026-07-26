package com.discord.dialogs

import android.text.method.LinkMovementMethod
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.i18n.setI18nText
import com.discord.utilities.view.text.LinkifiedTextView
import com.google.android.material.button.MaterialButton

/**
 * Explains that purchases are unavailable in the user's region.
 *
 * Informational and unavoidable — the body carries a masked link to the support article and the
 * only control acknowledges it. Named after the sanctions rollout it shipped for, which is why the
 * copy resources are `region_blocked_payments_*` while the class is not.
 */
class WidgetBlockRussianPurchasesDialog : AppDialog(R.layout.widget_block_russian_purchases_dialog) {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val body = view.findViewById<LinkifiedTextView>(R.id.notice_body_text)
        body.setI18nText(R.string.region_blocked_payments_cta, "helpdeskArticle" to HELP_ARTICLE_URL)
        body.movementMethod = LinkMovementMethod.getInstance()
        view.findViewById<MaterialButton>(R.id.notice_ok).setOnClickListener { dismiss() }
    }

    companion object {
        /**
         * Article 4708041338391, "Payments in your region".
         *
         * The reference localises the `/hc/<locale>/` segment through `AppHelpDesk`, which this
         * project has not reconstructed; pinned to `en-us` like the other help-desk links here.
         */
        private const val HELP_ARTICLE_URL =
            "https://support.discord.com/hc/en-us/articles/4708041338391"

        fun show(fragmentManager: FragmentManager) {
            WidgetBlockRussianPurchasesDialog().show(
                fragmentManager,
                WidgetBlockRussianPurchasesDialog::class.java.name,
            )
        }
    }
}
