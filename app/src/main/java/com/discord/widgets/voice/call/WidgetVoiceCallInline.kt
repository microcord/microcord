package com.discord.widgets.voice.call

import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.app.AppFragment
import com.discord.rtcconnection.mediaengine.MediaEngineConnection
import com.discord.stores.StoreStream
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.widgets.voice.VoiceStoreAccess
import com.google.android.material.button.MaterialButton
import rx.Observable

/**
 * The push-to-talk bar pinned under the chat while the user is connected to a voice channel.
 *
 * It is permanently mounted in `widget_home_panel_center_chat.xml` and shows itself only when the
 * user is both connected *and* using push-to-talk — with voice activity there is nothing to press.
 */
class WidgetVoiceCallInline : AppFragment(R.layout.widget_voice_call_inline) {

    /**
     * What the bar needs to know. [voiceConnected] is `null` whenever no voice channel is selected,
     * which is what keeps the bar hidden outside a call.
     */
    data class Model(val voiceConnected: VoiceConnected?) {

        data class VoiceConnected(
            val selectedVoiceChannel: Channel,
            val inputMode: MediaEngineConnection.InputMode,
        ) {
            companion object {
                fun get(): Observable<VoiceConnected?> = StoreStream.getVoiceChannelSelected()
                    .observeSelectedChannel()
                    .switchMap<VoiceConnected?> { channel ->
                        if (channel == null) {
                            Observable.just(null)
                        } else {
                            StoreStream.getMediaSettings().getInputMode()
                                .map { inputMode -> VoiceConnected(channel, inputMode) }
                        }
                    }
                    .computationLatest()
            }
        }

        companion object {
            fun get(): Observable<Model> = VoiceConnected.get().map(::Model)
        }
    }

    private lateinit var pushToTalkButton: MaterialButton
    private var pushToTalkPressed = false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        pushToTalkButton = view.findViewById(R.id.voice_connected_ptt)
        pushToTalkButton.setOnTouchListener { _, event ->
            val pressed = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> true
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_POINTER_UP,
                -> false
                else -> null
            }
            if (pressed != null && pressed != pushToTalkPressed) {
                pushToTalkPressed = pressed
                VoiceStoreAccess.setPttActive(pressed)
            }
            false
        }
        // The reference also registers this button with `PanelsChildGestureRegionObserver` so a
        // horizontal drag that starts on it does not open the home panel. That observer class does
        // not exist in this reconstruction — `OverlappingPanelsLayout.setChildGestureRegions` is
        // there and takes the regions, but nothing collects them — so holding PTT and drifting
        // sideways currently opens the panel. See the handoff report.
        setWindowInsetsListeners(view)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        Model.get()
            .ui(this)
            .appSubscribe(WidgetVoiceCallInline::class.java) { configureUI(it) }
    }

    override fun onStop() {
        // Never leave the microphone latched open because the screen went away mid-press.
        if (pushToTalkPressed) {
            pushToTalkPressed = false
            VoiceStoreAccess.setPttActive(false)
        }
        super.onStop()
    }

    private fun configureUI(model: Model) {
        val isPushToTalk =
            model.voiceConnected?.inputMode == MediaEngineConnection.InputMode.PUSH_TO_TALK
        view?.visibility = if (isPushToTalk) View.VISIBLE else View.GONE
    }

    /**
     * The bar is the bottom-most chrome in the chat panel, so it absorbs the navigation-bar inset
     * itself rather than letting the button sit under the system bar.
     */
    private fun setWindowInsetsListeners(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            target.setPadding(
                target.paddingLeft,
                target.paddingTop,
                target.paddingRight,
                insets.systemWindowInsetBottom,
            )
            insets.consumeSystemWindowInsets()
        }
    }
}
