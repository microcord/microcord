package com.discord.views.user

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R
import com.discord.models.guild.UserGuildMember
import com.discord.models.user.CoreUser
import com.discord.utilities.icon.IconUtils
import com.discord.views.CutoutView
import com.discord.views.VitoImageView

class UserSummaryView constructor(
    context: Context,
    attrs: AttributeSet?,
) : ConstraintLayout(context, attrs) {
    private var avatarSizePx = (16 * resources.displayMetrics.density).toInt()
    private var overlapAmountPx = (4 * resources.displayMetrics.density).toInt()
    private var members: List<UserGuildMember> = emptyList()
    private var maxAvatars = 3

    init {
        context.obtainStyledAttributes(attrs, R.styleable.UserSummaryView).run {
            try {
                overlapAmountPx = getDimensionPixelSize(
                    R.styleable.UserSummaryView_summary_avatarOverlap,
                    overlapAmountPx,
                )
                avatarSizePx = getDimensionPixelSize(
                    R.styleable.UserSummaryView_summary_avatarSize,
                    avatarSizePx,
                )
                maxAvatars = getInt(R.styleable.UserSummaryView_summary_maxAvatars, maxAvatars)
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
            val avatar = requireNotNull<VitoImageView>(cutout.findViewById(R.id.avatar))
            val member = members[index]
            val url = avatarUrl(
                member.user,
                member.guildMember,
                IconUtils.getMediaProxySize(avatarSizePx),
                animate = false,
            )
            // Recycled rows are rebound constantly; only re-issue the request when the
            // avatar actually differs.
            if (avatar.tag != url) {
                avatar.tag = url
                IconUtils.setIcon(avatar, url, R.dimen.avatar_size_reply)
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
