package com.discord.widgets.user.presence

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.fragment.app.FragmentManager
import com.discord.api.activity.Activity
import com.discord.models.user.User
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.streams.StreamContext
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.facebook.drawee.view.SimpleDraweeView

/**
 * A plain game with rich presence.
 *
 * Differs from the base card in two ways: the title is the *game*, with the developer-supplied
 * detail line demoted below it (the base card leads with the detail line); and when the game
 * supplied no artwork at all, the application's own icon is used rather than leaving a hole.
 */
class ViewHolderGameRichPresence(
    root: View,
    richPresenceImageLarge: SimpleDraweeView,
    richPresenceImageSmall: SimpleDraweeView,
    richPresenceHeader: TextView,
    richPresenceTitle: TextView,
    richPresenceDetails: TextView,
    richPresenceTime: TextView,
    richPresenceState: TextView,
    richPresenceTextContainer: View,
    richPresencePrimaryButton: Button,
    richPresenceSecondaryButton: Button,
) : ViewHolderUserRichPresence(
    root = root,
    richPresenceImageLarge = richPresenceImageLarge,
    richPresenceImageSmall = richPresenceImageSmall,
    richPresenceHeader = richPresenceHeader,
    richPresenceTitle = richPresenceTitle,
    richPresenceDetails = richPresenceDetails,
    richPresenceTime = richPresenceTime,
    richPresenceState = richPresenceState,
    richPresenceTextContainer = richPresenceTextContainer,
    richPresencePrimaryButton = richPresencePrimaryButton,
    richPresenceSecondaryButton = richPresenceSecondaryButton,
    richPresenceType = GAME_RICH_PRESENCE_TYPE,
) {

    override fun configureTextUi(model: Activity?, streamContext: StreamContext?) {
        require(model != null) { "model must not be null" }
        richPresenceHeader.text = PresenceUtils.getActivityHeader(richPresenceHeader.context, model)
        richPresenceTitle.text = model.name
        richPresenceDetails?.setTextAndVisibilityBy(model.details)
        richPresenceState?.setTextAndVisibilityBy(stateWithPartySize(model))
        richPresenceTime.setTextAndVisibilityBy(model.friendlyTime())
    }

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
        super.configureUi(
            fragmentManager,
            streamContext,
            isMe,
            user,
            applicationContext,
            richPresence,
            userInSameVoiceChannel,
        )
        if (richPresenceImageLarge.visibility == View.VISIBLE) return
        // No presence artwork: fall back to the application icon so the card is never blank.
        val application = richPresence?.primaryApplication
        val iconUrl = application?.let {
            IconUtils.getApplicationIcon(it.id, it.icon.orEmpty())
        }
        richPresenceImageLarge.visibility = View.VISIBLE
        MGImages.setImage(richPresenceImageLarge, iconUrl)
    }
}
