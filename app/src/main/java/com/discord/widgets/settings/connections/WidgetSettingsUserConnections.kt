package com.discord.widgets.settings.connections

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.activity.ActivityType
import com.discord.api.connectedaccounts.ConnectedAccount
import com.discord.app.AppFragment
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import com.discord.utilities.integrations.SpotifyHelper
import com.discord.utilities.notices.NoticeBuilders
import com.discord.utilities.platform.Platform
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.settings.SettingsNavigation
import com.google.android.material.switchmaterial.SwitchMaterial

class WidgetSettingsUserConnections : AppFragment(R.layout.widget_settings_connections) {
    private val viewModel: WidgetSettingsUserConnectionsViewModel by viewModels()
    private val adapter = Adapter(::joinIntegration)

    private lateinit var empty: TextView
    private lateinit var recycler: RecyclerView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        empty = view.findViewById(R.id.connections_empty)
        recycler = view.findViewById(R.id.connections_recycler)
        recycler.adapter = adapter
        setActionBarTitle(R.string.connected_accounts)
        setActionBarSubtitle(R.string.user_settings)
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)
        setActionBarOptionsMenu(
            R.menu.menu_connections,
            onSelected = { item ->
                if (item.itemId == R.id.menu_add_connections) WidgetSettingsUserConnectionsAdd.show(this)
            },
        )
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).appSubscribe(javaClass, onNext = ::render)
    }

    private fun render(state: WidgetSettingsUserConnectionsViewModel.ViewState) {
        val data = (state as? WidgetSettingsUserConnectionsViewModel.ViewState.Loaded)?.data.orEmpty()
        empty.visibility = if (state is WidgetSettingsUserConnectionsViewModel.ViewState.Empty) View.VISIBLE else View.GONE
        recycler.visibility = if (state is WidgetSettingsUserConnectionsViewModel.ViewState.Loaded) View.VISIBLE else View.GONE
        adapter.setData(data)
    }

    private fun joinIntegration(id: String) = viewModel.joinConnectionIntegrationGuild(id)

    private inner class Adapter(
        private val onJoinIntegration: (String) -> Unit,
    ) : RecyclerView.Adapter<ConnectionViewHolder>() {
        private var items = emptyList<WidgetSettingsUserConnectionsViewModel.ConnectionState>()

        fun setData(value: List<WidgetSettingsUserConnectionsViewModel.ConnectionState>) {
            items = value
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ConnectionViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.widget_settings_item_connected_account, parent, false),
            onJoinIntegration,
        )

        override fun onBindViewHolder(holder: ConnectionViewHolder, position: Int) = holder.bind(items[position])
    }

    private inner class ConnectionViewHolder(
        itemView: View,
        private val onJoinIntegration: (String) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val image: ImageView = itemView.findViewById(R.id.connected_account_img)
        private val disconnect: ImageView = itemView.findViewById(R.id.connected_account_disconnect)
        private val name: TextView = itemView.findViewById(R.id.connected_account_name)
        private val display: SwitchMaterial = itemView.findViewById(R.id.display_switch)
        private val displayActivity: SwitchMaterial = itemView.findViewById(R.id.display_activity_switch)
        private val syncFriends: SwitchMaterial = itemView.findViewById(R.id.sync_friends_switch)
        private val extraInfo: TextView = itemView.findViewById(R.id.extra_info)
        private val integrations: LinearLayout = itemView.findViewById(R.id.integrations_root)

        fun bind(state: WidgetSettingsUserConnectionsViewModel.ConnectionState) {
            val account = state.connection
            val platform = Platform.from(account)
            name.text = account.name
            image.contentDescription = platform.name
            image.setImageResource(itemView.context.resolveDrawable(platform.themedPlatformImage))
            itemView.setOnClickListener {
                platform.getProfileUrl(account)?.let { url -> UriHandler.handle(it.context, url) }
            }
            disconnect.setOnClickListener { showDisconnectDialog(account, platform) }

            configureSwitch(display, true, account.visibility == ConnectedAccount.VISIBLE) { update(account) }
            configureSwitch(syncFriends, platform.canSyncFriends, account.friendSync) { update(account) }
            configureSwitch(displayActivity, platform.canShowActivity, account.showActivity) {
                update(account)
                if (platform == Platform.SPOTIFY && !displayActivity.isChecked) {
                    StoreStream.getPresences().updateActivity(ActivityType.LISTENING, null)
                }
            }

            if (platform == Platform.SPOTIFY) {
                extraInfo.visibility = View.VISIBLE
                extraInfo.setOnClickListener { SpotifyHelper.openSpotifyApp(itemView.context) }
            } else {
                extraInfo.visibility = View.GONE
                extraInfo.setOnClickListener(null)
            }
            bindIntegrations(account, state.integrationGuildJoinStatus)
        }

        private fun configureSwitch(view: SwitchMaterial, visible: Boolean, checked: Boolean, changed: () -> Unit) {
            view.setOnCheckedChangeListener(null)
            view.visibility = if (visible) View.VISIBLE else View.GONE
            view.isChecked = checked
            view.setOnCheckedChangeListener { _, _ -> changed() }
        }

        private fun update(account: ConnectedAccount) {
            StoreStream.getUserConnections().updateUserConnection(
                account,
                syncFriends.isChecked,
                displayActivity.isChecked,
                display.isChecked,
            )
        }

        private fun bindIntegrations(account: ConnectedAccount, statuses: Map<String, WidgetSettingsUserConnectionsViewModel.JoinStatus>) {
            while (integrations.childCount > 2) integrations.removeViewAt(integrations.childCount - 1)
            val values = account.integrations.orEmpty()
            integrations.visibility = if (values.isEmpty()) View.GONE else View.VISIBLE
            values.forEach { integration ->
                val id = integration.id ?: return@forEach
                integrations.addView(ConnectionsGuildIntegrationView(itemView.context, null).apply {
                    setIntegrationData(integration, statuses[id])
                    setJoinClickListener { onJoinIntegration(id) }
                })
            }
        }
    }

    private fun showDisconnectDialog(account: ConnectedAccount, platform: Platform) {
        NoticeBuilders.showNotice(
            requireContext(),
            parentFragmentManager,
            StoreNotices.Dialog(
                type = StoreNotices.Dialog.Type.DELETE_CONNECTION_MODAL,
                metadata = mapOf(
                    "platform_name" to account.type,
                    "platform_title" to platform.properName,
                    "connection_id" to account.id,
                ),
            ),
        )
    }

    private fun Context.resolveDrawable(attribute: Int?): Int {
        if (attribute == null) return 0
        val value = TypedValue()
        return if (theme.resolveAttribute(attribute, value, true)) value.resourceId else 0
    }

    companion object {
        fun launch(context: Context) = SettingsNavigation.launch(context, WidgetSettingsUserConnections::class.java)
    }
}
