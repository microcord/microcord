@file:JvmName("PrivateCallLaunchUtilsKt")

package com.discord.widgets.voice.call

import android.content.Context
import androidx.fragment.app.FragmentManager
import com.discord.app.AppComponent
import com.discord.app.AppPermissionsRequests
import com.discord.api.channel.getDMRecipient
import com.discord.models.domain.ModelUserRelationship
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.takeSingleUntilTimeout
import com.discord.utilities.rx.ui
import com.discord.widgets.voice.fullscreen.WidgetCallFullscreen
import com.discord.widgets.notice.WidgetNoticeNuxOverlay
import rx.Observable

/**
 * Places a call in a DM or group DM and brings up the call UI.
 *
 * Three steps, in order: get the runtime permissions, decide whether the callee should be *rung*,
 * then join the channel. The ring decision is the interesting one — see [callAndLaunch].
 */
@Suppress("LongParameterList")
fun callAndLaunch(
    channelId: Long,
    isVideo: Boolean,
    appPermissionsRequests: AppPermissionsRequests,
    context: Context,
    appComponent: AppComponent,
    fragmentManager: FragmentManager,
) {
    val onPermissionsGranted = {
        if (isVideo) {
            // Opening the camera can fail (no camera, or another app holds it); the reference
            // abandons the call rather than silently placing an audio-only one.
            StoreStream.getMediaEngine().selectDefaultVideoDevice { deviceGuid ->
                if (deviceGuid != null) call(channelId, context, appComponent, fragmentManager)
            }
        } else {
            call(channelId, context, appComponent, fragmentManager)
        }
    }
    if (isVideo) {
        appPermissionsRequests.requestVideoCallPermissions { onPermissionsGranted() }
    } else {
        appPermissionsRequests.requestMicrophone(onFailure = null) { onPermissionsGranted() }
    }
}

/**
 * Joins [channelId], ringing the other side when that is appropriate.
 *
 * A **group DM** rings only when nobody is in the call yet — joining an in-progress group call must
 * not re-ring everyone. A **one-to-one DM** rings a friend unconditionally; for a non-friend the
 * server decides, and a `50013` (missing access) answer means the recipient's privacy settings
 * block the call, which is what [WidgetCallFailed] explains.
 *
 * Reference: this whole sequence is `StoreCalls.call(...)`. The reconstructed store currently owns
 * call-state and ring/stop-ring operations, but not this REST privacy check, so the sequence remains
 * here rather than weakening the call decision.
 */
private fun call(
    channelId: Long,
    context: Context,
    appComponent: AppComponent,
    fragmentManager: FragmentManager,
) {
    val doCall = { ring: Boolean ->
        StoreStream.getVoiceChannelSelected().selectVoiceChannel(channelId).subscribe({}, {})
        if (ring) StoreStream.getCalls().ring(channelId)
        Observable.combineLatest(
            StoreStream.getRtcConnection().getConnectionState(),
            StoreStream.getVoiceChannelSelected().observeSelectedVoiceChannelId(),
        ) { connection, selectedChannelId -> connection.metadata?.channelId ?: selectedChannelId }
            .filter { it == channelId }
            .take(1)
            .ui(appComponent)
            .appSubscribe(errorClass = WidgetCallFullscreen::class.java) {
                WidgetCallFullscreen.launch(context, channelId)
                if (!StoreStream.getUserSettings().getIsMobileOverlayEnabled()) {
                    WidgetNoticeNuxOverlay.enqueue()
                }
            }
        Unit
    }

    Observable.zip(
        StoreStream.getChannels().observeChannel(channelId),
        StoreStream.getVoiceStates().observeForPrivateChannels(channelId),
    ) { channel, voiceStates -> channel to voiceStates }
        .take(1)
        .appSubscribe(errorClass = WidgetCallFailed::class.java) { (channel, voiceStates) ->
            val recipient = channel?.getDMRecipient()
            when {
                recipient != null ->
                    ringIfCallable(channelId, recipient.id, context, appComponent, fragmentManager, doCall)
                channel != null -> doCall(voiceStates.isEmpty())
                else -> Unit
            }
        }
}

private fun ringIfCallable(
    channelId: Long,
    recipientId: Long,
    context: Context,
    appComponent: AppComponent,
    fragmentManager: FragmentManager,
    doCall: (Boolean) -> Unit,
) {
    StoreStream.getUserRelationships().observe(recipientId)
        .flatMap { relationshipType ->
            if (relationshipType == ModelUserRelationship.TYPE_FRIEND) {
                Observable.just(true)
            } else {
                RestAPI.api.call(channelId).restSubscribeOn().map { true }
            }
        }
        .takeSingleUntilTimeout()
        .ui(appComponent)
        .appSubscribe(
            errorClass = WidgetCallFailed::class.java,
            context = context,
            errorHandler = { error ->
                if (error.code == ERROR_MISSING_ACCESS) {
                    // Not an error the user can act on by retrying, so suppress the toast and
                    // explain it properly instead.
                    error.showErrorToasts = false
                    WidgetCallFailed.show(fragmentManager, recipientId)
                }
            },
        ) { isRingable -> doCall(isRingable) }
}

/** `MISSING_ACCESS` — the recipient's privacy settings refuse calls from this user. */
private const val ERROR_MISSING_ACCESS = 50013
