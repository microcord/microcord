package com.discord.widgets.voice

import android.content.Context
import androidx.fragment.app.FragmentManager
import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.channel.isPrivate
import com.discord.app.AppBottomSheet
import com.discord.app.AppComponent
import com.discord.app.AppFragment
import com.discord.app.AppPermissionsRequests
import com.discord.stores.StoreStream
import com.discord.widgets.voice.call.callAndLaunch
import com.discord.widgets.voice.sheet.WidgetVoiceBottomSheet

/**
 * The single entry point for "the user tapped a channel that is a *call*".
 *
 * Every surface that can start or join a call — the channel list, the DM list, a user profile, a
 * scheduled-event card — routes through here so the three channel kinds are handled consistently:
 *
 * * a **DM or group DM** places a private call, which means asking for the microphone, deciding
 *   whether to ring, and only then connecting;
 * * a **stage channel** connects as audience and opens the stage controls;
 * * a **regular voice channel** connects and opens the voice bottom sheet.
 */
object VoiceUtils {

    @JvmStatic
    fun handleCallChannel(channel: Channel, appFragment: AppFragment) = handleCallChannel(
        channel,
        appFragment,
        appFragment,
        appFragment.requireContext(),
        appFragment.parentFragmentManager,
    )

    @JvmStatic
    fun handleCallChannel(channel: Channel, appBottomSheet: AppBottomSheet) = handleCallChannel(
        channel,
        appBottomSheet,
        appBottomSheet,
        appBottomSheet.requireContext(),
        appBottomSheet.parentFragmentManager,
    )

    @JvmStatic
    fun handleCallChannel(
        channel: Channel,
        appComponent: AppComponent,
        appPermissionsRequests: AppPermissionsRequests,
        context: Context,
        fragmentManager: FragmentManager,
    ) {
        // The reference wraps this in `MemberVerificationUtils.maybeShowVerificationGate`, which
        // interposes the guild's member-verification sheet for a lurker. Only
        // `MemberVerificationUtils.hasVerificationGate` is reconstructed — there is no sheet to
        // show yet — so a gated lurker falls through to the join attempt and is refused by
        // `VoiceChannelJoinabilityUtils` instead of being offered the gate.
        if (channel.isPrivate()) {
            callAndLaunch(
                channelId = channel.id,
                isVideo = false,
                appPermissionsRequests = appPermissionsRequests,
                context = context,
                appComponent = appComponent,
                fragmentManager = fragmentManager,
            )
        } else {
            // Stage channels take the same path: connecting puts the user in the audience, and the
            // sheet renders the stage-specific controls off `StageRoles`.
            appPermissionsRequests.requestMicrophone(onFailure = null) {
                connectAndShowControls(channel, fragmentManager)
            }
        }
    }

    /**
     * Connects to a channel that already has a scheduled event running in it and, on a stage,
     * un-suppresses the user so they arrive as a speaker rather than in the audience.
     *
     * @param onEventStarted runs once the connection has been requested, so the caller can dismiss
     *   whatever surface the event was started from.
     */
    @JvmStatic
    @JvmOverloads
    fun handleConnectToEventChannel(
        channel: Channel,
        appFragment: AppFragment,
        onEventStarted: () -> Unit = {},
    ) = handleConnectToEventChannel(
        channel,
        appFragment,
        appFragment.requireContext(),
        appFragment.parentFragmentManager,
        onEventStarted,
    )

    @JvmStatic
    @JvmOverloads
    fun handleConnectToEventChannel(
        channel: Channel,
        appBottomSheet: AppBottomSheet,
        onEventStarted: () -> Unit = {},
    ) = handleConnectToEventChannel(
        channel,
        appBottomSheet,
        appBottomSheet.requireContext(),
        appBottomSheet.parentFragmentManager,
        onEventStarted,
    )

    @JvmStatic
    fun handleConnectToEventChannel(
        channel: Channel,
        appPermissionsRequests: AppPermissionsRequests,
        context: Context,
        fragmentManager: FragmentManager,
        onEventStarted: () -> Unit,
    ) {
        if (!channel.isGuildVocalChannel()) return
        appPermissionsRequests.requestMicrophone(onFailure = null) {
            connectAndShowControls(channel, fragmentManager)
            // On a stage the reference additionally calls
            // `StageChannelAPI.setUserSuppressedInChannel(channel, meId, suppressed = false)` here,
            // so the host who starts the event lands on stage instead of in the audience. That
            // call is made from the stage slice; see the handoff report.
            onEventStarted()
        }
    }

    /**
     * Joins [channel] and brings up the controls for it.
     *
     * The reference opens `WidgetCallPreviewFullscreen` / `WidgetCallFullscreen` instead whenever
     * the text-in-voice feature flag is on. Neither that flag nor a `TextInVoiceFeatureFlag`
     * equivalent exists in this reconstruction, so the bottom sheet — the flag-off path — is always
     * used.
     */
    private fun connectAndShowControls(channel: Channel, fragmentManager: FragmentManager) {
        StoreStream.getVoiceChannelSelected().selectVoiceChannel(channel.id).subscribe({}, {})
        WidgetVoiceBottomSheet.show(
            fragmentManager,
            channel.id,
            forwardToFullscreenIfVideoActivated = true,
            featureContext = WidgetVoiceBottomSheet.FeatureContext.HOME,
        )
    }
}
