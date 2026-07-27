package com.discord.widgets.tos

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.app.AppScreenRouter
import com.discord.i18n.setI18nText
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.text.LinkifiedTextView
import com.google.android.material.button.MaterialButton

/**
 * The blocking Terms of Service screen.
 *
 * Reached as a required action: the API tells the client the account must either **agree** to the
 * updated policies (`AGREEMENTS`) or merely **acknowledge** that they are about to change
 * (`TOS_UPDATE_ACKNOWLEDGMENT`). Both variants render the same four policy links; only the copy and
 * the request body differ, which is what [isAcknowledgement] selects.
 *
 * The screen is deliberately inescapable — back is swallowed — so the only ways out are accepting
 * or logging out from the overflow menu.
 */
class WidgetTos : AppFragment(R.layout.widget_tos) {

    /**
     * `true` for the "heads up, these policies change soon" variant.
     *
     * The reference only ever reads the activity intent, because it always arrives through
     * [show] — a fresh activity carrying the extra. This screen is *also* reached as the
     * `AGREEMENTS` / `TOS_UPDATE_ACKNOWLEDGMENT` required action, where the host instantiates the
     * fragment directly and has nowhere to put an intent extra, so the fragment arguments are
     * checked first. When neither is set the answer is `false`, which is the agreement variant —
     * the safe default, since it asks for consent rather than assuming it.
     */
    private val isAcknowledgement: Boolean by lazy {
        arguments?.takeIf { it.containsKey(INTENT_EXTRA_IS_ACKNOWLEDGEMENT) }
            ?.getBoolean(INTENT_EXTRA_IS_ACKNOWLEDGEMENT)
            ?: mostRecentIntent.getBooleanExtra(INTENT_EXTRA_IS_ACKNOWLEDGEMENT, false)
    }

    private val continueButton: MaterialButton
        get() = requireView().findViewById(R.id.alert_tos_ack_continue)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setOnBackPressed({ true })
        setActionBarOptionsMenu(R.menu.menu_settings_logout, { menuItem ->
            if (menuItem.itemId == R.id.menu_settings_log_out) {
                StoreStream.getAuthentication().logout()
            }
        })
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        configureUI()
    }

    private fun configureUI() {
        configureCommonUI()
        if (isAcknowledgement) configureAcknowledgementUI() else configureAgreementUI()
    }

    /** The four policy links, each of which is a markdown masked link in the recovered strings. */
    private fun configureCommonUI() {
        val view = requireView()
        view.findViewById<LinkifiedTextView>(R.id.alert_tos_terms_of_service)
            .setI18nText(R.string.terms_of_service, "url" to getString(R.string.terms_of_service_url))
        view.findViewById<LinkifiedTextView>(R.id.alert_tos_privacy_policy)
            .setI18nText(R.string.privacy_policy, "url" to getString(R.string.privacy_policy_url))
        view.findViewById<LinkifiedTextView>(R.id.alert_tos_community_guidelines)
            .setI18nText(R.string.community_guidelines, "url" to getString(R.string.community_guidelines_url))
        view.findViewById<LinkifiedTextView>(R.id.alert_tos_blog_post)
            .setI18nText(R.string.blog_post, "url" to getString(R.string.important_updates_url))
    }

    /**
     * Label and click handler shared by both variants.
     *
     * The reference submits the agreement and finishes the activity immediately, without waiting for
     * the response: the required action is cleared by the gateway, and a failed call simply leaves
     * the action pending so the screen returns on the next launch.
     */
    private fun configureCommonTosModalUI() {
        val body = if (isAcknowledgement) {
            RestAPIParams.UserAgreements.acknowledge()
        } else {
            RestAPIParams.UserAgreements.agree()
        }
        continueButton.setI18nText(R.string.got_it)
        continueButton.setOnClickListener { submitAgreements(body) }
    }

    private fun configureAcknowledgementUI() {
        configureCommonTosModalUI()
        val view = requireView()
        view.findViewById<TextView>(R.id.alert_tos_text_description_top).setI18nText(
            R.string.terms_acknowledgement_description_1,
            "date" to DateUtils.formatDateTime(requireContext(), ACCEPT_BY_DATE, DateUtils.FORMAT_SHOW_YEAR),
        )
        view.findViewById<TextView>(R.id.alert_tos_text_description_bottom)
            .setI18nText(R.string.terms_acknowledgement_description_2)
    }

    private fun configureAgreementUI() {
        configureCommonTosModalUI()
        val view = requireView()
        view.findViewById<TextView>(R.id.alert_tos_text_description_top)
            .setI18nText(R.string.terms_agreement_description_1)
        view.findViewById<TextView>(R.id.alert_tos_text_description_bottom)
            .setI18nText(R.string.terms_agreement_description_2)
    }

    private fun submitAgreements(body: RestAPIParams.UserAgreements) {
        val api = RestAPI.apiOrNull
        if (api == null) {
            AppLog.w("Cannot submit user agreements before the REST client is published.")
            return
        }
        api.userAgreements(body)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetTos::class.java, context) { }
        activity?.finish()
    }

    companion object {
        /**
         * Selects the acknowledgement variant. Public because the required-action route builds this
         * fragment by class name and has to set it as an argument rather than an intent extra.
         */
        const val INTENT_EXTRA_IS_ACKNOWLEDGEMENT = "intent_args_key"

        /** Opens the screen outside the required-action route, used by settings deep links. */
        fun show(context: Context, isAcknowledgement: Boolean) {
            AppScreenRouter.launch(
                context,
                WidgetTos::class.java,
                Intent().putExtra(INTENT_EXTRA_IS_ACKNOWLEDGEMENT, isAcknowledgement),
            )
        }
    }
}
