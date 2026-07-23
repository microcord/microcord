package com.discord.views.guilds

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.discord.R

class ServerMemberCount(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs, 0) {
    private val onlineDot: ImageView
    private val onlineText: TextView
    private val totalDot: ImageView
    private val totalText: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_server_member_count, this)
        onlineDot = requireNotNull(findViewById(R.id.item_invite_online_dot))
        onlineText = requireNotNull(findViewById(R.id.item_invite_online_text))
        totalDot = requireNotNull(findViewById(R.id.item_invite_total_member_dot))
        totalText = requireNotNull(findViewById(R.id.item_invite_total_member_text))
    }

    fun setInvalidText(text: CharSequence?) {
        onlineDot.visibility = View.GONE
        totalDot.visibility = View.GONE
        onlineText.text = text
        onlineText.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        totalText.text = null
        totalText.visibility = View.GONE
    }

    fun setOnline(active: Int?) {
        onlineDot.visibility = if (active == null) View.GONE else View.VISIBLE
        onlineText.text = active?.let { context.getString(R.string.instant_invite_guild_members_online, it) }
        onlineText.visibility = if (active == null) View.GONE else View.VISIBLE
    }

    fun setMembers(members: Int?) {
        totalDot.visibility = if (members == null) View.GONE else View.VISIBLE
        totalText.text = members?.let {
            resources.getQuantityString(R.plurals.members_header_members, it, it)
        }
        totalText.visibility = if (members == null) View.GONE else View.VISIBLE
    }
}
