package com.discord.widgets.user.account

import android.content.Intent
import android.view.View
import androidx.annotation.LayoutRes
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.app.AppScreenRouter
import com.discord.models.requiredaction.RequiredAction
import com.discord.models.user.MeUser
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationBuffered
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils.hasPhone
import com.discord.widgets.tabs.WidgetTabsHost
import rx.Observable

/**
 * Shared behaviour of the five "prove this account is yours" screens
 * ([WidgetUserAccountVerify], `WidgetUserEmailUpdate`, `WidgetUserEmailVerify`,
 * `WidgetUserPhoneManage`, `WidgetUserPhoneVerify`).
 *
 * Three things are common to all of them:
 *
 * 1. **A [Mode]**, carried on the launch intent. It decides whether the screen can be escaped
 *    ([Mode.FORCED] replaces the back arrow with a logout menu), and whether satisfying the
 *    requirement should bounce the user back to the app or leave them where they are.
 * 2. **Which factors this particular entry point accepts** ([isPhoneAllowed] / [isEmailAllowed]),
 *    also carried on the intent. A "verify your e-mail" gate must not be dismissed by adding a
 *    phone number, and vice versa.
 * 3. **A self-dismiss watch.** The gate is driven by the server (`required_action`) and by the
 *    user's own record, both of which can change from another device or from a background
 *    dispatch. Rather than each screen polling, the base subscribes once and leaves for the tab
 *    host the moment the requirement is satisfied.
 */
abstract class WidgetUserAccountVerifyBase(@LayoutRes contentLayoutId: Int) : AppFragment(contentLayoutId) {

    /**
     * How the user got here, and therefore how they are allowed to leave.
     *
     * Ordinals are load-bearing: the value travels through [Intent] as an ordinal, exactly as the
     * reference does. Verified against the enum's `<clinit>` in the DEX —
     * `UNFORCED(0)`, `FORCED(1)`, `NO_HISTORY_FROM_USER_SETTINGS(2)` — because the Kotlin decompile
     * prints the constants alphabetically, which would have silently swapped them.
     */
    enum class Mode {
        /** Entered voluntarily; the screen dismisses itself as soon as the requirement is met. */
        UNFORCED,

        /** Entered because the account is locked out. No back arrow, only "log out". */
        FORCED,

        /**
         * Entered from account settings. The screen never self-dismisses (settings decides where
         * the user goes next), and it is launched `NO_HISTORY` so Back skips it.
         */
        NO_HISTORY_FROM_USER_SETTINGS,
    }

    /** Whether adding/verifying a phone number can satisfy the gate this screen is enforcing. */
    protected var isPhoneAllowed: Boolean = true
        private set

    /** Whether verifying an e-mail address can satisfy the gate this screen is enforcing. */
    protected var isEmailAllowed: Boolean = true
        private set

    /**
     * Defaults to [Mode.FORCED] — the most restrictive value — so that a screen launched with a
     * malformed intent cannot accidentally present itself as escapable in the moment between the
     * bad intent being detected and the activity finishing.
     */
    protected var mode: Mode = Mode.FORCED
        private set

