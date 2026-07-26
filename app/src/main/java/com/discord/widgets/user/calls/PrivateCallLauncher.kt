package com.discord.widgets.user.calls

import android.content.Context
import androidx.fragment.app.FragmentManager
import com.discord.app.AppComponent
import com.discord.app.AppPermissionsRequests
import com.discord.stores.StoreCalls
import com.discord.stores.StoreMediaEngine
import com.discord.stores.StoreVoiceChannelSelected

/**
 * Starts a one-to-one or group call from a profile, a friends row, or a DM header.
 *
 * Every one of those surfaces needs the same three steps — ask for the right runtime permissions,
 * join the channel, then bring up the call UI — so they all hold one of these rather than repeating
 * the sequence. The only difference between the two entry points is whether the camera is requested
 * and which device the media engine opens.
 */
class PrivateCallLauncher(
    private val appPermissionsRequests: AppPermissionsRequests,
    private val appComponent: AppComponent,
    private val context: Context,
    private val fragmentManager: FragmentManager,
    /**
     * Rings the channel once it has been joined. `StoreStream` publishes no accessor for this store
     * yet, so the default is `null` and the callee is never rung.
     */
    private val storeCalls: StoreCalls? = null,
    /**
     * Joins the channel. `StoreStream` publishes no accessor for this store yet, so the default is
     * `null` and the call cannot be connected — see the class note below.
     */
    private val storeVoiceChannelSelected: StoreVoiceChannelSelected? = null,
    /**
     * Opens the camera for a video call. `StoreStream` publishes no accessor for this store yet.
     */
    private val storeMediaEngine: StoreMediaEngine? = null,
) {

    fun launchVoiceCall(channelId: Long) = callAndLaunch(channelId, isVideo = false)

    fun launchVideoCall(channelId: Long) = callAndLaunch(channelId, isVideo = true)

    /**
     * Reference: `widgets/voice/call/PrivateCallLaunchUtilsKt.callAndLaunch`. That file belongs to
     * `widgets/voice`, which is not reconstructed, so the sequence lives here for now.
     *
     * Two pieces of the reference behaviour are absent and are *not* approximated:
     * - `WidgetCallFullscreen` does not exist, so nothing navigates to the call surface once the
     *   connection lands. The user joins the channel but stays on the current screen.
     * - `StoreCalls.call(...)` — which decides whether to ring based on the channel's existing
     *   voice states and surfaces `WidgetCallFailed` on a blocked-by-privacy error — has not been
     *   reconstructed either; this rings unconditionally.
     */
    private fun callAndLaunch(channelId: Long, isVideo: Boolean) {
        val onPermissionsGranted = {
            if (isVideo) {
                storeMediaEngine?.selectDefaultVideoDevice { deviceGuid ->
                    if (deviceGuid != null) connect(channelId) else Unit
                }
            } else {
                connect(channelId)
            }
        }
        if (isVideo) {
            appPermissionsRequests.requestVideoCallPermissions { onPermissionsGranted() }
        } else {
            appPermissionsRequests.requestMicrophone(onFailure = null) { onPermissionsGranted() }
        }
    }

    private fun connect(channelId: Long) {
        storeVoiceChannelSelected?.selectVoiceChannel(channelId)?.subscribe({}, {})
        storeCalls?.ring(channelId)
    }
}
