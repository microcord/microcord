package com.discord.widgets.user.presence

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.activity.Activity
import com.discord.i18n.i18nFormat
import com.discord.models.user.User
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.presence.isGameActivity
import com.discord.utilities.streams.StreamContext
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.views.StreamPreviewView
import com.facebook.drawee.view.SimpleDraweeView

/**
 * Somebody's Go Live stream, with a live thumbnail and — when they are also playing something — the
 * game underneath it.
 *
 * The card hides itself entirely when the viewer lacks permission to join the stream's channel:
 * advertising a stream nobody can watch is worse than showing nothing.
 */
class ViewHolderStreamRichPresence(
    root: View,
    richPresenceImageLarge: SimpleDraweeView,
    richPresenceHeader: TextView,
    richPresenceTitle: TextView,
    richPresenceDetails: TextView,
    richPresenceTime: TextView,
    richPresenceState: TextView,
    richPresenceTextContainer: View,
    private val streamPreview: StreamPreviewView,
) : ViewHolderUserRichPresence(
    root = root,
    richPresenceImageLarge = richPresenceImageLarge,
    richPresenceImageSmall = null,
    richPresenceHeader = richPresenceHeader,
    richPresenceTitle = richPresenceTitle,
    richPresenceDetails = richPresenceDetails,
    richPresenceTime = richPresenceTime,
    richPresenceState = richPresenceState,
    richPresenceTextContainer = richPresenceTextContainer,
    richPresencePrimaryButton = null,
    richPresenceSecondaryButton = null,
    richPresenceType = STREAM_RICH_PRESENCE_TYPE,
) {

    fun setOnStreamPreviewClicked(onStreamPreviewClicked: () -> Unit) {
        streamPreview.setOnClickListener { onStreamPreviewClicked() }
    }

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
        val playingActivity = richPresence?.presence?.let {
            with(PresenceUtils) { it.playingActivity }
        }
        val cannotJoin = streamContext?.joinability == StreamContext.Joinability.MISSING_PERMISSIONS
        if ((streamContext == null && playingActivity == null) || cannotJoin) {
            root.visibility = View.GONE
            return
        }
        root.visibility = View.VISIBLE
        configureTextUi(playingActivity, streamContext)
        configureAssetUi(playingActivity, streamContext)
        configureStreamPreview(streamContext)
    }

    /**
     * The header names the stream's destination; the rest of the card describes the game, and
     * collapses when the streamer is not playing anything recognisable.
     */
    override fun configureTextUi(model: Activity?, streamContext: StreamContext?) {
        // Only reachable with a stream — this holder is chosen by the presence of a stream context.
        require(streamContext != null) { "streamContext must not be null" }
        val guildName = streamContext.guild?.name
        richPresenceHeader.text = if (guildName != null) {
            richPresenceHeader.i18nFormat(R.string.user_activity_header_streaming_to_guild, "server" to guildName)
        } else {
            richPresenceHeader.context.getString(R.string.user_activity_header_streaming_to_dm)
        }

        if (model == null || !model.isGameActivity()) {
            richPresenceTextContainer.visibility = View.GONE
            return
        }
        richPresenceTextContainer.visibility = View.VISIBLE
        richPresenceTitle.text = model.name
        richPresenceDetails?.setTextAndVisibilityBy(model.details)
        richPresenceState?.setTextAndVisibilityBy(stateWithPartySize(model))
        richPresenceTime.setTextAndVisibilityBy(model.friendlyTime())
    }

    /** The stream thumbnail replaces the game's large artwork. */
    override fun configureAssetUi(model: Activity?, streamContext: StreamContext?) {
        super.configureAssetUi(model, streamContext)
        richPresenceImageLarge.visibility = View.GONE
    }

    private fun configureStreamPreview(streamContext: StreamContext?) {
        val preview = streamContext?.preview
        if (preview == null) {
            streamPreview.visibility = View.GONE
            return
        }
        streamPreview.configure(
            preview,
            streamContext.joinability,
            streamContext.isCurrentUserParticipating,
        )
        streamPreview.visibility = View.VISIBLE
    }
}
