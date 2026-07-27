package com.discord.widgets.guild_role_subscriptions.tier.create

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.api.role.GuildRole
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.views.ThemedColor
import com.discord.widgets.roles.RoleIconView
import com.facebook.drawee.view.SimpleDraweeView

class GuildRoleSubscriptionMemberPreview @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val name: TextView
    private val roleIcon: RoleIconView

    init {
        LayoutInflater.from(context).inflate(R.layout.widget_chat_list_adapter_item_text, this, true)
        val avatar = requireNotNull<SimpleDraweeView>(findViewById(R.id.chat_list_adapter_item_text_avatar))
        name = requireNotNull(findViewById(R.id.chat_list_adapter_item_text_name))
        roleIcon = requireNotNull(findViewById(R.id.chat_list_adapter_item_text_role_icon))
        requireNotNull<TextView>(findViewById(R.id.chat_list_adapter_item_text_tag)).visibility = View.GONE
        requireNotNull<TextView>(findViewById(R.id.chat_list_adapter_item_text_timestamp))
            .setText(R.string.sample_time_stamp)
        requireNotNull<LinkifiedTextView>(findViewById(R.id.chat_list_adapter_item_text))
            .setText(R.string.guild_role_subscription_tier_design_member_preview_placeholder_message)
        requireNotNull<ConstraintLayout>(findViewById(R.id.widget_chat_list_adapter_item_text_root))
            .setPadding(0, 0, 0, 0)
        avatar.setActualImageResource(R.drawable.img_guild_role_subscription_default_avatar_40dp)
        name.setText(R.string.wumpus)
        setBackgroundColor(ThemedColor.resolve(context, R.attr.colorBackgroundAccent))
        roleIcon.setRoleIconPreview(null as GuildRole?)
    }

    fun setMemberDesign(color: Int?, icon: String? = null, guildRole: GuildRole? = null) {
        name.setTextColor(color ?: ContextCompat.getColor(context, R.color.primary_300))
        if (icon.isNullOrBlank()) roleIcon.setRoleIconPreview(guildRole)
        else roleIcon.setRoleIconPreview(icon)
    }

}
