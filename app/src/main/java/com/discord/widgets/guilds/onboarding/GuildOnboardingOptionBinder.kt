package com.discord.widgets.guilds.onboarding

import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import com.discord.R
import com.discord.api.guild.GuildOnboardingPromptOption
import com.discord.databinding.WidgetGuildOnboardingOptionBinding
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages

internal class GuildOnboardingOptionBinder(
    private val binding: WidgetGuildOnboardingOptionBinding,
) {
    private var boundOptionId: Long? = null
    private var boundSelected = false

    init {
        binding.root.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) view.animate().scaleX(0.985f).scaleY(0.985f)
            else if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f)
            } else return@setOnTouchListener false
            view.animate().setDuration(PRESS_ANIMATION_MS).start()
            false
        }
    }

    fun bind(
        option: GuildOnboardingPromptOption,
        selected: Boolean,
        description: CharSequence? = option.description,
        memberCount: Int = 0,
        allowAnimatedEmoji: Boolean = true,
        onClick: () -> Unit,
    ) {
        val animateSelection = boundOptionId == option.id && !boundSelected && selected
        val emoji = option.emoji
        if (emoji?.id == null) {
            MGImages.cancelImageRequests(binding.guildOnboardingOptionEmojiImage)
            binding.guildOnboardingOptionEmojiImage.visibility = View.GONE
            binding.guildOnboardingOptionEmojiText.visibility = View.VISIBLE
            binding.guildOnboardingOptionEmojiText.text = emoji?.name.orEmpty()
        } else {
            binding.guildOnboardingOptionEmojiText.visibility = View.GONE
            binding.guildOnboardingOptionEmojiImage.visibility = View.VISIBLE
            val animate = emoji.animated && allowAnimatedEmoji
            MGImages.setImage(
                binding.guildOnboardingOptionEmojiImage,
                IconUtils.getForEmoji(emoji.id, animate, EMOJI_SIZE_PX),
                autoPlayAnimations = animate,
            )
        }
        binding.guildOnboardingOptionTitle.text = option.title
        binding.guildOnboardingOptionDescription.text = description
        binding.guildOnboardingOptionDescription.visibility = if (description.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.guildOnboardingOptionMemberCount.visibility = if (selected && memberCount > 0) View.VISIBLE else View.GONE
        if (selected && memberCount > 0) {
            binding.guildOnboardingOptionMemberCount.text = binding.root.context.getString(
                R.string.guild_onboarding_people_in_common,
                memberCount,
            )
        }
        binding.root.setBackgroundResource(
            if (selected) R.drawable.bg_guild_onboarding_option_selected else R.drawable.bg_guild_onboarding_option,
        )
        binding.guildOnboardingOptionChecked.visibility = if (selected) View.VISIBLE else View.GONE
        binding.guildOnboardingOptionChecked.animate().cancel()
        binding.root.animate().cancel()
        if (animateSelection) {
            binding.guildOnboardingOptionChecked.apply {
                alpha = 0f
                scaleX = 0.65f
                scaleY = 0.65f
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(SELECTION_ANIMATION_MS)
                    .setInterpolator(OvershootInterpolator()).start()
            }
        } else {
            binding.guildOnboardingOptionChecked.alpha = 1f
            binding.guildOnboardingOptionChecked.scaleX = 1f
            binding.guildOnboardingOptionChecked.scaleY = 1f
        }
        binding.root.scaleX = 1f
        binding.root.scaleY = 1f
        binding.root.setOnClickListener { onClick() }
        boundOptionId = option.id
        boundSelected = selected
    }

    fun recycle() {
        boundOptionId = null
        boundSelected = false
        MGImages.cancelImageRequests(binding.guildOnboardingOptionEmojiImage)
        binding.guildOnboardingOptionEmojiImage.controller = null
    }

    private companion object {
        const val EMOJI_SIZE_PX = 64
        const val SELECTION_ANIMATION_MS = 180L
        const val PRESS_ANIMATION_MS = 90L
    }
}
