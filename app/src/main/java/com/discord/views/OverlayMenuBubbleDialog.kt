package com.discord.views

import android.animation.Animator
import android.animation.AnimatorInflater
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppComponent
import com.discord.overlay.views.OverlayDialog
import com.discord.stores.StoreStream
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.PublishSubject
import rx.subjects.Subject

/**
 * Base class for the full-screen dialogs presented by the voice overlay bubble.
 *
 * The dialog fills the overlay window, dims what is behind it, closes when the scrim is tapped,
 * and animates itself out before telling the overlay host to detach it.
 *
 */
abstract class OverlayAppDialog(context: Context) : OverlayDialog(context), AppComponent {

    override val unsubscribeSignal: Subject<Void, Void> = PublishSubject.create<Void>()

    /** Invoked once the dialog has finished closing so the overlay host can detach it. */
    var onDialogClosed: (OverlayDialog) -> Unit = {}

    init {
        setOnClickListener { close() }
    }

    /** Animator played while the dialog closes; the dialog is detached when it ends. */
    protected abstract fun getClosingAnimator(): Animator

    /**
     * Tears down subscriptions, plays the closing animation, and notifies [onDialogClosed]. When
     * the dialog is not attached there is nothing to animate, so it closes immediately.
     */
    fun close() {
        unsubscribeSignal.onNext(null)
        if (!ViewCompat.isAttachedToWindow(this)) {
            onDialogClosed(this)
            return
        }
        val closingAnimator = getClosingAnimator()
        closingAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                onDialogClosed(this@OverlayAppDialog)
            }
        })
        closingAnimator.start()
        // If the window goes away mid-animation, end it so onDialogClosed still runs.
        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                closingAnimator.end()
            }
        })
    }

    override fun onDetachedFromWindow() {
        unsubscribeSignal.onNext(null)
        super.onDetachedFromWindow()
    }
}

/**
 * The expanded voice overlay bubble: the participant strip that lines up with the collapsed
 * bubble, plus the [OverlayMenuView] with the call controls.
 *
 * The header keeps a placeholder view at the exact size and position of the collapsed bubble
 * ([linkedAnchorView]) so the overlay host can animate the bubble into place, and shows at most
 * [MAX_VISIBLE_MEMBERS] other participants with a `+n` overflow chip.
 */
class OverlayMenuBubbleDialog(context: Context) : OverlayAppDialog(context) {

    private val header: LinearLayout
    private val linkedAnchor: View
    private val membersOverflowText: TextView
    private val membersRecycler: RecyclerView
    private val adapter = VoiceUserAdapter()

    /** The call controls menu, so the overlay host can wire its actions to the voice stores. */
    val overlayMenu: OverlayMenuView

    /**
     * Placeholder occupying the collapsed bubble's slot in the header, used by the overlay host to
     * line the expanded dialog up with the bubble it grew out of.
     */
    val linkedAnchorView: View
        get() = linkedAnchor

    init {
        val content = LayoutInflater.from(context)
            .inflate(R.layout.overlay_bubble_menu, this, false)
        addView(content)

        header = requireNotNull(content.findViewById(R.id.overlay_header))
        linkedAnchor = requireNotNull(content.findViewById(R.id.overlay_linked_anchor_view))
        membersOverflowText = requireNotNull(content.findViewById(R.id.overlay_members_overflow_tv))
        membersRecycler = requireNotNull(content.findViewById(R.id.overlay_members_rv))
        overlayMenu = requireNotNull(content.findViewById(R.id.overlay_menu))

        overlayMenu.setOnDismissRequested { close() }
        clipChildren = false
    }

    private fun updateVoiceUsers(voiceUsers: List<StoreVoiceParticipants.VoiceUser>) {
        val (visibleUsers, overflowCount) = visibleParticipants(voiceUsers) { it.isMe }
        adapter.setData(visibleUsers)
        if (overflowCount == 0) {
            membersOverflowText.text = null
            membersOverflowText.visibility = View.GONE
        } else {
            membersOverflowText.text = "+$overflowCount"
            membersOverflowText.visibility = View.VISIBLE
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // Both halves start invisible and slide in towards each other from the bubble.
        overlayMenu.alpha = 0f
        header.alpha = 0f
        AnimatorSet().apply {
            playTogether(
                loadAnimator(R.animator.overlay_slide_up_fade_in, overlayMenu),
                loadAnimator(R.animator.overlay_slide_down_fade_in, header),
            )
            startDelay = shortAnimationDuration()
        }.start()

        membersRecycler.adapter = adapter
        StoreStream.getVoiceChannelSelected().observeSelectedVoiceChannelId()
            .switchMap { channelId ->
                StoreStream.getChannels().observeChannel(channelId).switchMap { channel ->
                    if (channel == null) {
                        Observable.just(emptyList<StoreVoiceParticipants.VoiceUser>())
                    } else {
                        StoreStream.getVoiceParticipants().get(channelId)
                            .map { participants -> participants.values.toList() }
                            .distinctUntilChanged()
                    }
                }
            }
            .computationLatest()
            .ui(this)
            .appSubscribe(OverlayMenuBubbleDialog::class.java, context, onNext = ::updateVoiceUsers)
    }

    override fun getClosingAnimator(): Animator = AnimatorSet().apply {
        startDelay = shortAnimationDuration()
        playTogether(
            loadAnimator(R.animator.overlay_slide_down_fade_out, overlayMenu),
            loadAnimator(R.animator.overlay_slide_up_fade_out, header),
        )
    }

    /**
     * A rotation re-lays out the whole overlay window, and the dialog's position is meaningless
     * afterwards, so it is dismissed rather than repositioned.
     */
    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        onDialogClosed(this)
    }

    private fun loadAnimator(animatorRes: Int, target: View): Animator =
        AnimatorInflater.loadAnimator(target.context, animatorRes).apply { setTarget(target) }

    private fun shortAnimationDuration(): Long =
        resources.getInteger(android.R.integer.config_shortAnimTime).toLong()

    /** Horizontal strip of call participants, rendered as plain avatars with voice state rings. */
    private class VoiceUserAdapter :
        RecyclerView.Adapter<VoiceUserAdapter.VoiceUserViewHolder>() {

        private var data: List<StoreVoiceParticipants.VoiceUser> = emptyList()

        fun setData(data: List<StoreVoiceParticipants.VoiceUser>) {
            this.data = data
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = data.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VoiceUserViewHolder =
            VoiceUserViewHolder(
                LayoutInflater.from(parent.context).inflate(
                    R.layout.overlay_menu_voice_members_item,
                    parent,
                    false,
                ) as VoiceUserView,
            )

        override fun onBindViewHolder(holder: VoiceUserViewHolder, position: Int) =
            holder.bind(data[position])

        class VoiceUserViewHolder(private val voiceUserView: VoiceUserView) :
            RecyclerView.ViewHolder(voiceUserView) {

            fun bind(voiceUser: StoreVoiceParticipants.VoiceUser) =
                voiceUserView.setVoiceUser(voiceUser, R.dimen.avatar_size_extra_large)
        }
    }

    companion object {
        /** Participants shown before the `+n` overflow chip takes over. */
        const val MAX_VISIBLE_MEMBERS = 3

        internal fun <T> visibleParticipants(
            participants: List<T>,
            isCurrentUser: (T) -> Boolean,
        ): Pair<List<T>, Int> {
            val others = participants.filterNot(isCurrentUser)
            return others.take(MAX_VISIBLE_MEMBERS) to
                (others.size - MAX_VISIBLE_MEMBERS).coerceAtLeast(0)
        }
    }
}
