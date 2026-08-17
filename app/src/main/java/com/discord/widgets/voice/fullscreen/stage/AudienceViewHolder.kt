package com.discord.widgets.voice.fullscreen.stage

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.utilities.icon.IconUtils
import com.discord.views.CutoutView
import com.discord.views.VitoImageView

/**
 * One listener in the audience roll.
 *
 * A raised hand punches a cutout out of the avatar so the hand badge can sit flush against it, and
 * turns "activated" once a moderator has invited that listener up to speak. Blocked and boosting
 * listeners get a leading / trailing badge on the name.
 */
class AudienceViewHolder(adapter: StageCallAdapter) :
    StageCallViewHolder(R.layout.widget_stage_channel_audience, adapter) {

    private val iconContainer: CutoutView =
        requireNotNull(itemView.findViewById(R.id.stage_channel_audience_member_icon_container))
    private val icon: VitoImageView =
        requireNotNull(itemView.findViewById(R.id.stage_channel_audience_member_icon))
    private val raisedHand: ImageView =
        requireNotNull(itemView.findViewById(R.id.stage_channel_audience_member_raised_hand))
    private val name: TextView =
        requireNotNull(itemView.findViewById(R.id.stage_channel_audience_member_name))

    override fun onConfigure(position: Int, data: StageCallItem) {
        super.onConfigure(position, data)
        val item = data as StageCallItem.AudienceItem
        val voiceUser = item.voiceUser

        iconContainer.isCutoutEnabled = voiceUser.isRequestingToSpeak
        raisedHand.visibility = if (voiceUser.isRequestingToSpeak) View.VISIBLE else View.GONE
        raisedHand.isActivated = voiceUser.isInvitedToSpeak

        name.text = voiceUser.displayName
        val blockedBadge = if (item.isBlocked) {
            ContextCompat.getDrawable(name.context, R.drawable.ic_blocked_12dp)?.apply {
                setTint(ContextCompat.getColor(name.context, R.color.status_red_500))
            }
        } else {
            null
        }
        val boosterBadge = if (voiceUser.isBooster) {
            ContextCompat.getDrawable(name.context, R.drawable.ic_boosted_badge_12dp)
        } else {
            null
        }
        name.setCompoundDrawablesRelativeWithIntrinsicBounds(blockedBadge, null, boosterBadge, null)

        val user = voiceUser.user
        IconUtils.setIcon(
            imageView = icon,
            username = user.username,
            userId = user.id,
            userAvatar = user.avatar,
            discriminator = user.discriminator,
            sizeDimenRes = R.dimen.avatar_size_large,
            guildMember = voiceUser.guildMember,
        )
    }
}
