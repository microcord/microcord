package com.discord.widgets.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.application.ApplicationDisclosureType
import com.discord.app.AppFragment
import com.discord.models.domain.ModelOAuth2Token
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.views.SimpleRecyclerAdapter
import com.discord.views.OAuthPermissionViews

private const val ARG_TOKEN_ID = "token_id"

/** What one authorized application may do, plus the disclosures accepted alongside it. */
class WidgetSettingsAuthorizedAppPermissions :
    AppFragment(R.layout.widget_settings_authorized_app_permissions) {

    private val tokenId: Long get() = arguments?.getLong(ARG_TOKEN_ID) ?: 0L

    private lateinit var appName: TextView

    private val permissions =
        SimpleRecyclerAdapter<CharSequence, OAuthPermissionViews.ScopeViewHolder> {
                inflater, parent ->
            OAuthPermissionViews.ScopeViewHolder.create(inflater, parent)
        }
    private val disclosures = SimpleRecyclerAdapter<ApplicationDisclosureType, DisclosureViewHolder> {
            inflater, parent ->
        DisclosureViewHolder(inflater, parent)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)
        appName = view.findViewById(R.id.authorized_app_permissions_app_name)
        view.findViewById<RecyclerView>(R.id.authorized_app_permissions_list).adapter = permissions
        view.findViewById<RecyclerView>(R.id.authorized_app_disclosures_list).adapter = disclosures
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        AuthorizedApps.cached(tokenId)?.let(::render)
        AuthorizedApps.fetch()
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetSettingsAuthorizedAppPermissions::class.java, context) { tokens ->
                val token = tokens.firstOrNull { it.id == tokenId }
                    ?: return@appSubscribe activity?.finish().let { }
                render(token)
            }
    }

    private fun render(token: ModelOAuth2Token) {
        appName.text = token.application.name
        permissions.setData(
            OAuthPermissionViews.scopeDescriptions(requireContext(), token.scopes),
        )
        disclosures.setData(token.disclosures.orEmpty().filter { it.textResId() != null })
    }

    private class DisclosureViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
        SimpleRecyclerAdapter.ViewHolder<ApplicationDisclosureType>(
            inflater.inflate(R.layout.oauth_token_disclosure_list_item, parent, false),
        ) {
        private val text = itemView as TextView

        override fun bind(data: ApplicationDisclosureType) {
            text.setText(data.textResId() ?: return)
            TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(
                text,
                data.iconResId(),
                0,
                0,
                0,
            )
        }
    }

    companion object {
        fun launch(context: Context, tokenId: Long) = SettingsNavigation.launch(
            context,
            WidgetSettingsAuthorizedAppPermissions().apply {
                arguments = Bundle().apply { putLong(ARG_TOKEN_ID, tokenId) }
            },
        )

        /** Null for a disclosure with nothing to say, which the reference renders as no row. */
        private fun ApplicationDisclosureType.textResId(): Int? = when (this) {
            ApplicationDisclosureType.IP_LOCATION -> R.string.oauth2_ip_location_disclosure
            ApplicationDisclosureType.DISPLAYS_ADVERTISEMENTS ->
                R.string.oauth2_advertisements_disclosure
            else -> null
        }

        private fun ApplicationDisclosureType.iconResId(): Int = when (this) {
            ApplicationDisclosureType.IP_LOCATION -> R.drawable.ic_globe_24dp
            else -> R.drawable.ic_embed_white_24dp
        }
    }
}
