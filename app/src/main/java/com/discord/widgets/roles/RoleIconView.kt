package com.discord.widgets.roles

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.discord.R
import com.discord.api.guild.GuildFeature
import com.discord.api.role.GuildRole
import com.discord.i18n.i18nFormat
import com.discord.models.domain.emoji.toCodePoints
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.view.ToastManager
import com.facebook.drawee.view.SimpleDraweeView

/**
 * The small badge shown next to a name for a role that has an icon or a unicode emoji.
 *
 * [setRole] is the production entry point: it hides itself unless the guild is actually entitled
 * to role icons, and makes the badge tappable so the role behind it can be identified. The
 * `setRoleIconPreview` overloads skip both — they exist for the role editor, where the icon is
 * being chosen and no entitlement or guild context applies yet.
 */
class RoleIconView(
    context: Context,
    attrs: AttributeSet,
) : FrameLayout(context, attrs) {

    private val iconView: SimpleDraweeView

    init {
        LayoutInflater.from(context).inflate(R.layout.role_icon_view, this)
        iconView = requireNotNull(findViewById(R.id.role_icon_iv))
    }

    fun setRole(role: GuildRole?, guildId: Long?) {
        val guild = guildId?.let { StoreStream.getGuilds().getGuild(it) }
        if (role == null || !role.hasIconOrUnicodeEmoji() || guild == null || !guild.canUseRoleIcons(role)) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        setIcon(role)
        contentDescription = i18nFormat(R.string.role_icon_alt_text, "name" to role.name)
        setOnClickListener { showRoleIconToast(context, role) }
    }

    fun setRole(roleId: Long?, guildId: Long?) {
        val role = guildId?.let { StoreStream.getGuilds().getGuild(it) }?.roles?.firstOrNull { it.id == roleId }
        setRole(role, guildId)
    }

    fun setRoleIconPreview(guildRole: GuildRole?) {
        if (guildRole == null || !guildRole.hasIconOrUnicodeEmoji()) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        setIcon(guildRole)
    }

    fun setRoleIconPreview(icon: String?) {
        if (icon.isNullOrBlank()) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        IconUtils.setIcon(iconView, icon, R.dimen.role_icon_size)
    }

    private fun setIcon(guildRole: GuildRole) = when {
        guildRole.hasIcon() -> IconUtils.setIcon(iconView, guildRole, R.dimen.role_icon_size)
        // The reference leaves the emoji unsized; this project's `IconUtils` always resolves the
        // dimension, so the badge size is passed explicitly.
        guildRole.hasUnicodeEmoji() ->
            IconUtils.setIcon(iconView, emojiResourceUri(guildRole.unicodeEmoji!!), R.dimen.role_icon_size)
        else -> Unit
    }

    /**
     * Names the role behind the badge.
     *
     * The reference renders the icon itself into the toast through an `EmojiNode`; that node type
     * is not reconstructed, so the toast carries the name only. **Restore the leading icon once
     * `textprocessing/node/EmojiNode` exists.**
     */
    private fun showRoleIconToast(context: Context, role: GuildRole) {
        ToastManager().show(context, context.i18nFormat(R.string.role_icon_toast_message, "roleName" to role.name))
    }

    private fun GuildRole.hasIcon(): Boolean = !icon.isNullOrEmpty()

    private fun GuildRole.hasUnicodeEmoji(): Boolean = !unicodeEmoji.isNullOrEmpty()

    private fun GuildRole.hasIconOrUnicodeEmoji(): Boolean = hasIcon() || hasUnicodeEmoji()

    /**
     * Role icons are a boost perk, but a role sold through a role subscription always shows its
     * icon regardless of the guild's tier.
     *
     * Belongs in `utilities/guilds/RoleIconUtils`, which does not exist in this project yet.
     */
    private fun Guild.canUseRoleIcons(role: GuildRole?): Boolean =
        hasFeature(GuildFeature.ROLE_ICONS) || (role != null && RoleUtils.hasSubscriptionListingId(role))

    private fun emojiResourceUri(emoji: String): String? {
        val codePoints = emoji.toCodePoints()
        val resource = resources.getIdentifier("emoji_$codePoints", "raw", context.packageName)
        return resource.takeIf { it != 0 }?.let { "res:///$it" }
    }
}
