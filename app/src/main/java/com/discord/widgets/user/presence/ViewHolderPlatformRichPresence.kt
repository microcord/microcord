package com.discord.widgets.user.presence

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.activity.Activity
import com.discord.i18n.i18nFormat
import com.discord.models.user.User
import com.discord.stores.StoreUserConnections
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.platform.Platform
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.presence.isGamePlatform
import com.discord.utilities.presence.isXboxActivity
import com.discord.utilities.streams.StreamContext
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.facebook.drawee.view.SimpleDraweeView

/**
 * A game being played on a console or partner platform — Xbox, PlayStation, Samsung.
 *
 * These presences arrive through the platform's own integration rather than from a Discord game
 * SDK, so the card doubles as an upsell: if the viewer has not linked that platform to their own
 * account, it offers to. Already-linked platforms just show the game.
 *
 * @param storeUserConnections the viewer's linked accounts, used to decide whether to offer the
 *   link. `StoreStream` exposes no accessor for this store yet, so the default is `null` and the
 *   connect button stays hidden.
 * @param canSeePlaystationAccountIntegration the PlayStation integration's experiment gate
 *   (reference: `PlaystationExperimentUtilsKt.canSeePlaystationAccountIntegration`). `widgets/
 *   playstation` has not been reconstructed, so the default reports the integration off.
 */
class ViewHolderPlatformRichPresence(
    root: View,
    richPresenceImageLarge: SimpleDraweeView,
    richPresenceImageSmall: SimpleDraweeView,
    richPresenceHeader: TextView,
    richPresenceTitle: TextView,
    richPresenceTime: TextView,
    richPresenceTextContainer: View,
    richPresencePrimaryButton: Button,
    private val storeUserConnections: StoreUserConnections? = null,
    private val canSeePlaystationAccountIntegration: () -> Boolean = { false },
) : ViewHolderUserRichPresence(
    root = root,
    richPresenceImageLarge = richPresenceImageLarge,
    richPresenceImageSmall = richPresenceImageSmall,
    richPresenceHeader = richPresenceHeader,
    richPresenceTitle = richPresenceTitle,
    richPresenceDetails = null,
    richPresenceTime = richPresenceTime,
    richPresenceState = null,
    richPresenceTextContainer = richPresenceTextContainer,
    richPresencePrimaryButton = richPresencePrimaryButton,
    richPresenceSecondaryButton = null,
    richPresenceType = PLATFORM_RICH_PRESENCE_TYPE,
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
        disposeSubscriptions()
        val activity = richPresence?.primaryActivity
        if (activity == null || !activity.isGamePlatform()) {
            root.visibility = View.GONE
            return
        }
        root.visibility = View.VISIBLE

        // Xbox presences are identified by their application id rather than a platform tag.
        val platform = if (activity.isXboxActivity()) Platform.XBOX else Platform.from(activity.platform)

        richPresenceHeader.text = PresenceUtils.getActivityHeader(richPresenceHeader.context, activity)
        richPresenceTitle.text = activity.name
        richPresenceTime.setTextAndVisibilityBy(activity.timestamps?.friendlyTime())
        richPresenceTextContainer.isSelected = true

        val offersLink = platform.enabled &&
            (platform != Platform.PLAYSTATION || canSeePlaystationAccountIntegration())
        if (offersLink && storeUserConnections != null) {
            val alreadyLinked = storeUserConnections.getConnectedAccounts()
                .any { it.type == platform.platformId }
            richPresencePrimaryButton?.visibility = if (alreadyLinked) View.GONE else View.VISIBLE
            richPresencePrimaryButton?.text = root.context.i18nFormat(
                R.string.user_activity_connect_platform,
                "platform" to platform.properName,
            )
            richPresencePrimaryButton?.setOnClickListener {
                // The reference passes an "Activity Action" analytics location as a third argument;
                // this project's `authorizeConnection` has no location parameter yet.
                storeUserConnections.authorizeConnection(platform.platformId, it.context)
            }
        } else {
            richPresencePrimaryButton?.visibility = View.GONE
            richPresencePrimaryButton?.setOnClickListener(null)
        }

        configureImages(platform, richPresence)
    }

    /**
     * Platform artwork.
     *
     * PlayStation ships its own presence assets, so it keeps whatever the base class resolved.
     * Xbox has no per-title art and shows the platform glyph full-size instead. Everything else
     * gets the glyph as a small badge over the application icon.
     */
    private fun configureImages(platform: Platform, richPresence: ModelRichPresence?) {
        super.configureAssetUi(richPresence?.primaryActivity, null)
        if (platform == Platform.PLAYSTATION) return

        val themedImage = platform.themedPlatformImage
        if (platform == Platform.XBOX && themedImage != null) {
            MGImages.setImage(richPresenceImageLarge, richPresenceImageLarge.getThemedDrawableRes(themedImage))
            richPresenceImageLarge.visibility = View.VISIBLE
            return
        }

        val whiteGlyph = platform.whitePlatformImage
        richPresenceImageSmall?.visibility =
            if (whiteGlyph != null && platform != Platform.NONE) View.VISIBLE else View.GONE
        if (platform != Platform.NONE) {
            val glyph = whiteGlyph?.let { res ->
                richPresenceImageSmall?.resources?.let { ResourcesCompat.getDrawable(it, res, null) }
            }
            richPresenceImageSmall?.setImageDrawable(glyph)
        }

        if (richPresenceImageLarge.visibility != View.VISIBLE) {
            val application = richPresence?.primaryApplication
            val iconUrl = application?.let { IconUtils.getApplicationIcon(it.id, it.icon.orEmpty()) }
            richPresenceImageLarge.visibility = View.VISIBLE
            MGImages.setImage(richPresenceImageLarge, iconUrl)
        }
    }
}
