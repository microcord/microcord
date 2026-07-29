package com.discord.overlay

import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import com.discord.R
import com.discord.app.AppComponent
import com.discord.overlay.views.OverlayBubbleWrap
import com.discord.stores.StoreStream
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.facebook.drawee.view.SimpleDraweeView
import rx.Observable
import rx.subjects.PublishSubject
import rx.subjects.Subject

class OverlayVoiceBubble(context: Context) : OverlayBubbleWrap(context), AppComponent {
    val imageView: SimpleDraweeView = LayoutInflater.from(context)
        .inflate(R.layout.overlay_voice_bubble, this, false) as SimpleDraweeView

    override val unsubscribeSignal: Subject<Void, Void> = PublishSubject.create()

    init {
        addView(imageView)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        StoreStream.getVoiceChannelSelected().observeSelectedVoiceChannelId()
            .switchMap<StoreVoiceParticipants.VoiceUser?> { channelId ->
                if (channelId == 0L) {
                    Observable.just(null as StoreVoiceParticipants.VoiceUser?)
                } else {
                    StoreStream.getVoiceParticipants().get(channelId)
                        .map { participants -> participants.values.firstOrNull { it.isSpeaking } }
                        .distinctUntilChanged()
                }
            }
            .computationLatest()
            .ui(this)
            .appSubscribe(OverlayVoiceBubble::class.java, context) { voiceUser ->
                if (voiceUser == null) {
                    fadeInactive()
                } else {
                    IconUtils.setIcon(
                        imageView,
                        voiceUser.user,
                        guildMember = voiceUser.guildMember,
                    )
                    fadeActive()
                }
            }
    }

    override fun anchorTo(targetView: android.view.View) {
        super.anchorTo(targetView)
        fadeActive()
    }

    override fun unanchor(animate: Boolean) {
        fadeInactive()
        super.unanchor(animate)
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        val targetX = if (windowLayoutParams.x > getScreenBounds(context).centerX()) {
            Int.MAX_VALUE
        } else {
            Int.MIN_VALUE
        }
        super.onConfigurationChanged(newConfig)
        springToPosition(targetX, windowLayoutParams.y)
        springAnimationX.skipToEnd()
        springAnimationY.skipToEnd()
    }

    override fun onDetachedFromWindow() {
        unsubscribeSignal.onNext(null)
        super.onDetachedFromWindow()
    }

    private fun fadeActive() {
        imageView.animate().setDuration(ACTIVE_FADE_MS).alpha(1f).start()
    }

    private fun fadeInactive() {
        imageView.animate().setDuration(INACTIVE_FADE_MS).alpha(0.5f).start()
    }

    private companion object {
        const val ACTIVE_FADE_MS = 100L
        const val INACTIVE_FADE_MS = 200L
    }
}
