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
import com.discord.api.role.GuildRole
import com.google.android.material.chip.ChipGroup

class RolesListView(
    context: Context,
    attrs: AttributeSet,
) : ChipGroup(context, attrs) {
    var roles: List<GuildRole> = emptyList()

    init {
        val spacing = (4 * resources.displayMetrics.density).toInt()
        chipSpacingVertical = spacing
        chipSpacingHorizontal = spacing
    }

    fun updateView(roles: List<GuildRole>, roleDefaultColor: Int, guildId: Long) {
        if (roles == this.roles) return

        val developerModeEnabled = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(DEVELOPER_MODE_KEY, false)
        this.roles = roles
        removeAllViews()

        roles.forEach { role ->
            val chip = LayoutInflater.from(context).inflate(R.layout.guild_role_chip, null, false)
            chip.findViewById<TextView>(R.id.role_chip_text).text = role.name
            ImageViewCompat.setImageTintList(
                chip.findViewById<ImageView>(R.id.role_chip_dot),
                ColorStateList.valueOf(roleChipColor(role.color, roleDefaultColor)),
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
    }
}

internal fun roleChipColor(roleColor: Int, defaultColor: Int): Int =
    if (roleColor == 0) defaultColor else 0xff000000.toInt() + roleColor
