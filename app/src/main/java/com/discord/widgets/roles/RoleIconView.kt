package com.discord.widgets.roles

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.discord.R
import com.discord.api.role.GuildRole
import com.facebook.drawee.view.SimpleDraweeView

class RoleIconView(
    context: Context,
    attrs: AttributeSet,
) : FrameLayout(context, attrs) {
    private val iconView: SimpleDraweeView

    init {
        LayoutInflater.from(context).inflate(R.layout.role_icon_view, this)
        iconView = requireNotNull(findViewById(R.id.role_icon_iv))
    }

    fun setRoleIconPreview(guildRole: GuildRole?) {
        val uri = when {
            !guildRole?.icon.isNullOrEmpty() ->
                "https://cdn.discordapp.com/role-icons/${guildRole!!.id}/${guildRole.icon}.webp"
            !guildRole?.unicodeEmoji.isNullOrEmpty() -> emojiResourceUri(guildRole!!.unicodeEmoji!!)
            else -> null
        }
        show(uri)
    }

    fun setRoleIconPreview(icon: String?) = show(icon?.takeUnless(String::isBlank))

    private fun show(uri: String?) {
        visibility = if (uri == null) View.GONE else View.VISIBLE
        iconView.setImageURI(uri)
    }

    private fun emojiResourceUri(emoji: String): String? {
        val codePoints = emoji.codePoints()
            .toArray()
            .joinToString("_") { it.toString(16) }
        val resource = resources.getIdentifier("emoji_$codePoints", "raw", context.packageName)
        return resource.takeIf { it != 0 }?.let { "res:///$it" }
    }
}
