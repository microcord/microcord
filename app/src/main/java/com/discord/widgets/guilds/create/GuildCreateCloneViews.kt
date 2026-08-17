package com.discord.widgets.guilds.create

import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.Group
import com.discord.databinding.WidgetGuildCloneBinding
import com.discord.databinding.WidgetGuildCreateBinding
import com.discord.views.LoadingButton
import com.discord.views.ScreenTitleView
import com.discord.views.VitoImageView
import com.google.android.material.textfield.TextInputLayout

interface GuildCreateCloneViews {
    val guidelinesTextView: TextView
    val guildCreateButton: LoadingButton
    val guildCreateIconUploader: ViewGroup
    val guildCreateName: TextInputLayout
    val iconUploaderImage: VitoImageView
    val iconUploaderPlaceholderGroup: Group
    val screenTitleView: ScreenTitleView?

    companion object {
        fun from(binding: WidgetGuildCloneBinding): GuildCreateCloneViews = object : GuildCreateCloneViews {
            override val guidelinesTextView = binding.guildCreateGuidelines
            override val guildCreateButton = binding.guildCreateButton
            override val guildCreateIconUploader = binding.guildCreateIconUploader.root
            override val guildCreateName = binding.guildCreateName
            override val iconUploaderImage = binding.guildCreateIconUploader.iconUploaderImage
            override val iconUploaderPlaceholderGroup = binding.guildCreateIconUploader.placeholderGroup
            override val screenTitleView: ScreenTitleView? = null
        }

        fun from(binding: WidgetGuildCreateBinding): GuildCreateCloneViews = object : GuildCreateCloneViews {
            override val guidelinesTextView = binding.guildCreateGuidelines
            override val guildCreateButton = binding.guildCreateButton
            override val guildCreateIconUploader = binding.guildCreateIconUploader.root
            override val guildCreateName = binding.guildCreateName
            override val iconUploaderImage = binding.guildCreateIconUploader.iconUploaderImage
            override val iconUploaderPlaceholderGroup = binding.guildCreateIconUploader.placeholderGroup
            override val screenTitleView = binding.guildCreateScreenTitle
        }
    }
}
