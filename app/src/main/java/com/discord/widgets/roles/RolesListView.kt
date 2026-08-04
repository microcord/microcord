package com.discord.widgets.roles

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.core.widget.ImageViewCompat
import androidx.preference.PreferenceManager
import com.discord.R
import androidx.cardview.widget.CardView
import com.discord.api.role.GuildRole
import com.discord.widgets.user.profile.ProfileThemeColors
import com.google.android.material.chip.ChipGroup

class RolesListView(
    context: Context,
    attrs: AttributeSet,
) : ChipGroup(context, attrs) {
    var roles: List<GuildRole> = emptyList()
    private var appliedTheme: ProfileThemeColors? = null

    init {
        val spacing = (8 * resources.displayMetrics.density).toInt()
        chipSpacingVertical = spacing
        chipSpacingHorizontal = spacing
    }

    /**
     * @param theme colours of the profile whose roles these are; null keeps the app theme.
     */
    internal fun updateView(
        roles: List<GuildRole>,
        guildId: Long,
        theme: ProfileThemeColors? = null,
    ) {
        if (roles == this.roles && theme == appliedTheme) return

        val developerModeEnabled = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(DEVELOPER_MODE_KEY, false)
        this.roles = roles
        appliedTheme = theme
        removeAllViews()

        roles.forEach { role ->
            val chip = LayoutInflater.from(context).inflate(R.layout.guild_role_chip, this, false)
            chip.findViewById<TextView>(R.id.role_chip_text).apply {
                text = truncateRoleName(role.name)
                theme?.let { setTextColor(it.textDefault) }
            }
            theme?.let { (chip as CardView).setCardBackgroundColor(it.rolePillSurface) }
            ImageViewCompat.setImageTintList(
                chip.findViewById<ImageView>(R.id.role_chip_dot),
                ColorStateList.valueOf(roleChipColor(role.color, DEFAULT_ROLE_COLOR)),
            )
            chip.findViewById<RoleIconView>(R.id.role_chip_icon).setRole(role, guildId)
            if (developerModeEnabled) {
                chip.setOnClickListener { copyRoleId(role) }
            }
            addView(chip)
        }
    }

    private fun copyRoleId(role: GuildRole) {
        context.getSystemService<ClipboardManager>()
            ?.setPrimaryClip(ClipData.newPlainText(null, role.id.toString()))
        Toast.makeText(
            context,
            context.getString(R.string.role_id_copied).replace("{role}", role.name),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private companion object {
        const val DEVELOPER_MODE_KEY = "CACHE_KEY_DEVELOPER_MODE"

        /** `Constants.DEFAULT_ROLE_COLOR`, used for a role that has no colour of its own. */
        const val DEFAULT_ROLE_COLOR = 0xFF99AAB5.toInt()
        const val MAX_VISUAL_ROLE_LENGTH = 30

        fun truncateRoleName(name: String): String =
            if (name.length <= MAX_VISUAL_ROLE_LENGTH) name
            else name.take(MAX_VISUAL_ROLE_LENGTH) + "..."
    }
}

internal fun roleChipColor(roleColor: Int, defaultColor: Int): Int =
    if (roleColor == 0) defaultColor else 0xff000000.toInt() + roleColor
