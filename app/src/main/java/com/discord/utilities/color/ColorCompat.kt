package com.discord.utilities.color

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.annotation.AttrRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/**
 * Colour resolution and status-bar tinting.
 *
 * Every accessor is null-tolerant: a `Context`, `Activity`, `Window` or `Fragment` that has already
 * been torn down yields `0` (or is a no-op) rather than throwing. That is what lets a view holder
 * resolve its colours from a detached `itemView`, and an async callback tint the status bar without
 * first re-checking that its fragment is still attached.
 */
object ColorCompat {

    /** Resolves a colour *resource*. Returns `0` for a null context. */
    @JvmStatic
    @ColorInt
    fun getColor(context: Context?, @ColorRes colorResourceId: Int): Int =
        if (context != null) ContextCompat.getColor(context, colorResourceId) else 0

    @JvmStatic
    @ColorInt
    fun getColor(view: View, @ColorRes colorResourceId: Int): Int =
        getColor(view.context, colorResourceId)

    @JvmStatic
    @ColorInt
    fun getColor(fragment: Fragment, @ColorRes colorResourceId: Int): Int =
        getColor(fragment.context, colorResourceId)

    /**
     * Resolves a theme *attribute* (`R.attr.colorX`) against the context's current theme.
     *
     * Returns `0` when the context is null or the attribute is not defined by the theme — an
     * unresolved attribute is transparent black, not an exception, because themes legitimately
     * differ in which optional colours they declare.
     */
    @JvmStatic
    @ColorInt
    fun getThemedColor(context: Context?, @AttrRes attributeResourceId: Int): Int {
        val typedValue = TypedValue()
        context?.theme?.resolveAttribute(attributeResourceId, typedValue, true)
        return typedValue.data
    }

    /**
     * [getThemedColor] reads `TypedValue.data`, which for an attribute pointing at a
     * `ColorStateList` resource is the resource id rather than a colour.
     */
    @JvmStatic
    @ColorInt
    fun getThemedColorStateListDefault(context: Context, @AttrRes attributeResourceId: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attributeResourceId, typedValue, true)
        return if (typedValue.type == TypedValue.TYPE_STRING) {
            AppCompatResources.getColorStateList(context, typedValue.resourceId).defaultColor
        } else {
            typedValue.data
        }
    }

    @JvmStatic
    @ColorInt
    fun getThemedColor(view: View, @AttrRes attributeResourceId: Int): Int =
        getThemedColor(view.context, attributeResourceId)

    @JvmStatic
    @ColorInt
    fun getThemedColor(fragment: Fragment, @AttrRes attributeResourceId: Int): Int =
        getThemedColor(fragment.context, attributeResourceId)

    /**
     * Whether [color] is dark enough that white foreground content will read against it.
     *
     * Uses the ITU-R BT.601 luma weights, which weight green heaviest because the eye is most
     * sensitive to it — a saturated green is "light" at a luma a saturated blue would not reach.
     * The default [darkThreshold] of `0.4` is deliberately below the midpoint: role colours sit on
     * dark chrome, so the tie-break favours treating a colour as light.
     */
    @JvmStatic
    fun isColorDark(@ColorInt color: Int, darkThreshold: Float = 0.4f): Boolean {
        val luma = (
            Color.red(color) * 0.299 +
                Color.green(color) * 0.587 +
                Color.blue(color) * 0.114
            ) / 255
        return 1 - luma >= darkThreshold
    }

    /**
     * Paints the status bar [color], replacing any translucent-status flag.
     *
     * [forceDarkUi] clears the light-status-bar appearance flag, i.e. forces *white* status icons.
     * The name reads backwards until you take "dark UI" to mean the system UI is drawn dark-themed.
     * It only has an effect from API 23, which is when that flag was introduced.
     */
    @JvmStatic
    @JvmOverloads
    @Suppress("DEPRECATION")
    fun setStatusBarColor(window: Window?, @ColorInt color: Int, forceDarkUi: Boolean = false) {
        window ?: return
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = color
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && forceDarkUi) {
            window.decorView.systemUiVisibility = 0
        }
    }

    @JvmStatic
    @JvmOverloads
    fun setStatusBarColor(activity: Activity?, @ColorInt color: Int, forceDarkUi: Boolean = false) =
        setStatusBarColor(activity?.window, color, forceDarkUi)

    @JvmStatic
    @JvmOverloads
    fun setStatusBarColor(fragment: Fragment, @ColorInt color: Int, forceDarkUi: Boolean = false) =
        setStatusBarColor(fragment.activity, color, forceDarkUi)

    @JvmStatic
    fun setStatusBarColorResourceId(window: Window?, @ColorRes colorResourceId: Int) =
        setStatusBarColor(window, getColor(window?.context, colorResourceId))

    @JvmStatic
    fun setStatusBarColorResourceId(activity: Activity?, @ColorRes colorResourceId: Int) =
        setStatusBarColorResourceId(activity?.window, colorResourceId)

    @JvmStatic
    fun setStatusBarColorResourceId(fragment: Fragment, @ColorRes colorResourceId: Int) =
        setStatusBarColorResourceId(fragment.activity, colorResourceId)

    /** Lets content draw behind a translucent status bar (media viewer, image crop). */
    @JvmStatic
    @Suppress("DEPRECATION")
    fun setStatusBarTranslucent(window: Window?) {
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
        )
    }

    @JvmStatic
    fun setStatusBarTranslucent(activity: Activity?) = setStatusBarTranslucent(activity?.window)

    @JvmStatic
    fun setStatusBarTranslucent(fragment: Fragment) = setStatusBarTranslucent(fragment.activity)

    /**
     * A state list that answers [color] for every state.
     *
     * Enabled, disabled, unchecked and pressed all map to the same value — the point is to satisfy
     * a widget that insists on a `ColorStateList` without introducing state-dependent colouring.
     */
    fun createDefaultColorStateList(@ColorInt color: Int): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_enabled),
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(-android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_pressed),
        ),
        intArrayOf(color, color, color, color),
    )

    /** `#RRGGBB`, alpha discarded — the form Discord's APIs and CSS-ish payloads expect. */
    fun getColorHexFromColorInt(@ColorInt colorInt: Int): String =
        String.format("#%06X", colorInt and 0xFFFFFF)

    /** Same RGB, fully transparent. */
    @ColorInt
    fun removeAlphaComponent(@ColorInt colorInt: Int): Int =
        Color.argb(0, Color.red(colorInt), Color.green(colorInt), Color.blue(colorInt))
}
