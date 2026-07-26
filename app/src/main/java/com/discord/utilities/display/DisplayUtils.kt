@file:JvmName("DisplayUtils")

package com.discord.utilities.display

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.core.view.OnApplyWindowInsetsListener

/**
 * Screen geometry and system-bar helpers.
 *
 * These are the queries the app makes about the *physical* display rather than about any particular
 * view: how large the screen is, how tall the status and navigation bars are, and whether the device
 * is using gesture navigation. The overlay module in particular has no `Activity` and no window
 * insets to read, so it computes its own bounds from here.
 */

/**
 * An insets listener that consumes nothing and changes nothing.
 *
 * Installed on views that must keep receiving insets (so the platform keeps dispatching them down
 * the hierarchy) while doing nothing with them themselves.
 */
@JvmField
val NO_OP_WINDOW_INSETS_LISTENER = OnApplyWindowInsetsListener { _, insets -> insets }

/**
 * Lets [view]'s window draw behind the status and navigation bars.
 *
 * `fitsSystemWindows` keeps the view's own padding correct while the layout-stable/hide-navigation
 * flags stop the layout from jumping when the bars come and go.
 */
fun drawUnderSystemBars(view: View) {
    view.fitsSystemWindows = true
    @Suppress("DEPRECATION")
    view.systemUiVisibility = view.systemUiVisibility or
        (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
}

/** The display this context is showing on. */
@SuppressLint("AnnotateVersionCheck")
private fun Context.getDisplayCompat(): Display =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        checkNotNull(display)
    } else {
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    }

/**
 * The full screen rectangle in pixels, anchored at the origin.
 *
 * This is the *display metrics* size, so it excludes neither the status bar nor the navigation bar —
 * which is what a `TYPE_APPLICATION_OVERLAY` window is measured against.
 */
fun getScreenSize(context: Context): Rect = Rect(
    0,
    0,
    context.resources.displayMetrics.widthPixels,
    context.resources.displayMetrics.heightPixels,
)

/** Refresh rate of the display this context is showing on, in Hz. */
fun getScreenRefreshRate(context: Context): Float = context.getDisplayCompat().refreshRate

/** Height of the system status bar in pixels, or `0` when the platform does not declare one. */
fun getStatusBarHeight(resources: Resources): Int =
    resources.getIdentifier("status_bar_height", "dimen", "android")
        .takeIf { it > 0 }
        ?.let(resources::getDimensionPixelSize)
        ?: 0

/** Height of the system navigation bar in pixels, or `0` when the platform does not declare one. */
fun getNavigationBarHeight(resources: Resources): Int =
    resources.getIdentifier("navigation_bar_height", "dimen", "android")
        .takeIf { it > 0 }
        ?.let(resources::getDimensionPixelSize)
        ?: 0

/** Whether the platform reports a software navigation bar. */
fun hasNavigationBar(resources: Resources): Boolean {
    val id = resources.getIdentifier("config_showNavigationBar", "bool", "android")
    return id > 0 && resources.getBoolean(id)
}

/**
 * Whether the device is in gesture-navigation mode.
 *
 * The framework exposes this only as a private integer resource; `2` is the gesture mode, `0` is
 * three-button and `1` is two-button. The resource does not exist before API 29.
 */
fun isGestureNavigationEnabled(resources: Resources): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val id = resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
    val mode = if (id > 0) resources.getInteger(id) else null
    return mode == NAV_BAR_INTERACTION_MODE_GESTURE
}

/**
 * Scales [`in`] down until it fits inside [bounds], preserving its aspect ratio.
 *
 * The result is always anchored at the origin — callers position it themselves. A rectangle that
 * already fits is returned at its original size.
 */
fun resizeToFit(`in`: Rect, bounds: Rect): Rect {
    var width = `in`.width()
    var height = `in`.height()

    if (width > bounds.width()) {
        height = (bounds.width().toFloat() / width * height).toInt()
        width = bounds.width()
    }
    if (height > bounds.height()) {
        width = (bounds.height().toFloat() / height * width).toInt()
        height = bounds.height()
    }

    return Rect(0, 0, width, height)
}

/** [resizeToFit] against the whole screen. */
fun resizeToFitScreen(context: Context, `in`: Rect): Rect = resizeToFit(`in`, getScreenSize(context))

private const val NAV_BAR_INTERACTION_MODE_GESTURE = 2
