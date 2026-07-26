package com.discord.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.annotation.CallSuper
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.MenuRes
import androidx.annotation.StringRes
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import com.discord.R
import com.discord.utilities.view.ToastManager
import com.discord.views.ToolbarTitleLayout
import java.io.File
import java.io.IOException
import rx.subjects.PublishSubject
import rx.subjects.Subject

/**
 * Base class for every full screen surface.
 *
 * Responsibilities:
 * - splits view setup into [onViewBound] (once per view) and [onViewBoundOrOnResume] (once per view
 *   plus once per resume), and exposes [isRecreated] so a screen can tell a fresh entry from a
 *   configuration change;
 * - owns an [unsubscribeSignal] that fires on pause so subscriptions bound with
 *   `bindToComponentLifecycle` dispose themselves;
 * - proxies the hosting [AppActivity]'s action bar, keyboard, back-press and permission APIs;
 * - demultiplexes media picker activity results into [onImageChosen] / [onImageCropped].
 */
abstract class AppFragment : Fragment, AppComponent, AppPermissionsRequests, AppLogger.Provider {

    constructor() : super()

    constructor(@LayoutRes contentLayoutId: Int) : super(contentLayoutId)

    final override val unsubscribeSignal: Subject<Void, Void> = PublishSubject.create()

    /** Screens override this to report impressions; `null` means "no impression logging". */
    override val loggingConfig: LoggingConfig?
        get() = null

    val appLogger: AppLogger by lazy { AppLogger(this) }

    /** `true` when the view was recreated from saved state rather than entered fresh. */
    var isRecreated: Boolean = false
        private set

    private var onViewBoundOrOnResumeInvoked: Boolean = false

    val appActivity: AppActivity?
        get() = activity as? AppActivity

    fun requireAppActivity(): AppActivity = checkNotNull(appActivity) {
        "${javaClass.name} is not hosted by an AppActivity."
    }

    /** The intent that most recently entered the host activity, never `null`. */
    val mostRecentIntent: Intent
        get() = appActivity?.mostRecentIntent ?: Intent()

    // region lifecycle

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindToolbar(view)
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
    open fun onViewBoundOrOnResume() {
        appLogger.logImpression()
    }

    override fun onResume() {
        super.onResume()
        // onViewCreated already ran it for this resume pass.
        if (onViewBoundOrOnResumeInvoked) onViewBoundOrOnResumeInvoked = false else onViewBoundOrOnResume()
    }

    override fun onPause() {
        unsubscribeSignal.onNext(null)
        super.onPause()
    }

    override fun onDestroyView() {
        appActivity?.backPressHandlers?.remove(backPressKey)
        super.onDestroyView()
    }

    override fun onDetach() {
        appActivity?.removeOnNewIntentListener(id)
        super.onDetach()
    }

    // endregion

    // region action bar

    /**
     * Promotes the `action_bar_toolbar` inside [view] to be the host activity's action bar.
     *
     * Screens carry their own toolbar in their layout, so the activity's toolbar reference follows
     * whichever screen is currently bound.
     */
    @CallSuper
    open fun bindToolbar(view: View? = this.view) {
        val toolbar = view?.findViewById<Toolbar>(R.id.action_bar_toolbar) ?: return
        val activity = appActivity ?: return
        if (activity.toolbar !== toolbar) activity.setToolbar(toolbar)
    }

    val actionBarTitleLayout: ToolbarTitleLayout?
        get() = appActivity?.toolbarTitleLayout

    fun setActionBarTitle(
        title: CharSequence?,
        @DrawableRes leadingDrawableRes: Int? = null,
        @DrawableRes trailingDrawableRes: Int? = null,
    ) {
        actionBarTitleLayout?.setTitle(title, leadingDrawableRes, trailingDrawableRes)
    }

    fun setActionBarTitle(@StringRes titleResId: Int) = setActionBarTitle(getString(titleResId))

    fun setActionBarSubtitle(subtitle: CharSequence?) {
        actionBarTitleLayout?.setSubtitle(subtitle)
    }

    fun setActionBarSubtitle(@StringRes subtitleResId: Int) = setActionBarSubtitle(getString(subtitleResId))

    fun setActionBarTitleColor(@ColorInt color: Int) {
        actionBarTitleLayout?.setTitleColor(color)
    }

    fun setActionBarTitleClick(onClickListener: View.OnClickListener) {
        actionBarTitleLayout?.setOnClickListener(onClickListener)
    }

