package com.discord.widgets.user.presence

import android.view.View
import android.widget.Button
import android.widget.TextView
import com.discord.api.activity.Activity
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.streams.StreamContext
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.views.VitoImageView

/**
 * A plain game with rich presence.
 *
 * The title is the game, with the developer-supplied detail line below it.
 * Artwork and application-icon fallback are shared with the base card.
 */
class ViewHolderGameRichPresence(
    root: View,
    richPresenceImageLarge: VitoImageView,
    richPresenceImageSmall: VitoImageView,
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
}
