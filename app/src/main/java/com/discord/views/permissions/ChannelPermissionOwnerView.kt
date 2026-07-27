package com.discord.views.permissions

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.net.Uri
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
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.drawee.view.SimpleDraweeView
import com.facebook.imagepipeline.request.ImageRequestBuilder
import java.util.Locale

class ChannelPermissionOwnerView(context: Context, attrs: AttributeSet) :
    ConstraintLayout(context, attrs) {
    private val crown: ImageView
    private val description: TextView
    private val image: SimpleDraweeView
    private val name: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.channel_permission_owner_view, this)
        crown = requireNotNull(findViewById(R.id.crown))
        description = requireNotNull(findViewById(R.id.description))
        image = requireNotNull(findViewById(R.id.image))
        name = requireNotNull(findViewById(R.id.name))
    }

    fun configure(permissionOwner: PermissionOwner) {
        when (permissionOwner) {
            is PermissionOwner.Role -> configureRole(permissionOwner)
            is PermissionOwner.Member -> configureMember(permissionOwner)
        }
    }

    private fun configureRole(owner: PermissionOwner.Role) {
        val role = owner.role
        val shield = requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_person_shield_24dp)).mutate()
        DrawableCompat.setTint(shield, role.color.takeIf { it != 0 } ?: ColorCompat.getThemedColor(context, R.attr.colorInteractiveNormal))
        image.controller = null
        image.hierarchy.setPlaceholderImage(shield)
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
            "https://cdn.discordapp.com/avatars/${user.id}/$it.$extension?size=32"
        } ?: "asset://asset/images/default_avatar_${user.discriminator % 5}.png"
        image.hierarchy.setPlaceholderImage(ColorDrawable(0))
        image.controller = Fresco.newDraweeControllerBuilder()
            .setOldController(image.controller)
            .setImageRequest(ImageRequestBuilder.newBuilderWithSource(Uri.parse(url)).build())
            .setAutoPlayAnimations(true)
            .build()
        name.text = owner.nickname ?: user.username
        description.text = if (user.discriminator == 0) {
            user.username
        } else {
            String.format(Locale.ROOT, "%s#%04d", user.username, user.discriminator)
        }
        crown.visibility = if (owner.isOwner) View.VISIBLE else View.GONE
    }

}
