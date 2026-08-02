package com.discord.widgets.user.presence

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.activity.Activity
import com.discord.api.activity.ActivityTimestamps
import com.discord.app.AppComponent
import com.discord.i18n.i18nFormat
import com.discord.models.user.User
import com.discord.stores.StoreUserConnections
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.presence.isGameActivity
import com.discord.utilities.presence.isGamePlatform
import com.discord.utilities.presence.isSpotifyActivity
import com.discord.utilities.presence.isStageChannelActivity
import com.discord.utilities.streams.StreamContext
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.time.TimeUtils
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.views.StreamPreviewView
import com.discord.widgets.user.profile.ProfileThemeColors
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.card.MaterialCardView
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Subscription

/**
 * The "activity card" that sits inside a user sheet or profile: what the user is doing right now,
 * how long they have been doing it, and whatever the game chose to advertise.
 *
 * There is one card, but six layouts, because a Spotify track, an Xbox session, a Go Live stream
 * and a stage all present the same data very differently. [Companion.setRichPresence] picks the
 * right subclass for the activity, reusing the current one when the shape has not changed, and each
 * subclass overrides only the parts of the binding that differ. This class is the plain-game
 * default and holds everything the variants share.
 *
 * Views are passed in rather than looked up: the variants have genuinely different layouts, and
 * several of the slots below simply do not exist in some of them — hence the nullable parameters.
 */
