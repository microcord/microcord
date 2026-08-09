package com.discord.widgets.status

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.inSpans
import androidx.core.view.ViewCompat
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.discord.R
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.app.AppFragment
import com.discord.app.appViewModels
import com.discord.i18n.i18nFormat
import com.discord.i18n.i18nPlural
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.color.setTint
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.rx.ui
import com.discord.utilities.spans.TypefaceSpanCompat
import com.google.android.material.button.MaterialButton
import java.util.concurrent.TimeUnit
import rx.Observable

/**
 * The app-wide status banner: a single strip above the app's content that reports the connection,
 * an ongoing call, or an invitation to speak on a stage.
 *
 * It is a fragment rather than a view because it outlives the screens beneath it — the banner has
 * to survive navigation between tabs while a call continues. Its own visibility is *not* decided
 * here; it comes from [WidgetGlobalStatusIndicatorState], which the fullscreen call screen also
 * writes to so the banner can suppress itself when it would only be repeating what that screen
 * already shows.
 */
class WidgetGlobalStatusIndicator : AppFragment(R.layout.widget_global_status_indicator) {

    private val viewModel: WidgetGlobalStatusIndicatorViewModel by appViewModels {
        WidgetGlobalStatusIndicatorViewModel()
    }

    private val indicatorState = WidgetGlobalStatusIndicatorState.Provider.get()
    private var lastIndicatorState: WidgetGlobalStatusIndicatorState.State? = null

    private lateinit var indicator: LinearLayout
    private lateinit var indicatorContent: LinearLayout
    private lateinit var indicatorActions: LinearLayout
    private lateinit var indicatorIcon: ImageView
    private lateinit var indicatorText: TextView
    private lateinit var alertText: TextView
    private lateinit var acceptButton: MaterialButton
    private lateinit var declineButton: MaterialButton

    private var connectingVector: AnimatedVectorDrawableCompat? = null

