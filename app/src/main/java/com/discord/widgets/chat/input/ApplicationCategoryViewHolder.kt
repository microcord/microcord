package com.discord.widgets.chat.input

import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.databinding.CommandCategoryItemBinding
import com.discord.i18n.i18nFormat
import com.discord.models.commands.Application
import com.discord.models.commands.hasBotAvatar
import com.discord.models.user.CoreUser
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.color.tintWithColor
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages

class ApplicationCategoryViewHolder(
    private val binding: CommandCategoryItemBinding,
    private val onItemSelected: (Application) -> Unit,
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(application: Application, isSelected: Boolean) {
        binding.commandCategoryItemIcon.setImageURI(null as String?)
        val iconRes = application.iconRes
        if (iconRes != null && !application.hasBotAvatar()) {
            val drawable = AppCompatResources.getDrawable(itemView.context, iconRes)?.mutate()
            if (drawable != null) {
                DrawableCompat.setTint(
                    drawable,
                    ColorCompat.getThemedColor(binding.commandCategoryItemIcon, R.attr.colorTextMuted),
                )
                MGImages.setImage(binding.commandCategoryItemIcon, drawable)
            } else {
                MGImages.setImage(binding.commandCategoryItemIcon, iconRes)
            }
        } else {
            val bot = application.bot
            val icon = when {
                application.hasBotAvatar() -> IconUtils.getForUser(bot?.let(::CoreUser))
                !application.icon.isNullOrBlank() -> IconUtils.getApplicationIcon(application.id, application.icon)
                else -> IconUtils.getDefaultAvatar(
                    IconUtils.getDefaultAvatarIndex(bot?.id, bot?.discriminator?.toIntOrNull()),
                )
            }
            MGImages.setImage(binding.commandCategoryItemIcon, icon)
        }
        binding.commandCategoryItemIcon.isSelected = isSelected
        binding.overline.expressionPickerCategorySelectionOverline.visibility =
            if (isSelected) View.VISIBLE else View.GONE
        binding.commandCategoryItemIcon.tintWithColor(
            ColorCompat.getThemedColor(
                binding.commandCategoryItemIcon,
                if (isSelected) R.attr.colorInteractiveActive else R.attr.colorInteractiveNormal,
            ),
        )
        binding.commandCategoryItemIcon.alpha = if (isSelected) 1f else 0.5f
        itemView.setOnClickListener { onItemSelected(application) }
        itemView.contentDescription = itemView.i18nFormat(
            R.string.command_accessibility_desc_app_item,
            "applicationName" to application.name,
        )
    }
}