open class ViewHolderUserRichPresence(
    protected val root: View,
    protected val richPresenceImageLarge: SimpleDraweeView,
    protected val richPresenceImageSmall: SimpleDraweeView?,
    protected val richPresenceHeader: TextView,
    protected val richPresenceTitle: TextView,
    protected val richPresenceDetails: TextView?,
    protected val richPresenceTime: TextView,
    protected val richPresenceState: TextView?,
    protected val richPresenceTextContainer: View,
    protected val richPresencePrimaryButton: Button?,
    protected val richPresenceSecondaryButton: Button?,
    /** Which layout this holder is bound to; see the `*_RICH_PRESENCE_TYPE` constants. */
    val richPresenceType: Int = BASE_RICH_PRESENCE_TYPE,
) {

    /**
     * Invoked with `(applicationContext, userId, sessionId, applicationId, buttonIndex)` when one of
     * the game's own buttons is tapped. Unset means the buttons still render but do nothing.
     */
    private var onActivityCustomButtonClicked: ((Context, Long, String, Long, Int) -> Unit)? = null

    /** Ticks the elapsed/remaining label once a second while the card is visible. */
    private var perSecondTimerSubscription: Subscription? = null

    private val profileCards = root.allViews().filterIsInstance<MaterialCardView>().toList()
    private val defaultCardColors = profileCards.associateWith(MaterialCardView::getCardBackgroundColor)
    private val defaultCardStrokes = profileCards.associateWith(MaterialCardView::getStrokeColor)
    private val profileButtons = listOfNotNull(richPresencePrimaryButton, richPresenceSecondaryButton)
    private val defaultButtonTints = profileButtons.associateWith(Button::getBackgroundTintList)
    private val defaultButtonTextColors = profileButtons.associateWith(Button::getTextColors)
    private val defaultButtonIconTints =
        profileButtons.associateWith(TextViewCompat::getCompoundDrawableTintList)
    private val profileTextViews = listOfNotNull(
        richPresenceHeader,
        richPresenceTitle,
        richPresenceDetails,
        richPresenceTime,
        richPresenceState,
        root.findViewById(R.id.rich_presence_music_elapsed),
        root.findViewById(R.id.rich_presence_music_duration),
        root.findViewById(R.id.rich_presence_image_large_text),
    )
    private val defaultTextColors = profileTextViews.associateWith(TextView::getTextColors)
    private val seekBar = root.findViewById<SeekBar>(R.id.rich_presence_seekbar)
    private val defaultSeekProgressTint = seekBar?.progressTintList
    private val defaultSeekTrackTint = seekBar?.progressBackgroundTintList

    fun setOnActivityCustomButtonClicked(
        newOnActivityCustomButtonClicked: (Context, Long, String, Long, Int) -> Unit,
    ) {
        onActivityCustomButtonClicked = newOnActivityCustomButtonClicked
    }

    /** Applies the user-profile palette after the variant layout has been inflated. */
    internal fun applyProfileTheme(colors: ProfileThemeColors?) {
        profileCards.forEach { card ->
            if (colors == null) card.setCardBackgroundColor(defaultCardColors.getValue(card))
            else card.setCardBackgroundColor(colors.sectionOverlay)
            card.strokeColor = colors?.borderOverlay ?: defaultCardStrokes.getValue(card)
        }

        fun TextView.color(value: Int?) {
            setTextColor(value?.let(ColorStateList::valueOf) ?: defaultTextColors.getValue(this))
        }
        richPresenceHeader.color(colors?.textMuted)
        richPresenceTitle.color(colors?.textDefault)
        richPresenceDetails?.color(colors?.textSubtle)
        richPresenceState?.color(colors?.textSubtle)
        richPresenceTime.color(colors?.textSubtle)
        root.findViewById<TextView>(R.id.rich_presence_music_elapsed)?.color(colors?.textMuted)
        root.findViewById<TextView>(R.id.rich_presence_music_duration)?.color(colors?.textMuted)
        root.findViewById<TextView>(R.id.rich_presence_image_large_text)?.color(colors?.textDefault)

        profileButtons.forEach { button ->
            button.backgroundTintList = colors?.button?.let(ColorStateList::valueOf)
                ?: defaultButtonTints.getValue(button)
            button.setTextColor(
                colors?.let { ColorStateList.valueOf(android.graphics.Color.WHITE) }
                    ?: defaultButtonTextColors.getValue(button),
            )
            TextViewCompat.setCompoundDrawableTintList(
                button,
                colors?.let { ColorStateList.valueOf(android.graphics.Color.WHITE) }
                    ?: defaultButtonIconTints.getValue(button),
            )
        }

        seekBar?.progressTintList = colors?.textDefault?.let(ColorStateList::valueOf)
            ?: defaultSeekProgressTint
        seekBar?.progressBackgroundTintList = colors?.textMuted?.let(ColorStateList::valueOf)
            ?: defaultSeekTrackTint
        listOf(
            root.findViewById<View>(R.id.stream_preview),
            root.findViewById<View>(R.id.rich_presence_image_large_text),
        ).filterNotNull().forEach { view ->
            ViewCompat.setBackgroundTintList(
                view,
                colors?.sectionOverlay?.let(ColorStateList::valueOf),
            )
        }
    }

    /**
     * Binds the whole card. [streamContext] non-null means the user is streaming, which takes
     * priority over whatever they are playing.
     */
    @MainThread
    open fun configureUi(
        fragmentManager: FragmentManager,
        streamContext: StreamContext?,
        isMe: Boolean,
        user: User?,
        applicationContext: Context,
        richPresence: ModelRichPresence?,
        userInSameVoiceChannel: Boolean,
    ) {
        disposeTimer()
        val activity = richPresence?.primaryActivity
        if (streamContext == null && activity == null) {
            root.visibility = View.GONE
            return
        }
        root.visibility = View.VISIBLE
        configureTextUi(activity, streamContext)
        // Marquee only scrolls on a selected view; the container owns the selection for all of its
        // labels at once.
        richPresenceTextContainer.isSelected = true
        configureAssetUi(activity, streamContext)
        configureCustomButtonsUi(user, activity, applicationContext)
    }

    /**
     * Starts or stops the once-a-second timer that keeps the elapsed label honest.
     *
     * Separate from [configureUi] because the card is re-bound on every state change but the timer
     * must survive those: restarting it would make the label stutter.
     */
    @MainThread
    open fun configureUiTimestamp(model: Activity?, appComponent: AppComponent) {
        val hasTimeToShow = model != null &&
            root.visibility == View.VISIBLE &&
            model.timestamps?.friendlyTime() != null
        if (!hasTimeToShow) {
            disposeTimer()
            return
        }
        if (perSecondTimerSubscription != null) return
        Observable.interval(0L, 1L, TimeUnit.SECONDS)
            .ui(appComponent)
            .appSubscribe(
                errorClass = javaClass,
                subscriptionHandler = { perSecondTimerSubscription = it },
            ) { setTimeTextViews(model?.timestamps) }
    }

    open fun disposeSubscriptions() = disposeTimer()

    @MainThread
    protected fun disposeTimer() {
        perSecondTimerSubscription?.unsubscribe()
        perSecondTimerSubscription = null
    }

    /** Header / title / details / state / time, in the plain-game arrangement. */
    protected open fun configureTextUi(model: Activity?, streamContext: StreamContext?) {
        if (model == null) return
        richPresenceHeader.text =
            PresenceUtils.getActivityHeader(richPresenceHeader.context, model)
        richPresenceTitle.text = model.details
        richPresenceDetails?.setTextAndVisibilityBy(stateWithPartySize(model))
        richPresenceState?.setTextAndVisibilityBy(model.assets?.largeText)
        richPresenceTime.setTextAndVisibilityBy(model.timestamps?.friendlyTime())
    }

    /**
     * The activity's state line with its party size appended — `"In a Match (2 of 5)"`. A party
     * with no declared maximum contributes nothing.
     */
    protected fun stateWithPartySize(model: Activity): String? {
        val state = model.state ?: return null
        val party = model.party
        val partySize = if (party == null) {
            ""
        } else {
            with(PresenceUtils) {
                if (party.maxSize == 0L) {
                    ""
                } else {
                    root.context?.i18nFormat(
                        R.string.user_activity_state_size,
                        "count" to party.currentSize.toString(),
                        "max" to party.maxSize.toString(),
                    ).orEmpty()
                }
            }
        }
        return "$state $partySize".trim()
    }

    /**
     * The game's large and small artwork.
     *
     * A game that supplies only the small asset gets it promoted into the large slot — an empty
     * large slot with a badge floating beside it looks broken.
     */
    @MainThread
    protected open fun configureAssetUi(model: Activity?, streamContext: StreamContext?) {
        val assets = model?.assets
        if (assets == null) {
            richPresenceImageLarge.visibility = View.GONE
            richPresenceImageSmall?.visibility = View.GONE
            return
        }
        val applicationId = model.applicationId
        var largeUri = assets.largeImage?.let { IconUtils.getAssetImage(applicationId, it) }
        var largeText = assets.largeText
        var smallUri = assets.smallImage?.let { IconUtils.getAssetImage(applicationId, it) }
        var smallText = assets.smallText

        if (smallUri != null && largeUri == null) {
            largeUri = smallUri
            largeText = smallText
            smallUri = null
            smallText = null
        }

        richPresenceImageLarge.setImageAndVisibilityBy(largeUri)
        richPresenceImageLarge.contentDescription = largeText
        richPresenceImageSmall?.setImageAndVisibilityBy(smallUri, useSmallCache = true)
        richPresenceImageSmall?.contentDescription = smallText
    }

    /**
     * Up to two buttons the game itself declared. Buttons the activity did not declare are hidden,
     * not merely left blank.
     */
    @MainThread
    protected open fun configureCustomButtonsUi(
        user: User?,
        activity: Activity?,
        applicationContext: Context,
    ) {
        val buttons = activity?.buttons
        listOf(richPresencePrimaryButton, richPresenceSecondaryButton)
            .forEachIndexed { index, button ->
                if (button == null) return@forEachIndexed
                val label = buttons?.getOrNull(index)
                if (label == null) {
                    button.visibility = View.GONE
                    return@forEachIndexed
                }
                button.text = label
                button.visibility = View.VISIBLE
                button.setOnClickListener {
                    handleActivityCustomButtonClick(applicationContext, user, activity, index)
                }
            }
    }

    /**
     * A custom button is only actionable when we know which of the user's sessions is running the
     * game — the action is dispatched to that session, not to the user.
     */
    private fun handleActivityCustomButtonClick(
        applicationContext: Context,
        user: User?,
        activity: Activity?,
        buttonIndex: Int,
    ) {
        val userId = user?.id ?: return
        val sessionId = activity?.sessionId ?: return
        val applicationId = activity.applicationId ?: return
        onActivityCustomButtonClicked?.invoke(
            applicationContext,
            userId,
            sessionId,
            applicationId,
            buttonIndex,
        )
    }

    /** Called once a second by [configureUiTimestamp]. */
    protected open fun setTimeTextViews(timestamps: ActivityTimestamps?) {
        richPresenceTime.setTextAndVisibilityBy(timestamps?.friendlyTime())
    }

    /**
     * `"12:30 left"` when the activity declares an end, `"12:30 elapsed"` when it declares a start,
     * `null` when it declares neither.
     */
    protected fun ActivityTimestamps.friendlyTime(): CharSequence? = when {
        endMs > 0L -> TimeUtils.toFriendlyString(
            startTimeMs = ClockFactory.get().currentTimeMillis(),
            targetTimestampMs = endMs,
            formatString = getString(R.string.user_activity_timestamp_end_simple),
            argName = TIME_DELTA_ARG,
        )
        startMs > 0L -> TimeUtils.toFriendlyString(
            startTimeMs = startMs,
            targetTimestampMs = ClockFactory.get().currentTimeMillis(),
            formatString = getString(R.string.user_activity_timestamp_start_simple),
            argName = TIME_DELTA_ARG,
        )
        else -> null
    }

    protected fun getString(stringId: Int): String? = root.context?.getString(stringId)

    /** Loads [imageUri], hiding the view entirely when there is nothing to load. */
    protected fun ImageView.setImageAndVisibilityBy(imageUri: String?, useSmallCache: Boolean = false) {
        MGImages.setImage(this, imageUri, useSmallCache = useSmallCache)
        visibility = if (!imageUri.isNullOrBlank()) View.VISIBLE else View.GONE
    }

    companion object {
        const val BASE_RICH_PRESENCE_TYPE = 0
        const val GAME_RICH_PRESENCE_TYPE = 1
        const val MUSIC_RICH_PRESENCE_TYPE = 2
        const val PLATFORM_RICH_PRESENCE_TYPE = 3
        const val STREAM_RICH_PRESENCE_TYPE = 4
        const val STAGE_CHANNEL_RICH_PRESENCE_TYPE = 5

        private const val TIME_DELTA_ARG = "timeDelta"

        /**
         * Returns a holder bound to [parent] appropriate for this activity, reusing [oldViewHolder]
         * when it is already the right shape. Re-inflating on every presence tick would drop the
         * elapsed timer and restart every image load.
         *
         * The two trailing parameters only reach [ViewHolderPlatformRichPresence]; the reference
         * reads both straight off `StoreStream` inside the holder, which this project cannot do
         * because neither store is published there yet.
         */
        @JvmStatic
        @JvmOverloads
        fun setRichPresence(
            parent: ViewGroup,
            activity: Activity?,
            streamContext: StreamContext?,
            oldViewHolder: ViewHolderUserRichPresence?,
            storeUserConnections: StoreUserConnections? = null,
            canSeePlaystationAccountIntegration: () -> Boolean = { false },
        ): ViewHolderUserRichPresence {
            val type = getRPViewHolderType(activity, streamContext)
            if (oldViewHolder != null && type == oldViewHolder.richPresenceType) return oldViewHolder
            parent.removeAllViews()
            return createRPView(parent, type, storeUserConnections, canSeePlaystationAccountIntegration)
        }

        private fun getRPViewHolderType(activity: Activity?, streamContext: StreamContext?): Int = when {
            streamContext != null -> STREAM_RICH_PRESENCE_TYPE
            activity == null -> BASE_RICH_PRESENCE_TYPE
            activity.isGamePlatform() -> PLATFORM_RICH_PRESENCE_TYPE
            activity.isGameActivity() -> GAME_RICH_PRESENCE_TYPE
            activity.isSpotifyActivity() -> MUSIC_RICH_PRESENCE_TYPE
            activity.isStageChannelActivity() -> STAGE_CHANNEL_RICH_PRESENCE_TYPE
            else -> BASE_RICH_PRESENCE_TYPE
        }

        private fun createRPView(
            parent: ViewGroup,
            type: Int,
            storeUserConnections: StoreUserConnections?,
            canSeePlaystationAccountIntegration: () -> Boolean,
        ): ViewHolderUserRichPresence {
            val inflater = LayoutInflater.from(parent.context)
            return when (type) {
                GAME_RICH_PRESENCE_TYPE -> {
                    val root = inflater.inflateChild(R.layout.widget_user_rich_presence, parent)
                    ViewHolderGameRichPresence(
                        root = root,
                        richPresenceImageLarge = root.requireView(R.id.rich_presence_image_large),
                        richPresenceImageSmall = root.requireView(R.id.rich_presence_image_small),
                        richPresenceHeader = root.requireView(R.id.rich_presence_header),
                        richPresenceTitle = root.requireView(R.id.rich_presence_title),
                        richPresenceDetails = root.requireView(R.id.rich_presence_details),
                        richPresenceTime = root.requireView(R.id.rich_presence_time),
                        richPresenceState = root.requireView(R.id.rich_presence_state),
                        richPresenceTextContainer = root.requireView(R.id.rich_presence_container_data),
                        richPresencePrimaryButton = root.requireView(R.id.rich_presence_primary_button),
                        richPresenceSecondaryButton = root.requireView(R.id.rich_presence_secondary_button),
                    )
                }

                MUSIC_RICH_PRESENCE_TYPE -> {
                    val root = inflater.inflateChild(R.layout.widget_music_rich_presence, parent)
                    ViewHolderMusicRichPresence(
                        root = root,
                        richPresenceImageLarge = root.requireView(R.id.rich_presence_image_large),
                        richPresenceImageSmall = root.requireView(R.id.rich_presence_image_small),
                        richPresenceHeader = root.requireView(R.id.rich_presence_header),
                        richPresenceTitle = root.requireView(R.id.rich_presence_title),
                        richPresenceDetails = root.requireView(R.id.rich_presence_details),
                        richPresenceTime = root.requireView(R.id.rich_presence_time),
                        richPresenceTextContainer = root.requireView(R.id.rich_presence_container_data),
                        richPresencePrimaryButton = root.requireView(R.id.rich_presence_primary_button),
                        richPresenceSeekbar = root.requireView(R.id.rich_presence_seekbar),
                        richPresenceMusicDuration = root.requireView(R.id.rich_presence_music_duration),
                        richPresenceMusicElapsed = root.requireView(R.id.rich_presence_music_elapsed),
                    )
                }

                PLATFORM_RICH_PRESENCE_TYPE -> {
                    val root = inflater.inflateChild(R.layout.widget_platform_rich_presence, parent)
                    ViewHolderPlatformRichPresence(
                        root = root,
                        richPresenceImageLarge = root.requireView(R.id.rich_presence_image_large),
                        richPresenceImageSmall = root.requireView(R.id.rich_presence_image_small),
                        richPresenceHeader = root.requireView(R.id.rich_presence_header),
                        richPresenceTitle = root.requireView(R.id.rich_presence_title),
                        richPresenceTime = root.requireView(R.id.rich_presence_time),
                        richPresenceTextContainer = root.requireView(R.id.rich_presence_container_data),
                        richPresencePrimaryButton = root.requireView(R.id.rich_presence_primary_button),
                        storeUserConnections = storeUserConnections,
                        canSeePlaystationAccountIntegration = canSeePlaystationAccountIntegration,
                    )
                }

                STREAM_RICH_PRESENCE_TYPE -> {
                    val root = inflater.inflateChild(R.layout.widget_stream_rich_presence, parent)
                    ViewHolderStreamRichPresence(
                        root = root,
                        richPresenceImageLarge = root.requireView(R.id.rich_presence_image_large),
                        richPresenceHeader = root.requireView(R.id.rich_presence_header),
                        richPresenceTitle = root.requireView(R.id.rich_presence_title),
                        richPresenceDetails = root.requireView(R.id.rich_presence_details),
                        richPresenceTime = root.requireView(R.id.rich_presence_time),
                        richPresenceState = root.requireView(R.id.rich_presence_state),
                        richPresenceTextContainer = root.requireView(R.id.rich_presence_container_data),
                        streamPreview = root.requireView<StreamPreviewView>(R.id.stream_preview),
                    )
                }

                STAGE_CHANNEL_RICH_PRESENCE_TYPE -> {
                    val root = inflater.inflateChild(R.layout.widget_stage_channel_rich_presence, parent)
                    ViewHolderStageChannelRichPresence(
                        root = root,
                        richPresenceImageLarge = root.requireView(R.id.rich_presence_image_large),
                        richPresenceHeader = root.requireView(R.id.rich_presence_header),
                        richPresenceTitle = root.requireView(R.id.rich_presence_title),
                        richPresenceDetails = root.requireView(R.id.rich_presence_details),
                        richPresenceTime = root.requireView(R.id.rich_presence_time),
                        richPresenceTextContainer = root.requireView(R.id.rich_presence_container_data),
                        richPresencePrimaryButton = root.requireView(R.id.rich_presence_primary_button),
                        richPresenceImageLargeText = root.requireView(R.id.rich_presence_image_large_text),
                    )
                }

                else -> {
                    val root = inflater.inflateChild(R.layout.widget_user_rich_presence, parent)
                    ViewHolderUserRichPresence(
                        root = root,
                        richPresenceImageLarge = root.requireView(R.id.rich_presence_image_large),
                        richPresenceImageSmall = root.requireView(R.id.rich_presence_image_small),
                        richPresenceHeader = root.requireView(R.id.rich_presence_header),
                        richPresenceTitle = root.requireView(R.id.rich_presence_title),
                        richPresenceDetails = root.requireView(R.id.rich_presence_details),
                        richPresenceTime = root.requireView(R.id.rich_presence_time),
                        richPresenceState = root.requireView(R.id.rich_presence_state),
                        richPresenceTextContainer = root.requireView(R.id.rich_presence_container_data),
                        richPresencePrimaryButton = root.requireView(R.id.rich_presence_primary_button),
                        richPresenceSecondaryButton = root.requireView(R.id.rich_presence_secondary_button),
                    )
                }
            }
        }

        /**
         * Inflates [layoutRes] and attaches it to [parent], returning the inflated child rather
         * than the parent — which is what `inflate(…, attachToRoot = true)` would hand back.
         */
        private fun LayoutInflater.inflateChild(layoutRes: Int, parent: ViewGroup): View =
            inflate(layoutRes, parent, false).also(parent::addView)

        /**
         * `findViewById` that fails loudly. The generated view bindings the reference used threw on
         * a missing id; a silent null here would surface much later as an unexplained blank card.
         */
        private fun <T : View> View.requireView(id: Int): T =
            requireNotNull(findViewById<T>(id)) {
                "Missing required view with ID: ${resources.getResourceName(id)}"
            }
    }
}

private fun View.allViews(): Sequence<View> = sequence {
    yield(this@allViews)
    if (this@allViews is ViewGroup) {
        repeat(childCount) { yieldAll(getChildAt(it).allViews()) }
    }
}
