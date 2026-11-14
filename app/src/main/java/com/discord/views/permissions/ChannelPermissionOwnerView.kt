package com.discord.views.permissions

import com.discord.utilities.rest.ClientBackend
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.discord.R
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.channels.permissions.PermissionOwner
import com.discord.views.VitoImageView
import com.discord.utilities.user.UserUtils
import com.discord.utilities.user.UserUtils.getDisplayName
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.icon.IconUtils

class ChannelPermissionOwnerView(context: Context, attrs: AttributeSet) :
    ConstraintLayout(context, attrs) {
    private val crown: ImageView
    private val description: TextView
    private val image: VitoImageView
    private val name: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.channel_permission_owner_view, this)
        crown = requireNotNull(findViewById(R.id.crown))
        description = requireNotNull(findViewById(R.id.description))
        image = requireNotNull(findViewById(R.id.image))
        name = requireNotNull(findViewById(R.id.name))
    }

    /** What a member's avatar scales with; a role's shield swaps it out, so a recycled row restores it. */
    private val avatarScaleType = image.scaleType

    fun configure(permissionOwner: PermissionOwner) {
        when (permissionOwner) {
            is PermissionOwner.Role -> configureRole(permissionOwner)
            is PermissionOwner.Member -> configureMember(permissionOwner)
        }
    }

    private fun configureRole(owner: PermissionOwner.Role) {
        val role = owner.role
        val shield = requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_person_shield_24dp)).mutate()
        // Role colours carry no alpha byte; tinting with one as-is would be fully transparent.
        DrawableCompat.setTint(
            shield,
            role.color.takeIf { it != 0 }?.let { it or 0xFF000000.toInt() }
                ?: ColorCompat.getThemedColor(context, R.attr.colorInteractiveNormal),
        )
        // A placeholder only draws while an image loads, and a role has none to load, so the shield
        // is the image itself.
        image.release()
        // Inset in the avatar's box, as React Native draws it, rather than stretched to its edges.
        val inset = (SHIELD_INSET_DP * resources.displayMetrics.density).toInt()
        image.setPadding(inset, inset, inset, inset)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.setImageDrawable(shield)
        name.text = role.name
        description.setText(
            if (role.permissions and 8L != 0L) R.string.private_channel_add_members_modal_row_administrator
            else R.string.private_channel_add_members_modal_row_role,
        )
        crown.visibility = View.GONE
    }

    private fun configureMember(owner: PermissionOwner.Member) {
        val user = owner.user
        val url = user.avatar?.let {
            val extension = if (it.startsWith("a_")) "gif" else "webp"
            "${ClientBackend.active.cdn}/avatars/${user.id}/$it.$extension?size=32"
        } ?: IconUtils.getDefaultAvatar(IconUtils.getDefaultAvatarIndex(user.id, user.discriminator, user.isWebhook))
        image.scaleType = avatarScaleType
        image.setPadding(0, 0, 0, 0)
        image.hierarchy.setPlaceholderImage(ColorDrawable(0))
        image.setImageURI(url)
        name.text = user.getDisplayName(owner.nickname.takeUnless { FeatureFlags.disableServerProfilesEnabled() })
        description.text = user.username + UserUtils.discriminatorSuffix(user.discriminator)
        crown.visibility = if (owner.isOwner) View.VISIBLE else View.GONE
    }


    private companion object {
        /** A 40dp box less 4dp each side leaves the 32dp shield React Native shows. */
        const val SHIELD_INSET_DP = 4
    }
}
