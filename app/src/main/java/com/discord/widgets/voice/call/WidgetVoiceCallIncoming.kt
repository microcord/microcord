package com.discord.widgets.voice.call

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.core.view.ViewCompat
import com.discord.utilities.user.NicknameUtils
import com.discord.R
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isMultiUserDM
import com.discord.app.AppFragment
import com.discord.stores.StoreStream
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.fcm.NotificationClient
import com.discord.utilities.media.AppSound
import com.discord.utilities.media.AppSoundManager
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.voice.VoiceStoreAccess
import com.discord.widgets.voice.fullscreen.grid.PrivateCallBlurredGridView
import com.discord.widgets.voice.fullscreen.grid.PrivateCallGridView
import com.discord.widgets.voice.model.CallModel
import com.google.android.material.floatingactionbutton.FloatingActionButton
import rx.Observable
import com.discord.utilities.user.UserUtils.displayName

/**
 * The full-screen "incoming call" surface: who is calling, and accept / accept-with-video /
 * decline.
 *
 * It renders whichever call is ringing first. Answering a call that is the *only* one ringing goes
 * through the normal private-call launch, so the callee's own microphone permission is requested
 * before connecting; answering while several are ringing skips that and just switches channels,
 * because the user is already in a call and has already granted it.
 */
open class WidgetVoiceCallIncoming : AppFragment(R.layout.widget_voice_call_incoming) {

    /**
     * @param privateCallUserListItems who to draw in the avatar grid. For a group DM that is
     *   everyone connected *or* ringing; for a one-to-one DM it is everyone except me — my own tile
     *   would just be a picture of myself while my phone rings.
     */
    data class Model(
        val callModel: CallModel,
        val privateCallUserListItems: List<StoreVoiceParticipants.VoiceUser>,
        val numIncomingCalls: Int,
    ) {
        val channelId: Long get() = callModel.channel.id

        companion object {
            fun get(): Observable<Model?> = StoreStream.getCallsIncoming().observeIncoming()
                .switchMap<Model?> { incomingCalls ->
                    val first = incomingCalls.firstOrNull()
                        ?: return@switchMap Observable.just(null)
                    CallModel.get(first).map { callModel ->
                        if (callModel == null) {
                            null
                        } else {
                            Model(
                                callModel = callModel,
                                privateCallUserListItems = callModel.participants.values
                                    .filter { it.isShownWhileRinging(callModel) },
                                numIncomingCalls = incomingCalls.size,
                            )
                        }
                    }
                }

            private fun StoreVoiceParticipants.VoiceUser.isShownWhileRinging(callModel: CallModel) =
                if (callModel.channel.isMultiUserDM()) isConnected || isRinging else !isMe
        }
    }

    private lateinit var blurredBackground: PrivateCallBlurredGridView
    private lateinit var usersGrid: PrivateCallGridView
    private lateinit var statusPrimary: TextView
    private lateinit var statusSecondary: TextView
    private lateinit var acceptButton: FloatingActionButton
    private lateinit var acceptVideoButton: FloatingActionButton
    private lateinit var acceptVideoContainer: LinearLayout
    private lateinit var declineButton: FloatingActionButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        blurredBackground = view.findViewById(R.id.incoming_call_blurred_view)
        usersGrid = view.findViewById(R.id.incoming_call_users_grid_view)
        statusPrimary = view.findViewById(R.id.incoming_call_status_primary)
        statusSecondary = view.findViewById(R.id.incoming_call_status_secondary)
        acceptButton = view.findViewById(R.id.incoming_call_accept_button)
        acceptVideoButton = view.findViewById(R.id.incoming_call_accept_video_button)
        acceptVideoContainer = view.findViewById(R.id.incoming_call_accept_alt_container)
        declineButton = view.findViewById(R.id.incoming_call_decline_button)

        // The screen is edge-to-edge behind a blurred backdrop, so it draws into the display cutout
        // and takes the status-bar inset as padding itself.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requireActivity().window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        ViewCompat.setOnApplyWindowInsetsListener(view as ViewGroup) { target, insets ->
            target.setPadding(
                target.paddingLeft,
                insets.systemWindowInsetTop,
                target.paddingRight,
                insets.systemWindowInsetBottom,
            )
            insets.consumeSystemWindowInsets()
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        Model.get()
            .ui(this)
            .appSubscribe(WidgetVoiceCallIncoming::class.java) { configureUI(it) }
    }

    open fun configureUI(model: Model?) {
        if (model == null) {
            onEmptyCallModel()
            return
        }
        val callModel = model.callModel

        // Backing out of an incoming call is a decline, not a dismissal — otherwise the caller
        // would be left ringing forever.
        setOnBackPressed({
            onDecline(model.channelId)
            true
        })

        AppSoundManager.Provider.get().play(AppSound.SOUND_CALL_RINGING)

        statusPrimary.text = if (callModel.channel.isMultiUserDM()) {
            callModel.channel.getDisplayName()
        } else {
            NicknameUtils.getName(null, callModel.channel.id, callModel.dmRecipient?.user)
        }
        statusSecondary.setText(
            if (callModel.isVideoCall) R.string.incoming_video_call else R.string.incoming_call,
        )

        usersGrid.configure(model.privateCallUserListItems)
        blurredBackground.configure(model.privateCallUserListItems)

        acceptVideoContainer.visibility = if (callModel.isVideoCall) View.VISIBLE else View.GONE
        acceptVideoButton.setOnClickListener { onConnect(model, isVideo = true) }
        acceptButton.setOnClickListener { onConnect(model, isVideo = false) }
        declineButton.setOnClickListener { onDecline(model.channelId) }
    }

    open fun onConnect(model: Model, isVideo: Boolean) {
        val channelId = model.channelId
        if (model.numIncomingCalls == 1) {
            callAndLaunch(
                channelId = channelId,
                isVideo = isVideo,
                appPermissionsRequests = this,
                context = requireContext(),
                appComponent = this,
                fragmentManager = parentFragmentManager,
            )
        } else {
            // Already in a call, so the permissions are settled; just move.
            if (isVideo) VoiceStoreAccess.setVideoEnabled(true)
            StoreStream.getVoiceChannelSelected().selectVoiceChannel(channelId).subscribe({}, {})
        }
        NotificationClient.clear(channelId, requireContext())
    }

    @MainThread
    open fun onDecline(channelId: Long) {
        StoreStream.getCalls().stopRinging(channelId)
        NotificationClient.clear(channelId, requireContext())
    }

    /** The call ended or was answered elsewhere while this screen was up. */
    open fun onEmptyCallModel() {
        appActivity?.finish()
    }

    override fun onStop() {
        AppSoundManager.Provider.get().stop(AppSound.SOUND_CALL_RINGING)
        super.onStop()
    }
}
