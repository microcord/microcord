package com.discord.widgets.settings

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.i18n.i18nFormat
import com.discord.models.domain.ModelOAuth2Token
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.notice.WidgetNoticeDialog
import java.text.DateFormat
import java.util.Date

private const val ARG_TOKEN_ID = "token_id"

/** One authorized application: when it was authorized, its policies, and how to revoke it. */
class WidgetSettingsAuthorizedApp : AppFragment(R.layout.widget_settings_authorized_app) {

    private val tokenId: Long get() = arguments?.getLong(ARG_TOKEN_ID) ?: 0L

    private lateinit var icon: ImageView
    private lateinit var authorizedOn: TextView
    private lateinit var description: TextView
    private lateinit var terms: TextView
    private lateinit var privacy: TextView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)
        icon = view.findViewById(R.id.authorized_app_icon)
        authorizedOn = view.findViewById(R.id.authorized_app_authorized_on)
        description = view.findViewById(R.id.authorized_app_description)
        terms = view.findViewById(R.id.authorized_app_terms)
        privacy = view.findViewById(R.id.authorized_app_privacy)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        AuthorizedApps.cached(tokenId)?.let(::render)
        AuthorizedApps.fetch()
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetSettingsAuthorizedApp::class.java, context) { tokens ->
                // Revoking from here, or from another client, leaves nothing to show.
                val token = tokens.firstOrNull { it.id == tokenId } ?: return@appSubscribe close()
                render(token)
            }
    }

    private fun close() {
        parentFragmentManager.popBackStack()
        activity?.finish()
    }

    private fun render(token: ModelOAuth2Token) {
        val application = token.application
        setActionBarTitle(application.name)
        MGImages.setImage(
            icon,
            application.icon?.let { IconUtils.getApplicationIcon(application.id, it) },
        )
        authorizedOn.text = requireContext().i18nFormat(
            R.string.authorized_apps_authorized_on,
            "date" to DATE_FORMAT.format(Date(SnowflakeUtils.toTimestamp(token.id))),
        )
        val blurb = application.description?.takeUnless(String::isBlank)
        description.text = blurb
        description.visibility = if (blurb == null) View.GONE else View.VISIBLE

        requireView().findViewById<View>(R.id.authorized_app_permissions).setOnClickListener {
            WidgetSettingsAuthorizedAppPermissions.launch(it.context, token.id)
        }
        bindLink(terms, application.termsOfServiceUrl)
        bindLink(privacy, application.privacyPolicyUrl)
        requireView().findViewById<View>(R.id.authorized_app_deauthorize)
            .setOnClickListener { confirmDeauthorize(token) }
    }

    private fun bindLink(row: TextView, url: String?) {
        row.visibility = if (url == null) View.GONE else View.VISIBLE
        row.setOnClickListener { UriHandler.handle(row.context, url ?: return@setOnClickListener) }
    }

    private fun confirmDeauthorize(token: ModelOAuth2Token) {
        WidgetNoticeDialog.Builder(requireContext())
            .setTitle(R.string.deauthorize_app)
            .setMessage(
                requireContext().i18nFormat(
                    R.string.delete_app_confirm_msg_v2,
                    "applicationName" to token.application.name,
                ),
            )
            .setNegativeButton(R.string.cancel)
            .setPositiveButton(R.string.deauthorize) {
                AuthorizedApps.forget(token.id)
                RestAPI.api.deleteOAuthToken(token.id)
                    .restSubscribeOn()
                    .ui(this)
                    .appSubscribe(
                        errorClass = WidgetSettingsAuthorizedApp::class.java,
                        context = context,
                        onCompleted = ::close,
                        onNext = {},
                    )
            }
            .setDialogAttrTheme(R.attr.notice_theme_positive_red)
            .show(parentFragmentManager)
    }

    companion object {
        private val DATE_FORMAT: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

        fun launch(context: Context, tokenId: Long) = SettingsNavigation.launch(
            context,
            WidgetSettingsAuthorizedApp().apply {
                arguments = Bundle().apply { putLong(ARG_TOKEN_ID, tokenId) }
            },
        )
    }
}
