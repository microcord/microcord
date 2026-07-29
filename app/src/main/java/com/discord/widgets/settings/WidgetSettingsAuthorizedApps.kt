package com.discord.widgets.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.models.domain.ModelOAuth2Token
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.views.SimpleRecyclerAdapter
import com.discord.views.OAuthPermissionViews
import com.discord.widgets.notice.WidgetNoticeDialog

/** Applications the user has authorized through OAuth2. */
class WidgetSettingsAuthorizedApps : AppFragment(R.layout.widget_settings_authorized_apps) {

    private val adapter = SimpleRecyclerAdapter<ModelOAuth2Token, AuthorizedAppViewHolder> {
            inflater, parent ->
        AuthorizedAppViewHolder(inflater, parent, ::deauthorizeApp)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.authorized_apps)
        setActionBarSubtitle(R.string.user_settings)
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)
        view.findViewById<RecyclerView>(R.id.authorized_apps_list).adapter = adapter
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        loadAuthorizedApps()
    }

    private fun loadAuthorizedApps() {
        RestAPI.api.getOAuthTokens()
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetSettingsAuthorizedApps::class.java, context) { adapter.setData(it) }
    }

    private fun deauthorizeApp(token: ModelOAuth2Token) {
        WidgetNoticeDialog.Builder(requireContext())
            .setTitle(R.string.deauthorize_app)
            .setMessage(R.string.delete_app_confirm_msg)
            .setNegativeButton(R.string.cancel)
            .setPositiveButton(R.string.deauthorize) {
                RestAPI.api.deleteOAuthToken(token.id)
                    .restSubscribeOn()
                    .ui(this)
                    .appSubscribe(
                        errorClass = WidgetSettingsAuthorizedApps::class.java,
                        context = context,
                        onCompleted = ::loadAuthorizedApps,
                        onNext = {},
                    )
            }
            .show(parentFragmentManager)
    }

    private class AuthorizedAppViewHolder(
        inflater: LayoutInflater,
        parent: ViewGroup,
        private val onDeauthorize: (ModelOAuth2Token) -> Unit,
    ) : SimpleRecyclerAdapter.ViewHolder<ModelOAuth2Token>(
        inflater.inflate(R.layout.widget_settings_authorized_apps_list_item, parent, false),
    ) {
        private val icon: ImageView = itemView.findViewById(R.id.oauth_application_icon_iv)
        private val name: TextView = itemView.findViewById(R.id.oauth_application_name_tv)
        private val descriptionLabel: TextView =
            itemView.findViewById(R.id.oauth_application_description_label_tv)
        private val description: TextView = itemView.findViewById(R.id.oauth_application_description_tv)
        private val permissionsLabel: TextView =
            itemView.findViewById(R.id.oauth_application_permissions_label_tv)
        private val permissions = SimpleRecyclerAdapter<com.discord.api.auth.OAuthScope,
            OAuthPermissionViews.ScopeViewHolder> { scopeInflater, scopeParent ->
            OAuthPermissionViews.ScopeViewHolder.create(scopeInflater, scopeParent)
        }

        init {
            itemView.findViewById<RecyclerView>(R.id.oauth_application_permissions_rv).adapter = permissions
            listOf(name, descriptionLabel, permissionsLabel).forEach {
                ViewCompat.setAccessibilityHeading(it, true)
            }
        }

        override fun bind(data: ModelOAuth2Token) {
            val application = data.application
            MGImages.setImage(
                icon,
                application.icon?.let { IconUtils.getApplicationIcon(application.id, it) },
            )
            name.text = application.name
            itemView.contentDescription = application.name

            val hasDescription = !application.description.isNullOrBlank()
            descriptionLabel.visibility = if (hasDescription) View.VISIBLE else View.GONE
            description.text = application.description
            description.visibility = if (hasDescription) View.VISIBLE else View.GONE
            permissions.setData(data.scopes)
            itemView.findViewById<View>(R.id.oauth_application_deauthorize_btn)
                .setOnClickListener { onDeauthorize(data) }
        }
    }

    companion object {
        fun launch(context: Context) {
            SettingsNavigation.launch(context, WidgetSettingsAuthorizedApps::class.java)
        }
    }
}