    /**
     * `AnimatedVectorDrawableCompat` plays once; restarting it on end is what makes the connecting
     * indicator loop.
     */
    private val connectingVectorReplayCallback = object : Animatable2Compat.AnimationCallback() {
        override fun onAnimationEnd(drawable: Drawable) {
            connectingVector?.start()
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        indicator = view.findViewById(R.id.indicator)
        indicatorContent = view.findViewById(R.id.indicator_content)
        indicatorActions = view.findViewById(R.id.indicator_actions)
        indicatorIcon = view.findViewById(R.id.indicator_icon)
        indicatorText = view.findViewById(R.id.indicator_text)
        alertText = view.findViewById(R.id.alert_text)
        acceptButton = view.findViewById(R.id.indicator_accept)
        declineButton = view.findViewById(R.id.indicator_decline)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            (view.parent as? View)?.let { container ->
                val overlap = resources.getDimensionPixelSize(R.dimen.gateway_banner_status_overlap)
                (container.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin =
                    (insets.systemWindowInsetTop - overlap).coerceAtLeast(0)
                container.requestLayout()
            }
            insets.consumeSystemWindowInsets()
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        indicatorState.observeState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGlobalStatusIndicator::class.java) { configureUIVisibility(it) }

        viewModel.observeViewState()
            .bindDelay()
            .ui(this)
            .appSubscribe(WidgetGlobalStatusIndicator::class.java) { configureUI(it) }
    }

    override fun onDestroyView() {
        connectingVector?.unregisterAnimationCallback(connectingVectorReplayCallback)
        connectingVector?.stop()
        super.onDestroyView()
    }

    /**
     * Holds back the offline and connecting banners for the delay the store attached to them.
     *
     * A short network blip resolves before its delay elapses, and because this runs inside a
     * `switchMap` the pending emission is discarded when the next state arrives — so the user never
     * sees a banner for a hiccup that already fixed itself.
     */
    private fun Observable<WidgetGlobalStatusIndicatorViewModel.ViewState>.bindDelay() =
        switchMap { viewState ->
            val delay = when (viewState) {
                is WidgetGlobalStatusIndicatorViewModel.ViewState.Offline -> viewState.delay
                is WidgetGlobalStatusIndicatorViewModel.ViewState.Connecting -> viewState.delay
                else -> null
            }
            if (delay == null) {
                Observable.just(viewState)
            } else {
                Observable.just(viewState).delay(delay, TimeUnit.MILLISECONDS)
            }
        }

    private fun configureUIVisibility(state: WidgetGlobalStatusIndicatorState.State) {
        lastIndicatorState = state
        val visibility = if (state.isVisible) View.VISIBLE else View.GONE
        indicator.visibility = visibility
        (indicator.parent as? View)?.visibility = visibility
    }

    private fun configureUI(viewState: WidgetGlobalStatusIndicatorViewModel.ViewState) {
        val compact = viewState is WidgetGlobalStatusIndicatorViewModel.ViewState.Connecting ||
            viewState is WidgetGlobalStatusIndicatorViewModel.ViewState.Offline
        indicator.layoutParams.height =
            if (compact) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        (indicator.parent as? View)?.let { container ->
            container.layoutParams.height = if (compact) {
                resources.getDimensionPixelSize(R.dimen.gateway_connecting_banner_height)
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
            container.requestLayout()
        }
        indicatorState.updateState(
            isVisible = viewState != WidgetGlobalStatusIndicatorViewModel.ViewState.Inactive,
            isCustomBackground = viewState.isSpeakingInOngoingCall,
        )

        connectingVector?.unregisterAnimationCallback(connectingVectorReplayCallback)
        connectingVector?.stop()

        when (viewState) {
            is WidgetGlobalStatusIndicatorViewModel.ViewState.Offline ->
                setupOfflineState(viewState.airplaneMode)
            is WidgetGlobalStatusIndicatorViewModel.ViewState.Connecting ->
                setupConnectingState()
            is WidgetGlobalStatusIndicatorViewModel.ViewState.CallOngoing -> {
                setupContainerClicks(viewState)
                setupIndicatorStatus(viewState)
            }
            is WidgetGlobalStatusIndicatorViewModel.ViewState.StageChannelOngoing -> {
                setupStageContainerClicks(viewState)
                setupStageIndicatorStatus(viewState)
            }
            WidgetGlobalStatusIndicatorViewModel.ViewState.Inactive -> resetContentVisibility()
        }
        // While the user is already looking at the call, the descriptive half of the banner is
        // redundant — it collapses to nothing unless it still has buttons to offer.
        if (lastIndicatorState?.isViewingCall == true) {
            indicator.visibility = indicatorActions.visibility
        }
    }

    private fun resetContentVisibility() {
        indicatorContent.visibility = View.VISIBLE
        alertText.visibility = View.GONE
    }

    // region connection states

    private fun setupOfflineState(airplaneMode: Boolean) {
        resetContentVisibility()
        applyNeutralChrome()
        indicatorText.setText(
            if (airplaneMode) R.string.network_offline_airplane_mode else R.string.network_offline,
        )
        indicatorIcon.visibility = View.VISIBLE
        indicatorIcon.setImageResource(
            requireContext().getThemedDrawableRes(
                if (airplaneMode) R.attr.ic_network_airplane_mode else R.attr.ic_network_offline,
            ),
        )
    }

    private fun setupConnectingState() {
        resetContentVisibility()
        applyNeutralChrome()
        indicatorText.text = getString(R.string.connecting)
        indicatorIcon.visibility = View.VISIBLE

        // API 28 mis-renders this particular animated vector, so that one release gets the static
        // icon instead. The reference makes the same exception.
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
            indicatorIcon.setImageResource(
                requireContext().getThemedDrawableRes(R.attr.ic_network_connecting),
            )
            return
        }
        if (connectingVector == null) {
            connectingVector = AnimatedVectorDrawableCompat.create(
                requireContext(),
                requireContext().getThemedDrawableRes(R.attr.ic_network_connecting_animated_vector),
            )
        }
        indicatorIcon.setImageDrawable(
            connectingVector?.also {
                it.registerAnimationCallback(connectingVectorReplayCallback)
                it.start()
            },
        )
    }

    private fun applyNeutralChrome() {
        indicator.setBackgroundColor(
            ColorCompat.getThemedColor(requireContext(), R.attr.colorBackgroundTertiary),
        )
        indicatorText.setTextColor(
            ColorCompat.getThemedColor(requireContext(), R.attr.colorHeaderPrimary),
        )
    }

    // endregion

    // region ongoing call

    private fun setupContainerClicks(
        viewState: WidgetGlobalStatusIndicatorViewModel.ViewState.CallOngoing,
    ) {
        indicator.setOnClickListener {
            onCallBannerPressed?.invoke(this, viewState.selectedVoiceChannel.id)
        }
    }

    private fun setupIndicatorStatus(
        viewState: WidgetGlobalStatusIndicatorViewModel.ViewState.CallOngoing,
    ) {
        indicatorActions.visibility = View.GONE
        indicator.setBackgroundColor(
            ContextCompat.getColor(
                requireContext(),
                if (viewState.isRtcConnected) R.color.status_green_600 else R.color.status_red_500,
            ),
        )

        val channelName = viewState.selectedVoiceChannel
            .getDisplayNameOrDefault(requireContext(), includePrefix = false)
        val location = viewState.guild?.name?.let { "$it / $channelName" } ?: channelName
        indicatorText.text = "${connectedText(viewState)}: $location"
        indicatorText.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))

