package com.discord.views.directories

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R
import com.discord.views.GuildView
import com.discord.views.guilds.ServerMemberCount
import com.google.android.material.button.MaterialButton

class ServerDiscoveryItem(context: Context, attrs: AttributeSet?) :
    ConstraintLayout(context, attrs, 0) {
    private val joinButton: MaterialButton
    private val joinedButton: MaterialButton
    private val memberCount: ServerMemberCount
    private val description: TextView
    private val image: GuildView
    private val title: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_server_discovery_item, this)
        requireNotNull<Barrier>(findViewById(R.id.button_barrier))
        requireNotNull<Barrier>(findViewById(R.id.server_discovery_item_barrier))
        joinButton = requireNotNull(findViewById(R.id.server_discovery_item_button))
        joinedButton = requireNotNull(findViewById(R.id.server_discovery_item_button_joined))
        memberCount = requireNotNull(findViewById(R.id.server_discovery_item_count_container))
        description = requireNotNull(findViewById(R.id.server_discovery_item_description))
        image = requireNotNull<GuildView>(findViewById(R.id.server_discovery_item_image))
            .apply { useGuildIconCorners() }
        title = requireNotNull(findViewById(R.id.server_discovery_item_title))
    }

    fun setTitle(title: CharSequence?) {
        this.title.text = title
    }

    /**
     * Shows the listed server's icon, falling back to [fallbackText] (its initials) when it has
     * none. The reference reaches the inner [GuildView] through its generated view binding; this
     * exposes it as behaviour instead so the view stays the only thing that knows its own layout.
     */
    fun setGuild(iconUrl: String?, fallbackText: String?) {
        image.configure(iconUrl, fallbackText)
    }

    fun setDescription(description: CharSequence?) {
        this.description.text = description
        this.description.visibility = if (description.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    fun setOnline(active: Int) {
        memberCount.setOnline(active)
    }

    fun setMembers(members: Int) {
        memberCount.setMembers(members)
    }

    fun setJoinButtonOnClickListener(listener: View.OnClickListener?) {
        joinButton.setOnClickListener(listener)
    }

    fun setJoinedButtonOnClickListener(listener: View.OnClickListener?) {
        joinedButton.setOnClickListener(listener)
    }

    fun setJoinedGuild(joinedGuild: Boolean) {
        joinButton.visibility = if (joinedGuild) View.GONE else View.VISIBLE
        joinedButton.visibility = if (joinedGuild) View.VISIBLE else View.GONE
    }
}
