package com.discord.views.user

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R
import com.discord.models.guild.UserGuildMember
import com.discord.models.user.CoreUser
import com.discord.views.CutoutView
import com.facebook.drawee.view.SimpleDraweeView

class UserSummaryView constructor(
    context: Context,
    attrs: AttributeSet?,
) : ConstraintLayout(context, attrs) {
    private var avatarSizePx = (16 * resources.displayMetrics.density).toInt()
    private var overlapAmountPx = (4 * resources.displayMetrics.density).toInt()
    private var members: List<UserGuildMember> = emptyList()
    private var maxAvatars = 3

    init {
        val attributes = intArrayOf(
            R.attr.summary_avatarOverlap,
            R.attr.summary_avatarSize,
            R.attr.summary_maxAvatars,
        )
        context.obtainStyledAttributes(attrs, attributes).run {
            try {
                overlapAmountPx = getDimensionPixelSize(0, overlapAmountPx)
                avatarSizePx = getDimensionPixelSize(1, avatarSizePx)
                maxAvatars = getInt(2, maxAvatars)
            } finally {
                recycle()
            }
        }
    }

    fun configure(members: List<UserGuildMember>, withFullAvatarAtEnd: Boolean = true) {
        if (members == this.members) return
        this.members = members
        val count = minOf(members.size, maxAvatars)
        while (childCount > count) removeViewAt(childCount - 1)
        while (childCount < count) addAvatar(childCount)
        repeat(count) { index ->
            val cutout = getChildAt(index) as CutoutView
            cutout.style = if (index == count - 1 && withFullAvatarAtEnd) {
                CutoutView.None
            } else {
                CutoutView.EndOverlap(0)
            }
            val avatar = requireNotNull<SimpleDraweeView>(cutout.findViewById(R.id.avatar))
            val member = members[index]
            val url = avatarUrl(member.user, member.guildMember, mediaProxySize(avatarSizePx))
            if (avatar.tag != url) {
                avatar.tag = url
                setAvatar(
                    avatar,
                    url,
                    true,
                    resources.getDimensionPixelSize(R.dimen.avatar_size_profile_small),
                )
            }
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        if (isInEditMode) {
            configure(
                listOf(
                    UserGuildMember(CoreUser(0, "mreynolds")),
                    UserGuildMember(CoreUser(1, "itskaylee")),
                ),
            )
        }
    }

    private fun addAvatar(index: Int) {
        val cutout = LayoutInflater.from(context)
            .inflate(R.layout.view_user_summary_item, this, false) as CutoutView
        cutout.id = index + 10
        cutout.layoutParams = LayoutParams(avatarSizePx, avatarSizePx).apply {
            topToTop = LayoutParams.PARENT_ID
            bottomToBottom = LayoutParams.PARENT_ID
            marginStart = if (index == 0) 0 else avatarSizePx - overlapAmountPx
            startToStart = if (index == 0) LayoutParams.PARENT_ID else cutout.id - 1
        }
        addView(cutout)
    }
}
