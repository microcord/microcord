package com.discord.widgets.user.calls

import android.content.Context
import androidx.fragment.app.FragmentManager
import com.discord.app.AppComponent
import com.discord.app.AppPermissionsRequests
import com.discord.widgets.voice.call.callAndLaunch

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
) {

    fun launchVoiceCall(channelId: Long) = callAndLaunch(channelId, isVideo = false)

    fun launchVideoCall(channelId: Long) = callAndLaunch(channelId, isVideo = true)

    private fun callAndLaunch(channelId: Long, isVideo: Boolean) = callAndLaunch(
        channelId,
        isVideo,
        appPermissionsRequests,
        context,
        appComponent,
        fragmentManager,
    )
}