        indicatorIcon.visibility = View.VISIBLE
        indicatorIcon.setImageResource(
            if (viewState.hasVideo) {
                R.drawable.ic_videocam_white_16dp
            } else {
                R.drawable.ic_call_indicator_voice_16dp
            },
        )
    }

    /**
     * The leading half of the banner text — what the connection is doing.
     *
     * The reference distinguishes six connection phases here (authenticating, awaiting endpoint,
     * no route, RTC connecting, connected, disconnected) and additionally reports "watching X's
     * stream". `RtcConnection` in this reconstruction exposes no phase beyond connected-or-not and
     * `StreamContextService` does not exist, so only the two end states are reachable. The four
     * intermediate strings are unused but present in `res/values/strings.xml` and drop in as soon
     * as the transport can report a phase.
     */
    private fun connectedText(
        viewState: WidgetGlobalStatusIndicatorViewModel.ViewState.CallOngoing,
    ): CharSequence = when {
        !viewState.isRtcConnected -> getString(R.string.connection_status_connecting)
        viewState.hasVideo -> getString(R.string.connection_status_video_connected)
        else -> getString(R.string.connection_status_voice_connected)
    }

    // endregion

    // region stage channel

    private fun setupStageContainerClicks(
        viewState: WidgetGlobalStatusIndicatorViewModel.ViewState.StageChannelOngoing,
    ) {
        indicator.setOnClickListener {
            onCallBannerPressed?.invoke(this, viewState.selectedVoiceChannel.id)
        }
        // Accepting means unmuting, so the microphone permission is requested first; declining
        // needs nothing.
        acceptButton.setOnClickListener {
            requestMicrophone(
                onFailure = {
                    android.widget.Toast.makeText(
                        context,
                        R.string.stage_channel_permission_microphone_denied,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
                onSuccess = { viewModel.ackStageInvitationToSpeak(true) },
            )
        }
        declineButton.setOnClickListener { viewModel.ackStageInvitationToSpeak(false) }
    }

    private fun setupStageIndicatorStatus(
        viewState: WidgetGlobalStatusIndicatorViewModel.ViewState.StageChannelOngoing,
    ) {
        resetContentVisibility()

        val foreground = if (viewState.isSpeakingInOngoingCall) {
            ContextCompat.getColor(requireContext(), R.color.white)
        } else {
            ColorCompat.getThemedColor(requireContext(), R.attr.colorHeaderPrimary)
        }
        val background = if (viewState.isSpeakingInOngoingCall) {
            ContextCompat.getColor(requireContext(), R.color.status_green_600)
        } else {
            ColorCompat.getThemedColor(requireContext(), R.attr.colorBackgroundTertiary)
        }
        indicator.setBackgroundColor(background)
        indicatorText.setTextColor(foreground)
        declineButton.setTextColor(foreground)

        indicatorActions.visibility = if (viewState.isInvitedToSpeak) View.VISIBLE else View.GONE
        // The icon is redundant on the call screen itself, which already shows one.
        indicatorIcon.visibility =
            if (lastIndicatorState?.isViewingCall == false) View.VISIBLE else View.GONE
        indicatorIcon.setImageDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_channel_stage_16dp)
                ?.also { it.setTint(foreground) },
        )

        if (viewState.isInvitedToSpeak) {
            setupSpeakInvitation(viewState)
        } else {
            indicatorText.text = stageDescription(viewState)
        }
    }

    private fun setupSpeakInvitation(
        viewState: WidgetGlobalStatusIndicatorViewModel.ViewState.StageChannelOngoing,
    ) {
        indicatorText.setText(R.string.stage_speak_invite_header)
        acceptButton.setText(R.string.stage_speak_invite_accept)
        declineButton.setText(R.string.stage_speak_invite_decline)
        acceptButton.isEnabled = !viewState.isAckingInvitation
        declineButton.isEnabled = !viewState.isAckingInvitation

        alertText.visibility = if (viewState.blockedUsersOnStage != 0) View.VISIBLE else View.GONE
        if (viewState.blockedUsersOnStage == 0) return
        val blockedUsers = requireContext().i18nPlural(
            R.plurals.stage_speak_invite_blocked_users_number,
            viewState.blockedUsersOnStage,
            "#" to viewState.blockedUsersOnStage,
        )
        alertText.text = requireContext().i18nFormat(
            R.string.stage_speak_invite_blocked_users,
            "number" to blockedUsers,
        )
    }

    /** "Topic: Guild: Channel", with the topic weighted more heavily than its context. */
    private fun stageDescription(
        viewState: WidgetGlobalStatusIndicatorViewModel.ViewState.StageChannelOngoing,
    ): CharSequence {
        val context = requireContext()
        return SpannableStringBuilder().apply {
            viewState.stageInstance?.topic?.let {
                appendWithFont(context, R.attr.font_primary_semibold, it)
                appendWithFont(context, R.attr.font_primary_semibold, ": ")
            }
            viewState.guild?.name?.let {
                appendWithFont(context, R.attr.font_primary_normal, it)
                appendWithFont(context, R.attr.font_primary_normal, ": ")
            }
            appendWithFont(
                context,
                R.attr.font_primary_normal,
                viewState.selectedVoiceChannel.getDisplayNameOrDefault(context, includePrefix = false),
            )
        }
    }

    /**
     * Reference: `SpannableUtilsKt.appendWithFont`, which is not reconstructed. Move this there
     * when it lands — it is generally useful and this is its second-most-likely caller.
     */
    private fun SpannableStringBuilder.appendWithFont(
        context: Context,
        @AttrRes fontAttr: Int,
        text: CharSequence,
    ) {
        val typeface = context.themedTypeface(fontAttr)
        if (typeface == null) append(text) else inSpans(TypefaceSpanCompat(typeface)) { append(text) }
    }

    private fun Context.themedTypeface(@AttrRes fontAttr: Int): Typeface? {
        val resolved = TypedValue()
        if (!theme.resolveAttribute(fontAttr, resolved, true) || resolved.resourceId == 0) return null
        return runCatching { ResourcesCompat.getFont(this, resolved.resourceId) }.getOrNull()
    }

    // endregion

    companion object {
        /**
         * Opens the call the banner is describing.
         *
         * The destinations — `WidgetCallFullscreen` and `WidgetVoiceBottomSheet` — live in
         * `widgets/voice`, which is being reconstructed in parallel, so the banner does not name
         * them. Whoever owns that package installs this, exactly as `StoreStream` installs
         * `WidgetChatStatus.onJumpToMessage`. Until then the banner renders correctly and is inert
         * on tap.
         */
        @JvmField
        var onCallBannerPressed: ((AppFragment, Long) -> Unit)? = null
    }
}
