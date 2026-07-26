package com.discord.app

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.Window
import androidx.annotation.CallSuper
import androidx.annotation.LayoutRes
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import com.discord.R
import rx.subjects.PublishSubject
import rx.subjects.Subject

/**
 * Base class for modal dialogs.
 *
 * Uses the same [onViewBound] / [onViewBoundOrOnResume] split and [unsubscribeSignal] contract as
 * [AppFragment], adds a transparent, title-less window, and guards `show`/`dismiss` against the
 * state-loss and duplicate-show cases that routinely happen when a dialog is opened from an
 * asynchronous callback.
 */
abstract class AppDialog : DialogFragment, AppComponent {

    constructor() : super()

    constructor(@LayoutRes contentLayoutId: Int) : super(contentLayoutId)

    final override val unsubscribeSignal: Subject<Void, Void> = PublishSubject.create()

    /** `true` when the view was recreated from saved state rather than entered fresh. */
    var isRecreated: Boolean = false
        private set

    private var onViewBoundOrOnResumeInvoked: Boolean = false

    val appActivity: AppActivity?
        get() = activity as? AppActivity

    val argumentsOrDefault: Bundle
        get() = arguments ?: Bundle()

    // region lifecycle

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            applyReducedMotionAnimations()
        }

    override fun onStart() {
        try {
            super.onStart()
        } catch (e: Exception) {
            AppLog.e("Failed to start AppDialog: ${javaClass.name}", e)
        }
        // The dialog's own background is drawn by its content layout.
        dialog?.window?.setBackgroundDrawableResource(R.color.transparent)
    }

    @CallSuper
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isRecreated = savedInstanceState != null
        drawUnderSystemBars(view)
        onViewBound(view)
        onViewBoundOrOnResume()
        onViewBoundOrOnResumeInvoked = true
    }

    /** Called once per created view, before the first [onViewBoundOrOnResume]. */
    @CallSuper
    open fun onViewBound(view: View) = Unit

    /** Called after [onViewBound] and again on every subsequent resume. */
    @CallSuper
    open fun onViewBoundOrOnResume() = Unit

    override fun onResume() {
        super.onResume()
        if (onViewBoundOrOnResumeInvoked) onViewBoundOrOnResumeInvoked = false else onViewBoundOrOnResume()
    }

    override fun onPause() {
        super.onPause()
        unsubscribeSignal.onNext(null)
    }

    // endregion

    // region show / dismiss

    /**
     * Shows the dialog. A detached fragment manager cannot commit, and committing after the host
     * saved its state throws; neither is worth crashing the app for.
     */
    override fun show(manager: FragmentManager, tag: String?) {
        if (isDetached) {
            AppLog.e(
                "Could not show dialog because of detached FragmentManager",
                IllegalStateException("failed to show ${javaClass.name}"),
            )
            return
        }
        if (manager.findFragmentByTag(tag) != null) return
        try {
            super.show(manager, tag)
        } catch (e: IllegalStateException) {
            AppLog.w("Could not show ${javaClass.simpleName} after state loss", e)
        }
    }

    override fun show(transaction: FragmentTransaction, tag: String?): Int = try {
        super.show(transaction, tag)
    } catch (e: IllegalStateException) {
        AppLog.w("Could not show ${javaClass.simpleName} after state loss", e)
        -1
    }

    override fun dismiss() {
        try {
            super.dismiss()
        } catch (e: IllegalStateException) {
            AppLog.w("Could not dismiss ${javaClass.simpleName} after state loss", e)
        }
    }

    /** Runs [onClickListener] and then dismisses the dialog. */
    protected fun View.setOnClickAndDismissListener(onClickListener: (View) -> Unit) {
        setOnClickListener {
            onClickListener(this)
            dismiss()
        }
    }

    // endregion

    // region keyboard

    fun showKeyboard(view: View) {
        appActivity?.showKeyboard(view)
    }

    fun hideKeyboard(view: View? = null) {
        appActivity?.hideKeyboard(view)
    }

    // endregion
}

/**
 * Replaces the default slide animations with a cross fade when the user asked for reduced motion.
 *
 * The accessibility store is read defensively: dialogs can be constructed before the store graph
 * exists in tests and during early boot.
 */
internal fun Dialog.applyReducedMotionAnimations() {
    val reducedMotion = runCatching {
        com.discord.stores.StoreStream.getAccessibility().isReducedMotionEnabled()
    }.getOrDefault(false)
    if (reducedMotion) window?.setWindowAnimations(R.style.FadeInOut)
}
