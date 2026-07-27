package com.discord.widgets.home

import android.view.View
import android.view.ViewStub
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.discord.R
import com.discord.api.user.NsfwAllowance
import com.discord.app.AppComponent
import com.discord.i18n.setI18nText
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.view.text.LinkifiedTextView
import com.google.android.material.button.MaterialButton
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Subscription

/**
 * The age gate that covers the chat when an age-restricted channel is opened.
 *
 * It has two faces:
 *
 * * **Consent** — an adult (or unknown) account is asked to confirm. Accepting records the guild as
 *   agreed and reveals the chat; declining navigates away from the channel entirely, which is why
 *   the decline callback is the caller's business rather than this class's.
 * * **Blocked** — an account known to be under age ([NsfwAllowance.DISALLOWED]) cannot consent at
 *   all, so the confirm button is removed, the artwork becomes the failure illustration and the copy
 *   becomes the "you are too young" explanation with a support link.
 *
 * The layout lives behind a [ViewStub] and is only inflated the first time a gate is actually
 * needed; a user who never opens an age-restricted channel never pays for it.
 *
 * Hiding is delayed by [HIDE_DELAY_MILLIS] because the gate and the chat cross-fade: dropping the
 * gate instantly would show the chat underneath for a frame before the new channel had rendered.
 */
class WidgetHomePanelNsfw(private val appComponent: AppComponent) {

    private var container: LinearLayout? = null
    private var art: ImageView? = null
    private var confirmButton: MaterialButton? = null
    private var denyButton: MaterialButton? = null
    private var description: LinkifiedTextView? = null
    private var title: TextView? = null

    private var stubInflated = false
    private var hidePanelSubscription: Subscription? = null

    @MainThread
    fun configureUI(
        guildId: Long,
        isChannelNsfw: Boolean,
        isNsfwUnconsented: Boolean,
        nsfwAllowed: NsfwAllowance?,
        viewStub: ViewStub?,
        onToggleNsfw: (Boolean) -> Unit,
        onDeny: (() -> Unit)? = null,
    ) {
        hidePanelSubscription?.unsubscribe()
        toggleContainerVisibility(isChannelNsfw, isNsfwUnconsented, nsfwAllowed, viewStub, onToggleNsfw)
        denyButton?.setOnClickListener {
            onGuildNsfwGateDenied(guildId)
            onDeny?.invoke()
        }
        confirmButton?.setOnClickListener {
            onGuildNsfwGateAllowed(guildId)
            setContainerViewHidden(true, onToggleNsfw)
        }
    }

    /** Forwards the window insets the host consumed so the anchored buttons clear the nav bar. */
    fun dispatchApplyWindowInsets(insets: WindowInsetsCompat) {
        container?.let { ViewCompat.dispatchApplyWindowInsets(it, insets) }
    }

    /**
     * @param hidden `true` hides the gate. [onToggleNsfw] is invoked with "is the gate showing", so
     *   the caller can hide the chat list underneath it.
     */
    fun setContainerViewHidden(hidden: Boolean, onToggleNsfw: (Boolean) -> Unit) {
        val container = this.container
        if (hidden) {
            if (container != null && container.visibility == View.GONE) return
            container?.visibility = View.GONE
            onToggleNsfw(false)
        } else {
            if (container != null && container.visibility == View.VISIBLE) return
            container?.visibility = View.VISIBLE
            onToggleNsfw(true)
        }
    }

    private fun toggleContainerVisibility(
        isChannelNsfw: Boolean,
        isNsfwUnconsented: Boolean,
        nsfwAllowed: NsfwAllowance?,
        viewStub: ViewStub?,
        onToggleNsfw: (Boolean) -> Unit,
    ) {
        val isUnderage = nsfwAllowed == NsfwAllowance.DISALLOWED
        if (!isChannelNsfw || !(isNsfwUnconsented || isUnderage)) {
            Observable.timer(HIDE_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                .ui(appComponent)
                .appSubscribe(
                    WidgetHomePanelNsfw::class.java,
                    subscriptionHandler = { hidePanelSubscription = it },
                ) { setContainerViewHidden(true, onToggleNsfw) }
            return
        }

        if (!stubInflated) viewStub?.inflate()?.let { bindViews(it) }

        if (isUnderage) {
            confirmButton?.isVisible = false
            art?.setImageResource(R.drawable.img_age_gate_failure)
            denyButton?.setText(R.string.back)
            title?.setText(R.string.age_gate_nsfw_underage_header)
            description?.setI18nText(R.string.age_gate_nsfw_underage_body, "helpURL" to NSFW_HELP_ARTICLE_URL)
        } else {
            description?.setText(R.string.age_gate_nsfw_description)
        }
        setContainerViewHidden(false, onToggleNsfw)
    }

    private fun bindViews(inflated: View) {
        stubInflated = true
        container = inflated as LinearLayout
        art = inflated.findViewById(R.id.home_panel_center_nsfw_art)
        confirmButton = inflated.findViewById(R.id.home_panel_center_nsfw_confirm)
        denyButton = inflated.findViewById(R.id.home_panel_center_nsfw_deny)
        description = inflated.findViewById(R.id.home_panel_center_nsfw_description)
        title = inflated.findViewById(R.id.home_panel_center_nsfw_title)
        ViewCompat.setOnApplyWindowInsetsListener(inflated) { view, insets ->
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, insets.systemWindowInsetBottom)
            insets.consumeSystemWindowInsets()
        }
        ViewCompat.requestApplyInsets(inflated)
    }

    companion object {
        private const val HIDE_DELAY_MILLIS = 500L

        /** Discord's "Age-Restricted Channels and Content" help article. */
        private const val NSFW_HELP_ARTICLE_URL =
            "https://support.discord.com/hc/en-us/articles/115000084051"

        /**
         * Which guilds the user has agreed to enter. `StoreGuildsNsfw` exists but is not published
         * by `StoreStream`, so accepting or declining is routed through these seams. Until they are
         * wired, agreement is not persisted and the gate reappears on the next navigation.
         */
        @JvmField
        var onGuildNsfwGateAllowed: (guildId: Long) -> Unit = {}

        @JvmField
        var onGuildNsfwGateDenied: (guildId: Long) -> Unit = {}
    }
}
