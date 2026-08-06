package com.discord.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.discord.databinding.MemberVerificationAvatarViewBinding

class MemberVerificationAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs, 0) {
    private val binding = MemberVerificationAvatarViewBinding.inflate(LayoutInflater.from(context), this)
    val avatar get() = binding.memberVerificationAvatar
    val text get() = binding.memberVerificationText

    init {
        clipToOutline = true
    }
}
