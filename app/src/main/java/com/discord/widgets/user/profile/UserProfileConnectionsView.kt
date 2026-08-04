package com.discord.widgets.user.profile

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.connectedaccounts.ConnectedAccount
import com.discord.utilities.platform.Platform
import com.discord.utilities.platform.resolvePlatformImage

class UserProfileConnectionsView(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    data class ConnectedAccountItem(val connectedAccount: ConnectedAccount) {
        val key: String get() = connectedAccount.id
        val type: Int get() = 0
    }

    data class ViewState(
        val showConnectionsSection: Boolean,
        val showMutualGuildsAndFriends: Boolean,
        val connectedAccountItems: List<ConnectedAccountItem>,
    )

    private val mutualGuilds: TextView
    private val mutualFriends: TextView
    private val recycler: RecyclerView
    private val adapter = ConnectedAccountsAdapter()

    init {
        LayoutInflater.from(context).inflate(R.layout.user_profile_connections_view, this)
        mutualGuilds = requireNotNull(findViewById(R.id.user_profile_connections_mutual_guilds_item))
        mutualFriends = requireNotNull(findViewById(R.id.user_profile_connections_mutual_friends_item))
        recycler = requireNotNull(findViewById(R.id.user_profile_connections_view_recycler))
        recycler.adapter = adapter
        recycler.setHasFixedSize(false)
        recycler.isNestedScrollingEnabled = false
    }

    internal fun updateViewState(
        viewState: ViewState,
        onConnectedAccountClick: (String, Int, String?) -> Unit,
        onMutualGuildsItemClick: () -> Unit,
        onMutualFriendsItemClick: () -> Unit,
        theme: ProfileThemeColors? = null,
    ) {
        adapter.onClick = onConnectedAccountClick
        // Rows are inflated on demand, so they are never reached by the sheet's theme sweep.
        adapter.theme = theme
        adapter.items = viewState.connectedAccountItems
        mutualGuilds.setOnClickListener { onMutualGuildsItemClick() }
        mutualFriends.setOnClickListener { onMutualFriendsItemClick() }
        val visibility = if (viewState.showMutualGuildsAndFriends) VISIBLE else GONE
        mutualGuilds.visibility = visibility
        mutualFriends.visibility = visibility
    }

    private inner class ConnectedAccountsAdapter : RecyclerView.Adapter<AccountViewHolder>() {
        var items = emptyList<ConnectedAccountItem>()
            set(value) {
                field = value
                notifyDataSetChanged()
            }
        var onClick: (String, Int, String?) -> Unit = { _, _, _ -> }
        var theme: ProfileThemeColors? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = AccountViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.icon_list_item_text_view, parent, false) as TextView,
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: AccountViewHolder, position: Int) {
            val account = items[position].connectedAccount
            val (iconAttr, profileUrl) = platformInfo(account)
            val icon = Platform.from(account).resolvePlatformImage(context, theme?.isDark)
            holder.text.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0)
            holder.text.text = account.name
            holder.text.contentDescription = String.format("%s, %s", account.type, account.name)
            holder.text.setOnClickListener { onClick(account.name, iconAttr, profileUrl) }
            // The glyph is a brand mark and carries its own colours; only the label follows the
            // profile theme.
            holder.text.setTextColor(theme?.textDefault ?: holder.defaultTextColor)
        }
    }

    private class AccountViewHolder(val text: TextView) : RecyclerView.ViewHolder(text) {
        val defaultTextColor: Int = text.currentTextColor
    }

    private fun platformInfo(account: ConnectedAccount): Pair<Int, String?> {
        val platform = Platform.from(account)
        return (platform.themedPlatformImage ?: 0) to platform.getProfileUrl(account)
    }
}
