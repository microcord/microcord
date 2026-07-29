package com.discord.widgets.chat.input.applicationcommands

import android.graphics.PorterDuff
import com.discord.R
import com.discord.databinding.ViewAppcommandsOptiontitleListitemBinding
import com.discord.nullserializable.NullSerializable
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder

class SelectedAppCommandTitleAdapterItem(
    adapter: SelectedApplicationCommandAdapter,
) : MGRecyclerViewHolder<SelectedApplicationCommandAdapter, SelectedApplicationCommandItem>(
    R.layout.view_appcommands_optiontitle_listitem,
    adapter,
) {
    private val binding = ViewAppcommandsOptiontitleListitemBinding.bind(itemView)

    override fun onConfigure(position: Int, data: SelectedApplicationCommandItem) {
        super.onConfigure(position, data)
        binding.appcommandsOptiontitle.text = "/${data.title}"

        val avatar = binding.appcommandsOptiontitleAvatar
        avatar.setImageURI(null as String?)
        avatar.clearColorFilter()
        val application = data.application ?: return
        val bot = application.bot
        val botAvatar = (bot?.avatar as? NullSerializable.Value<String>)?.value
        val applicationIcon = application.icon?.takeIf { it.isNotBlank() }
        val iconRes = application.iconRes
        if (iconRes != null && botAvatar == null) {
            MGImages.setImage(avatar, iconRes)
            avatar.setColorFilter(
                ColorCompat.getThemedColor(avatar, R.attr.colorTextMuted),
                PorterDuff.Mode.SRC_ATOP,
            )
            return
        }
        val url = when {
            botAvatar != null -> IconUtils.getForUser(
                bot!!.id,
                botAvatar,
                bot.discriminator.toIntOrNull(),
            )
            applicationIcon != null -> IconUtils.getApplicationIcon(application.id, applicationIcon)
            else -> IconUtils.getDefaultAvatar((bot?.discriminator?.toIntOrNull() ?: 0) % 5)
        }
        MGImages.setImage(avatar, url)
    }
}
