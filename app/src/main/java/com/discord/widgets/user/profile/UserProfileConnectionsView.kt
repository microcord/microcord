package com.discord.widgets.user.profile

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.connectedaccounts.ConnectedAccount
import java.util.Locale

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

    fun updateViewState(
        viewState: ViewState,
        onConnectedAccountClick: (String, Int, String?) -> Unit,
        onMutualGuildsItemClick: () -> Unit,
        onMutualFriendsItemClick: () -> Unit,
    ) {
        adapter.onClick = onConnectedAccountClick
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

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = AccountViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.icon_list_item_text_view, parent, false) as TextView,
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: AccountViewHolder, position: Int) {
            val account = items[position].connectedAccount
            val (iconAttr, profileUrl) = platformInfo(account)
            val icon = TypedValue().also { context.theme.resolveAttribute(iconAttr, it, true) }.resourceId
            holder.text.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0)
            holder.text.text = account.name
            holder.text.contentDescription = String.format("%s, %s", account.type, account.name)
            holder.text.setOnClickListener { onClick(account.name, iconAttr, profileUrl) }
        }
    }

    private class AccountViewHolder(val text: TextView) : RecyclerView.ViewHolder(text)

    private fun platformInfo(account: ConnectedAccount): Pair<Int, String?> = when (account.type.lowercase(Locale.ENGLISH)) {
        "battlenet" -> R.attr.ic_account_battlenet to null
        "facebook" -> R.attr.ic_account_facebook to null
        "github" -> R.attr.ic_account_github to "https://github.com/${account.name}"
        "leagueoflegends" -> R.attr.ic_account_league_of_legends to null
        "playstation" -> R.attr.ic_account_playstation to null
        "reddit" -> R.attr.ic_account_reddit to "https://reddit.com/u/${account.name}"
        "samsung" -> R.attr.ic_account_samsung to null
        "skype" -> R.attr.ic_account_skype to null
        "spotify" -> R.attr.ic_account_spotify to "https://open.spotify.com/user/${account.id}"
        "steam" -> R.attr.ic_account_steam to "https://steamcommunity.com/profiles/${account.id}"
        "twitch" -> R.attr.ic_account_twitch to "https://twitch.tv/${account.name}"
        "twitter" -> R.attr.ic_account_twitter to "https://www.twitter.com/${account.name}"
        "xbox" -> R.attr.ic_account_xbox to null
        "youtube" -> R.attr.ic_account_youtube to "https://youtube.com/channel/${account.id}"
        else -> R.attr.ic_account to null
    }
}
