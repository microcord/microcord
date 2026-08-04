package com.discord.widgets.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.models.domain.ModelOAuth2Token
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.views.SimpleRecyclerAdapter

/** Applications the user has authorized through OAuth2. Each row opens its own detail screen. */
class WidgetSettingsAuthorizedApps : AppFragment(R.layout.widget_settings_authorized_apps) {

    private lateinit var groupHeader: TextView
    private lateinit var empty: TextView
    private lateinit var loading: View

    private val adapter = SimpleRecyclerAdapter<ModelOAuth2Token, AuthorizedAppViewHolder> {
            inflater, parent ->
        AuthorizedAppViewHolder(inflater, parent, ::openApp)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)
        groupHeader = view.findViewById(R.id.authorized_apps_group_header)
        empty = view.findViewById(R.id.authorized_apps_empty)
        loading = view.findViewById(R.id.authorized_apps_loading)
        view.findViewById<RecyclerView>(R.id.authorized_apps_list).adapter = adapter
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        AuthorizedApps.cached()?.let(::render)
        AuthorizedApps.fetch()
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetSettingsAuthorizedApps::class.java, context) { render(it) }
    }

    private fun render(tokens: List<ModelOAuth2Token>) {
        val listed = visibleTokens(tokens)
        loading.visibility = View.GONE
        groupHeader.visibility = if (listed.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (listed.isEmpty()) View.VISIBLE else View.GONE
        adapter.setData(listed)
    }

    private fun openApp(token: ModelOAuth2Token) =
        WidgetSettingsAuthorizedApp.launch(requireContext(), token.id)

    private class AuthorizedAppViewHolder(
        inflater: LayoutInflater,
        parent: ViewGroup,
        private val onClick: (ModelOAuth2Token) -> Unit,
    ) : SimpleRecyclerAdapter.ViewHolder<ModelOAuth2Token>(
        inflater.inflate(R.layout.widget_settings_authorized_apps_list_item, parent, false),
    ) {
        private val icon: ImageView = itemView.findViewById(R.id.oauth_application_icon_iv)
        private val name: TextView = itemView.findViewById(R.id.oauth_application_name_tv)

        override fun bind(data: ModelOAuth2Token) {
            val application = data.application
            MGImages.setImage(
                icon,
                application.icon?.let { IconUtils.getApplicationIcon(application.id, it) },
            )
            name.text = application.name
            itemView.contentDescription = application.name
            itemView.setOnClickListener { onClick(data) }
        }
    }

    companion object {
        /**
         * Newest first, and social-layer child applications are folded away — the reference lists
         * only the parent an SDK game authorizes through.
         */
        internal fun visibleTokens(tokens: List<ModelOAuth2Token>): List<ModelOAuth2Token> = tokens
            .filter { it.application.parentId == null }
            .sortedByDescending { it.id }

        fun launch(context: Context) {
            SettingsNavigation.launch(context, WidgetSettingsAuthorizedApps::class.java)
        }
    }
}
