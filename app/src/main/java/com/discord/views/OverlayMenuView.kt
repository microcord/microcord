package com.discord.views

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.app.AppComponent
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.PublishSubject
import rx.subjects.Subject

/**
 * Call controls shown inside the floating voice overlay.
 *
 * The view observes call state itself rather than being pushed state by a host
 * fragment, because the overlay lives in a window service with no fragment
 * lifecycle. It is therefore its own [AppComponent]: the subscription starts when
 * the view is attached and is torn down by the unsubscribe signal on detach.
 */
class OverlayMenuView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs), AppComponent {
    enum class Quality { UNKNOWN, BAD, AVERAGE, FINE }

    data class ViewState(
        val channelId: Long,
        val channelPermissions: Long?,
        val guildName: String?,
        val channelName: String,
        val quality: Quality,
        val speakerphoneActive: Boolean,
        val eligibleAudioDeviceCount: Int,
        val mutedByAnySource: Boolean,
    )

    private val inviteLink: TextView
    private val switchChannels: TextView
    private val openApp: TextView
    private val networkIcon: ImageView
    private val guildName: TextView
    private val channelName: TextView
    private val sourceToggle: ImageView
    private val muteToggle: ImageView
    private val disconnect: ImageView

    override val unsubscribeSignal: Subject<Void, Void> = PublishSubject.create()

    /**
     * Call state for the selected voice channel. The reference composes this from
     * the selected voice channel, its permissions, the guild, the call model and
     * the RTC connection quality; those stores arrive with the voice phase, so the
     * host supplies the stream until then.
     */
    var viewStateSource: Observable<ViewState?>? = null
        set(value) {
            field = value
            if (isAttachedToWindow) subscribeToState()
        }

    private var state: ViewState? = null
    private var onDismissRequested: () -> Unit = {}
    private var onInviteRequested: (Long) -> Unit = {}
    private var onSwitchChannelsRequested: () -> Unit = {}
    private var onOpenAppRequested: (Long) -> Unit = {}
    private var onSourceToggleRequested: () -> Unit = {}
    private var onMuteToggleRequested: () -> Unit = {}
    private var onDisconnectRequested: () -> Unit = {}

    init {
        LayoutInflater.from(context).inflate(R.layout.view_overlay_menu, this)
        inviteLink = requireNotNull(findViewById(R.id.overlay_invite_link))
        switchChannels = requireNotNull(findViewById(R.id.overlay_switch_channels))
        openApp = requireNotNull(findViewById(R.id.overlay_open_app))
        networkIcon = requireNotNull(findViewById(R.id.overlay_network_icon))
        guildName = requireNotNull(findViewById(R.id.overlay_guild_name))
        channelName = requireNotNull(findViewById(R.id.overlay_channel_name))
        sourceToggle = requireNotNull(findViewById(R.id.src_toggle))
        muteToggle = requireNotNull(findViewById(R.id.mute_toggle))
        disconnect = requireNotNull(findViewById(R.id.disconnect_btn))

        inviteLink.setOnClickListener { state?.let { onInviteRequested(it.channelId) } }
        switchChannels.setOnClickListener {
            onSwitchChannelsRequested()
            onDismissRequested()
        }
        openApp.setOnClickListener {
            state?.let { current ->
                onOpenAppRequested(current.channelId)
                onDismissRequested()
            }
        }
        sourceToggle.setOnClickListener {
            if ((state?.eligibleAudioDeviceCount ?: 0) > 1) {
                onSourceToggleRequested()
            } else {
                Toast.makeText(context, R.string.audio_devices_toggle_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
        muteToggle.setOnClickListener { onMuteToggleRequested() }
        disconnect.setOnClickListener {
            onDismissRequested()
            onDisconnectRequested()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        subscribeToState()
    }

    override fun onDetachedFromWindow() {
        // Fires before super so the observation ends while the view is still intact.
        unsubscribeSignal.onNext(null)
        super.onDetachedFromWindow()
    }

    private fun subscribeToState() {
        val source = viewStateSource ?: return
        source
            .computationLatest()
            .distinctUntilChanged()
            .ui(this)
            .appSubscribe(OverlayMenuView::class.java, context) { updateView(it) }
    }

    fun updateView(state: ViewState?) {
        if (state == null) return
        this.state = state
        inviteLink.visibility = if (canCreateInvite(state.channelPermissions)) View.VISIBLE else View.GONE
        networkIcon.setImageResource(qualityIndicator(state.quality))
        guildName.text = state.guildName.orEmpty()
        channelName.text = state.channelName
        sourceToggle.imageTintList = ColorStateList.valueOf(
            if (state.speakerphoneActive) {
                ContextCompat.getColor(context, R.color.white)
            } else {
                ContextCompat.getColor(context, R.color.primary_dark_400)
            },
        )
        muteToggle.isActivated = state.mutedByAnySource
    }

    fun setOnDismissRequested(callback: () -> Unit) { onDismissRequested = callback }
    fun setOnInviteRequested(callback: (Long) -> Unit) { onInviteRequested = callback }
    fun setOnSwitchChannelsRequested(callback: () -> Unit) { onSwitchChannelsRequested = callback }
    fun setOnOpenAppRequested(callback: (Long) -> Unit) { onOpenAppRequested = callback }
    fun setOnSourceToggleRequested(callback: () -> Unit) { onSourceToggleRequested = callback }
    fun setOnMuteToggleRequested(callback: () -> Unit) { onMuteToggleRequested = callback }
    fun setOnDisconnectRequested(callback: () -> Unit) { onDisconnectRequested = callback }

    companion object {
        private const val CREATE_INSTANT_INVITE = 1L

        internal fun canCreateInvite(permissions: Long?) =
            permissions != null && permissions and CREATE_INSTANT_INVITE == CREATE_INSTANT_INVITE

        internal fun qualityIndicator(quality: Quality) = when (quality) {
            Quality.UNKNOWN -> R.drawable.ic_voice_quality_unknown
            Quality.BAD -> R.drawable.ic_voice_quality_bad
            Quality.AVERAGE -> R.drawable.ic_voice_quality_average
            Quality.FINE -> R.drawable.ic_voice_quality_fine
        }
    }
}
