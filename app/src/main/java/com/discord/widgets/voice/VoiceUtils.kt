package com.discord.widgets.voice

import android.content.Context
import androidx.fragment.app.FragmentManager
import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.permission.Permission
import com.discord.app.AppBottomSheet
import com.discord.app.AppComponent
import com.discord.app.AppFragment
import com.discord.app.AppPermissionsRequests
import com.discord.stores.StoreStream
import com.discord.utilities.guilds.MemberVerificationUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.user.UserUtils.isNewUser
import com.discord.widgets.stage.StageChannelAPI
import com.discord.widgets.stage.StageChannelJoinHelper
import com.discord.widgets.stage.start.WidgetModeratorStartStage
import com.discord.widgets.voice.call.callAndLaunch
import com.discord.widgets.voice.fullscreen.WidgetCallFullscreen
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
 * * a **regular voice channel** opens the voice bottom sheet; its Join button connects later.
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
        // Screening is interposed before the join rather than after: falling through would have the
        // connection refused with no explanation, where the gate says what to do about it.
        if (
            channel.guildId != 0L &&
            MemberVerificationUtils.maybeShowVerificationGate(
                context,
                fragmentManager,
                channel.guildId,
                location = "Voice Channel",
            )
        ) {
            return
        }
        when {
            channel.isPrivate() -> callAndLaunch(
                channelId = channel.id,
                isVideo = false,
                appPermissionsRequests = appPermissionsRequests,
                context = context,
                appComponent = appComponent,
                fragmentManager = fragmentManager,
            )
            // A stage, like voice, opens the pre-join sheet first, as on RN, so nobody lands in a
            // stage without seeing who is there; the sheet's Join button comes back to [joinStage].
            else -> showControls(channel, context, fragmentManager)
        }
    }

    /**
     * Joins a stage, or offers a moderator the start menu when nothing is running yet instead of
     * dropping them into an empty room.
     *
     * The reference keeps this in `utilities/stage/StageChannelUtils.connectToStageChannel`, which
     * this project has not reconstructed. No screening check here: the sheet that calls this was
     * opened through [handleCallChannel], which has already interposed the gate.
     */
    fun joinStage(channel: Channel, context: Context, fragmentManager: FragmentManager) {
        val stageNotStarted = StoreStream.getStageInstances().getStageInstanceForChannel(channel.id) == null
        val myPermissions = StoreStream.getPermissions().getPermissionsByChannel()[channel.id]
        if (stageNotStarted && PermissionUtils.can(Permission.MODERATE_STAGE_CHANNEL, myPermissions)) {
            WidgetModeratorStartStage.launch(context, channel.id, channel.guildId)
        } else {
            StageChannelJoinHelper.connectToStage(context, fragmentManager, channel.id)
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
        // Entering through an event is still entering: a screened member is gated here too, and
        // this path does not go through [handleCallChannel].
        if (
            MemberVerificationUtils.maybeShowVerificationGate(
                context,
                fragmentManager,
                channel.guildId,
                location = "Guild Event",
            )
        ) {
            return
        }
        if (channel.isStageVoiceChannel()) {
            StageChannelJoinHelper.connectToStage(context, fragmentManager, channel.id) {
                StageChannelAPI.setUserSuppressedInChannel(
                    channel,
                    StoreStream.getUsers().getMe().id,
                    isSuppressed = false,
                ).subscribe({}, {})
                onEventStarted()
            }
            return
        }
        appPermissionsRequests.requestMicrophone(onFailure = null) {
            connectAndShowControls(channel, context, fragmentManager)
            onEventStarted()
        }
    }

    /**
     * Joins [channel] and brings up its controls for scheduled-event entry points.
     *
     * Text-in-voice routes an already connected channel to the fullscreen call. An unconnected
     * channel still uses the join sheet because the separate preview screen is outside this slice.
     */
    private fun connectAndShowControls(
        channel: Channel,
        context: Context,
        fragmentManager: FragmentManager,
    ) {
        StoreStream.getVoiceChannelSelected().selectVoiceChannel(channel.id).subscribe({}, {})
        showControls(channel, context, fragmentManager)
    }

    private fun showControls(channel: Channel, context: Context, fragmentManager: FragmentManager) {
        val activeChannelId = StoreStream.getVoiceChannelSelected().getSelectedVoiceChannelId()
        if (activeChannelId == channel.id) {
            WidgetCallFullscreen.launch(context, channel.id, animate = true)
            return
        }
        WidgetVoiceBottomSheet.show(
            fragmentManager,
            channel.id,
            forwardToFullscreenIfVideoActivated = true,
            featureContext = WidgetVoiceBottomSheet.FeatureContext.HOME,
        )
    }
}