    val isForced: Boolean
        get() = mode == Mode.FORCED

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val modeOrdinal = mostRecentIntent.getIntExtra(INTENT_MODE, -1)
        val modes = Mode.values()
        if (modeOrdinal < 0 || modeOrdinal >= modes.size) {
            // Without a mode the screen cannot decide whether it may be escaped, so it is safer to
            // close it than to guess. The reference logs and finishes for the same reason.
            AppLog.e("Invalid mode passed into ${javaClass.name}")
            activity?.finish()
            return
        }
        mode = modes[modeOrdinal]
        setOptionsMenu()
        isPhoneAllowed = mostRecentIntent.getBooleanExtra(INTENT_PHONE_ALLOWED, isPhoneAllowed)
        isEmailAllowed = mostRecentIntent.getBooleanExtra(INTENT_EMAIL_ALLOWED, isEmailAllowed)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        observeShouldDismiss()
            .ui(this)
            .appSubscribe(javaClass) { shouldDismiss -> handleIsAuthorized(shouldDismiss) }
    }

    /**
     * `true` once the gate no longer applies, given what the account currently looks like and what
     * the server is still asking for.
     */
    private fun observeShouldDismiss(): Observable<Boolean> = Observable
        .combineLatest(
            StoreStream.getUserRequiredActions().observeUserRequiredAction(),
            StoreStream.getUsers().observeMe(),
        ) { requiredAction: RequiredAction, me: MeUser ->
            val isMissingPhone = isPhoneAllowed && !me.hasPhone
            val isMissingEmailOrUnverified = isEmailAllowed && !me.isVerified
            computeShouldDismiss(mode, isMissingPhone, isMissingEmailOrUnverified, requiredAction)
        }
        .computationBuffered()
        .distinctUntilChanged()

    /**
     * Note the inverted sense inherited from the reference: the observable emits "this screen may
     * be dismissed", so it is `false` that means "the gate still applies" and sends the user back
     * to the app.
     */
    private fun handleIsAuthorized(shouldDismiss: Boolean) {
        val context = context ?: return
        if (shouldDismiss) return
        StoreStream.getTabsNavigation().selectHomeTab(StoreNavigation.PanelAction.CLOSE, true)
        AppScreenRouter.launch(context, WidgetTabsHost::class.java)
    }

    private fun setOptionsMenu() {
        // A forced screen has no back arrow, so logging out is the only way off it.
        val menuRes = if (isForced) R.menu.menu_settings_logout else R.menu.menu_empty
        setActionBarOptionsMenu(menuRes, { menuItem ->
            if (menuItem.itemId == R.id.menu_settings_log_out) {
                StoreStream.getAuthentication().logout()
            }
        })
    }

    companion object {
        private const val INTENT_EMAIL_ALLOWED = "INTENT_EMAIL_ALLOWED"
        private const val INTENT_MODE = "INTENT_MODE"
        private const val INTENT_PHONE_ALLOWED = "INTENT_PHONE_ALLOWED"

        /**
         * Whether the gate no longer applies.
         *
         * A [Mode.UNFORCED] screen is always dismissible, and a
         * [Mode.NO_HISTORY_FROM_USER_SETTINGS] one never dismisses itself — settings owns that
         * navigation.
         *
         * For [Mode.FORCED] the test is deliberately asymmetric, and matches the reference: while
         * the server demands a verified *phone*, only a missing phone keeps the screen open; while
         * it demands a verified *e-mail*, either a missing phone or an unverified e-mail does. Any
         * other required action means this screen is no longer the thing standing in the way.
         *
         * Kept as a pure function on the companion (the reference reads `this.mode` from an
         * instance method) so the rule can be unit tested without a hosted fragment.
         */
        @JvmStatic
        internal fun computeShouldDismiss(
            mode: Mode,
            isMissingPhone: Boolean,
            isMissingEmailOrUnverified: Boolean,
            requiredAction: RequiredAction,
        ): Boolean = when {
            mode == Mode.UNFORCED -> true
            mode == Mode.NO_HISTORY_FROM_USER_SETTINGS -> true
            isMissingPhone && requiredAction == RequiredAction.REQUIRE_VERIFIED_PHONE -> true
            (isMissingPhone || isMissingEmailOrUnverified) &&
                requiredAction == RequiredAction.REQUIRE_VERIFIED_EMAIL -> true
            else -> false
        }

        /** Builds the extras every screen in this package is launched with. */
        @JvmStatic
        fun getLaunchIntent(mode: Mode, phoneAllowed: Boolean, emailAllowed: Boolean): Intent =
            Intent()
                .putExtra(INTENT_PHONE_ALLOWED, phoneAllowed)
                .putExtra(INTENT_EMAIL_ALLOWED, emailAllowed)
                .putExtra(INTENT_MODE, mode.ordinal)
    }
}
