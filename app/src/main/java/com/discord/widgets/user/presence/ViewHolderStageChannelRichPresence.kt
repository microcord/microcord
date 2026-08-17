package com.discord.widgets.user.presence

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.activity.Activity
import com.discord.i18n.i18nFormat
import com.discord.models.guild.Guild
import com.discord.models.user.User
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.presence.stageChannelRichPresencePartyData
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.streams.StreamContext
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.views.VitoImageView

/**
 * A stage the user is listening to.
 *
 * Unlike the other cards this one is actionable for a stranger: the presence carries enough about
 * the stage — guild, channel, instance — to let a viewer lurk-join it straight from the profile.
 * The join button is therefore offered to anyone who is neither already in the channel nor the
 * speaker themselves.
 *
 * @param onJoinStage invoked with `(context, fragmentManager, guildId, channelId)` when the join
 *   button is tapped. The reference calls `StageChannelJoinHelper.lurkAndJoinStage`; `widgets/stage`
 *   has not been reconstructed, so the default leaves the button inert rather than hiding it —
 *   hiding it would misreport the stage as unjoinable.
 */
class ViewHolderStageChannelRichPresence(
    root: View,
    richPresenceImageLarge: VitoImageView,
    richPresenceHeader: TextView,
    richPresenceTitle: TextView,
    richPresenceDetails: TextView,
    richPresenceTime: TextView,
    richPresenceTextContainer: View,
    richPresencePrimaryButton: Button,
    val richPresenceImageLargeText: TextView,
    private val onJoinStage: ((Context, FragmentManager, Long, Long) -> Unit)? = null,
) : ViewHolderUserRichPresence(
    root = root,
    richPresenceImageLarge = richPresenceImageLarge,
    richPresenceImageSmall = null,
    richPresenceHeader = richPresenceHeader,
    richPresenceTitle = richPresenceTitle,
    richPresenceDetails = richPresenceDetails,
    richPresenceTime = richPresenceTime,
    richPresenceState = null,
    richPresenceTextContainer = richPresenceTextContainer,
    richPresencePrimaryButton = richPresencePrimaryButton,
    richPresenceSecondaryButton = null,
    richPresenceType = STAGE_CHANNEL_RICH_PRESENCE_TYPE,
) {

    @MainThread
    override fun configureUi(
        fragmentManager: FragmentManager,
        streamContext: StreamContext?,
        isMe: Boolean,
        user: User?,
        applicationContext: Context,
        richPresence: ModelRichPresence?,
        userInSameVoiceChannel: Boolean,
    ) {
        disposeTimer()
        val activity = richPresence?.presence?.let { with(PresenceUtils) { it.stageChannelActivity } }
        if (activity == null) {
            root.visibility = View.GONE
            return
        }
        root.visibility = View.VISIBLE
        configureTextUi(activity, streamContext)
        configureAssetUi(activity, streamContext)
        configureButtonUi(fragmentManager, activity, isMe, userInSameVoiceChannel)
    }

    /** Title is the stage's topic; the detail line names the guild it is hosted in. */
    @MainThread
    override fun configureTextUi(model: Activity?, streamContext: StreamContext?) {
        require(model != null) { "model must not be null" }
        richPresenceTitle.text = model.name
        richPresenceTime.setTextAndVisibilityBy(model.friendlyTime())

        val guildName = model.assets?.smallText
        richPresenceDetails?.visibility = if (guildName != null) View.VISIBLE else View.GONE
        if (guildName != null) {
            richPresenceDetails?.text = richPresenceDetails?.i18nFormat(
                R.string.stage_discovery_origin_text,
                "origin" to guildName,
            )
        }
    }

    /**
     * The guild's icon, falling back to its initials when it has none.
     *
     * The stage broadcast packs the guild into the activity's *small* asset slot, so this rebuilds
     * just enough of a [Guild] to render it. A presence with no guild at all is not a stage we can
     * describe, and the whole card is withdrawn.
     */
    @MainThread
    override fun configureAssetUi(model: Activity?, streamContext: StreamContext?) {
        require(model != null) { "model must not be null" }
        richPresenceImageLarge.visibility = View.GONE

        val party = model.stageChannelRichPresencePartyData
        val guildName = model.assets?.smallText
        if (party == null || guildName == null) {
            root.visibility = View.GONE
            return
        }

        val guild = Guild(name = guildName, id = party.guildId, icon = model.assets?.smallImage)
        val hasIcon = guild.hasIcon()
        richPresenceImageLarge.visibility = if (hasIcon) View.VISIBLE else View.GONE
        richPresenceImageLargeText.visibility = if (hasIcon) View.GONE else View.VISIBLE

        if (!hasIcon) {
            richPresenceImageLargeText.text = guild.shortName
            return
        }
        val resources = richPresenceImageLarge.resources
        MGImages.setImage(
            richPresenceImageLarge,
            IconUtils.getForGuild(
                guild = guild,
                allowAnimation = true,
                size = IconUtils.getMediaProxySize(resources.getDimensionPixelSize(R.dimen.avatar_size_large)),
            ),
        )
        MGImages.setRoundingParams(
            view = richPresenceImageLarge,
            cornerRadius = resources.getDimensionPixelSize(R.dimen.guild_icon_radius).toFloat(),
            circle = false,
        )
    }

    /**
     * The join button, offered only to a viewer who could actually act on it: not the speaker whose
     * presence this is, and not somebody already sitting in the channel.
     */
    private fun configureButtonUi(
        fragmentManager: FragmentManager,
        model: Activity?,
        isMe: Boolean,
        userInSameVoiceChannel: Boolean,
    ) {
        val party = model?.stageChannelRichPresencePartyData
        if (party == null) {
            richPresencePrimaryButton?.visibility = View.GONE
            return
        }
        val canJoin = !isMe && !userInSameVoiceChannel
        richPresencePrimaryButton?.visibility = if (canJoin) View.VISIBLE else View.GONE
        richPresencePrimaryButton?.setOnClickListener {
            onJoinStage?.invoke(it.context, fragmentManager, party.guildId, party.channelId)
        }
    }
}