    /** Moves accessibility focus to the title, used after an in-place screen change. */
    fun setActionBarTitleAccessibilityViewFocused() {
        actionBarTitleLayout?.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    /** Makes the whole toolbar tappable, for titles that open a details surface. */
    fun setActionBarTitleLayoutExpandedTappableArea() {
        actionBarTitleLayout?.layoutParams = Toolbar.LayoutParams(
            Toolbar.LayoutParams.MATCH_PARENT,
            Toolbar.LayoutParams.MATCH_PARENT,
        )
    }

    /** Restricts the tappable area back to the title text itself. */
    fun setActionBarTitleLayoutMinimumTappableArea() {
        actionBarTitleLayout?.layoutParams = Toolbar.LayoutParams(
            Toolbar.LayoutParams.WRAP_CONTENT,
            Toolbar.LayoutParams.WRAP_CONTENT,
        )
    }

    fun setActionBarOptionsMenu(
        @MenuRes menuResId: Int,
        onSelected: (MenuItem) -> Unit,
        onConfigured: ((Menu) -> Unit)? = null,
    ): Toolbar? {
        val toolbar = appActivity?.toolbar ?: return null
        toolbar.menu?.clear()
        toolbar.inflateMenu(menuResId)
        toolbar.setOnMenuItemClickListener { menuItem ->
            onSelected(menuItem)
            true
        }
        toolbar.menu?.let { menu -> onConfigured?.invoke(menu) }
        return toolbar
    }

    fun setActionBarDisplayHomeAsUpEnabled(
        showHomeAsUp: Boolean = true,
        @DrawableRes iconRes: Int? = null,
        @StringRes iconAccessibilityLabel: Int? = null,
    ): Toolbar? = appActivity?.setActionBarDisplayHomeAsUpEnabled(showHomeAsUp, iconRes, iconAccessibilityLabel)

    // endregion

    // region keyboard, back press and intents

    fun showKeyboard(view: View) {
        appActivity?.showKeyboard(view)
    }

    fun hideKeyboard(view: View? = null) {
        appActivity?.hideKeyboard(view)
    }

    /**
     * Registers a back-press handler for this screen.
     *
     * Lower [priority] values run first. The handler returns `true` when it consumed the press.
     * The registration is keyed by fragment class and dropped in [onDestroyView].
     */
    fun setOnBackPressed(handler: () -> Boolean, priority: Int = BackPressHandlerRegistry.DEFAULT_PRIORITY) {
        appActivity?.backPressHandlers?.set(backPressKey, priority, handler)
    }

    /** Observes intents delivered to the host activity while this fragment is attached. */
    fun setOnNewIntentListener(action: (Intent) -> Unit) {
        appActivity?.setOnNewIntentListener(id, action)
    }

    private val backPressKey: String
        get() = javaClass.name

    // endregion

    // region media picking

    /** Opens the shared image chooser, requesting the media permission group first. */
    @Suppress("DEPRECATION")
    fun openMediaChooser() {
        requestMedia {
            val context = context ?: return@requestMedia
            try {
                val captureUri = AppMediaPicker.createCaptureUri(context, AppMediaPicker.createCaptureFile(context))
                val chooser = AppMediaPicker.createChooserIntent(
                    context,
                    getString(R.string.choose_an_application),
                    IMAGE_MIME_TYPE,
                    captureUri,
                )
                AppMediaPicker.prepareCapture(
                    context,
                    Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE),
                    captureUri,
                )
                startActivityForResult(chooser, MediaPickerRequest.CHOOSER.requestCode)
            } catch (e: IOException) {
                onMediaPickerFailed(e)
            } catch (e: android.content.ActivityNotFoundException) {
                onMediaPickerFailed(IOException("No application available for media picker.", e))
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val context = context ?: return
        when (val result = AppMediaPicker.handleActivityResult(context, requestCode, resultCode, data)) {
            null, AppMediaPicker.Result.Cancelled -> Unit
            is AppMediaPicker.Result.Failed -> onMediaPickerFailed(result.cause)
            is AppMediaPicker.Result.Picked -> {
                context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, result.uri))
                val mimeType = context.contentResolver.getType(result.uri) ?: IMAGE_MIME_TYPE
                if (result.request == MediaPickerRequest.CROP) {
                    onImageCropped(result.uri, mimeType)
                } else {
                    onImageChosen(result.uri, mimeType)
                }
            }
        }
    }

    /** Destination file for a camera capture started by this screen. */
    val imageFile: File
        get() = AppMediaPicker.createCaptureFile(requireContext())

    open fun onImageChosen(uri: Uri, mimeType: String) = Unit

    open fun onImageCropped(uri: Uri, mimeType: String) = Unit

    private val toastManager = ToastManager()

    private fun onMediaPickerFailed(cause: Exception) {
        AppLog.w("Media picker failed in ${javaClass.simpleName}", cause)
        val context = context ?: return
        toastManager.show(context, context.getNamedFormatString(R.string.upload_open_file_failed, cause.message.orEmpty()))
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

    // endregion

    private companion object {
        const val IMAGE_MIME_TYPE = "image/*"
    }
}

/** Lets a screen render behind the status and navigation bars while still receiving insets. */
internal fun drawUnderSystemBars(view: View) {
    view.fitsSystemWindows = true
    @Suppress("DEPRECATION")
    view.systemUiVisibility = view.systemUiVisibility or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
}
