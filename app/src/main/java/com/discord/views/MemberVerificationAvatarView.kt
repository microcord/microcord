package com.discord.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.discord.R

class MemberVerificationAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs, 0) {
    init {
        LayoutInflater.from(context).inflate(R.layout.member_verification_avatar_view, this)
        requireNotNull<android.view.View>(findViewById(R.id.member_verification_avatar))
        requireNotNull<android.view.View>(findViewById(R.id.member_verification_text))
        clipToOutline = true
    }
}
