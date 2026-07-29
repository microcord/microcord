package com.discord.app

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.CallSuper
import androidx.annotation.LayoutRes
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import com.discord.R
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import rx.subjects.PublishSubject
import rx.subjects.Subject
import rx.subscriptions.CompositeSubscription

/**
 * Base class for bottom sheets.
 *
 * Shares [AppFragment]'s subscription and permission contract, and adds the sheet specific pieces:
 * a declarative [getContentViewResId], keyboard-avoiding behaviour, peek-height tracking and
 * [BottomSheetBehavior] state callbacks.
 *
 * @param shouldAvoidKeyboard when `true` the sheet uses the keyboard-avoiding style and expands
 *   itself as soon as it is shown, so an input inside it stays visible.
 */
abstract class AppBottomSheet(
    private val shouldAvoidKeyboard: Boolean = false,
) : BottomSheetDialogFragment(), AppComponent, AppPermissionsRequests, AppLogger.Provider {

    final override val unsubscribeSignal: Subject<Void, Void> = PublishSubject.create()

    override val loggingConfig: LoggingConfig?
        get() = null

    private val appLogger: AppLogger by lazy { AppLogger(this) }

    private var compositeSubscription: CompositeSubscription? = null
    private var peekBottomView: View? = null
    private val peekLayoutListener = View.OnLayoutChangeListener { _, _, _, _, bottom, _, _, _, _ ->
        updatePeekHeightPx(bottom)
    }

    /** Layout inflated as the sheet content. */
    @LayoutRes
    abstract fun getContentViewResId(): Int

    val appActivity: AppActivity?
        get() = activity as? AppActivity

    fun requireAppActivity(): AppActivity = checkNotNull(appActivity) {
        "${javaClass.name} is not hosted by an AppActivity."
    }

    val argumentsOrDefault: Bundle
        get() = arguments ?: Bundle()

    // region lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (shouldAvoidKeyboard) setStyle(STYLE_NORMAL, R.style.UiKit_Sheet_KeyboardAvoiding)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setCanceledOnTouchOutside(true)
        dialog.applyReducedMotionAnimations()
        if (shouldAvoidKeyboard) {
            dialog.setOnShowListener {
                (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(getContentViewResId(), null)

    @CallSuper
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        drawUnderSystemBars(view)
        bottomSheetBehavior?.addBottomSheetCallback(bottomSheetCallback)
        appLogger.logImpression()
    }

    override fun onResume() {
        super.onResume()
        fixWindowInsetHandling()
        compositeSubscription = CompositeSubscription().also(::bindSubscriptions)
    }

    override fun onPause() {
        super.onPause()
        compositeSubscription?.unsubscribe()
        compositeSubscription = null
        unsubscribeSignal.onNext(null)
    }

    override fun onDestroyView() {
        setPeekHeightBottomView(null)
        bottomSheetBehavior?.removeBottomSheetCallback(bottomSheetCallback)
        super.onDestroyView()
    }

    /** Subscriptions created here are disposed automatically on pause. */
    open fun bindSubscriptions(compositeSubscription: CompositeSubscription) = Unit

    /** Reports [BottomSheetBehavior] state transitions. */
    open fun onStateChanged(state: Int) = Unit

    // endregion

    // region show / dismiss

    /**
     * Shows the sheet unless one with the same [tag] is already present.
     *
     * `DialogFragment.show` throws when the fragment manager already committed its state, which is
     * routine when a sheet is opened from an asynchronous callback; that case is not fatal.
     */
    override fun show(manager: FragmentManager, tag: String?) {
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

    /** Runs [onClickListener] and then dismisses the sheet. */
    protected fun View.setOnClickAndDismissListener(onClickListener: (View) -> Unit) {
        setOnClickListener {
            onClickListener(this)
            dismiss()
        }
    }

    // endregion

    // region bottom sheet behaviour

    private val bottomSheetCallback = object : BottomSheetBehavior.BottomSheetCallback() {
        override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit

        override fun onStateChanged(bottomSheet: View, newState: Int) {
            this@AppBottomSheet.onStateChanged(newState)
            if (newState == BottomSheetBehavior.STATE_HIDDEN) dismiss()
        }
    }

    private val bottomSheetBehavior: BottomSheetBehavior<View>?
        get() {
            val parent = view?.parent as? View ?: return null
            val layoutParams = parent.layoutParams as? CoordinatorLayout.LayoutParams ?: return null
            @Suppress("UNCHECKED_CAST")
            return layoutParams.behavior as? BottomSheetBehavior<View>
        }

    fun setBottomSheetState(state: Int) {
        bottomSheetBehavior?.state = state
    }

    /** Opens the sheet fully and keeps it there, resizing its content for the soft keyboard. */
    fun setBottomSheetCollapsedStateDisabled() {
        val dialog = dialog ?: return
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(MaterialR.id.design_bottom_sheet) ?: return@setOnShowListener
            BottomSheetBehavior.from(sheet).apply {
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    /**
     * Keeps the collapsed peek height in sync with the bottom of [targetView].
     *
     * Passing `null` stops tracking and leaves the peek height at its last value.
     */
    fun setPeekHeightBottomView(targetView: View?) {
        if (peekBottomView === targetView) return
        peekBottomView?.removeOnLayoutChangeListener(peekLayoutListener)
        peekBottomView = targetView
        if (targetView == null) return
        targetView.addOnLayoutChangeListener(peekLayoutListener)
        targetView.requestLayout()
    }

    fun updatePeekHeightPx(peekHeightPx: Int) {
        bottomSheetBehavior?.peekHeight = peekHeightPx
    }

    /**
     * Material's bottom sheet container consumes the system window insets before the sheet sees
     * them, which leaves the content padded twice. Re-dispatch them: the container takes only the
     * top inset, the sheet itself keeps the rest.
     */
    private fun fixWindowInsetHandling() {
        val root = view?.rootView ?: return
        val container = root.findViewById<View>(MaterialR.id.container) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            v.setPadding(v.paddingLeft, insets.systemWindowInsetTop, v.paddingRight, v.paddingBottom)
            WindowInsetsCompat.Builder(insets)
                .setSystemWindowInsets(
                    Insets.of(insets.systemWindowInsetLeft, 0, insets.systemWindowInsetRight, insets.systemWindowInsetBottom),
                )
                .build()
        }
        root.findViewById<View>(MaterialR.id.design_bottom_sheet)?.let { designBottomSheet ->
            designBottomSheet.fitsSystemWindows = true
            ViewCompat.setOnApplyWindowInsetsListener(designBottomSheet) { _, insets -> insets }
        }
        view?.let { sheetContent ->
            ViewCompat.setOnApplyWindowInsetsListener(sheetContent) { v, insets ->
                v.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
                insets.consumeSystemWindowInsets()
            }
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

    // region permissions

    override fun hasMedia(): Boolean = requireAppActivity().appPermissions.hasMedia()

    override fun requestVideoCallPermissions(onSuccess: () -> Unit) =
        requireAppActivity().appPermissions.requestVideoCallPermissions(onSuccess)

    override fun requestMedia(onSuccess: () -> Unit) =
        requireAppActivity().appPermissions.requestMedia(onSuccess)

    override fun requestMediaDownload(onSuccess: () -> Unit) =
        requireAppActivity().appPermissions.requestMediaDownload(onSuccess)

    override fun requestMicrophone(onFailure: (() -> Unit)?, onSuccess: () -> Unit) =
        requireAppActivity().appPermissions.requestMicrophone(onFailure, onSuccess)

    override fun requestCameraQRScanner(onSuccess: () -> Unit) =
        requireAppActivity().appPermissions.requestCameraQRScanner(onSuccess)

    override fun requestCameraQRScanner(onSuccess: () -> Unit, onFailure: (() -> Unit)?) =
        requireAppActivity().appPermissions.requestCameraQRScanner(onSuccess, onFailure)

    override fun requestContacts(onSuccess: () -> Unit, onFailure: () -> Unit) =
        requireAppActivity().appPermissions.requestContacts(onSuccess, onFailure)

    override fun requestNotifications(onFailure: () -> Unit, onSuccess: () -> Unit) =
        requireAppActivity().appPermissions.requestNotifications(onFailure, onSuccess)

    // endregion
}
