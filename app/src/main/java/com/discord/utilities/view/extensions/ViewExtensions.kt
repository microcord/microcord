@file:JvmName("ViewExtensions")

package com.discord.utilities.view.extensions

import android.animation.Animator
import android.content.res.TypedArray
import android.graphics.drawable.Drawable
import android.text.Editable
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.ViewPropertyAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.CompoundButton
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.MainThread
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.annotation.StyleableRes
import androidx.core.view.ViewCompat
import androidx.core.view.children
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.discord.utilities.view.text.addBindedTextWatcher
import com.google.android.material.textfield.TextInputLayout

private const val DEFAULT_FADE_MILLIS = 350L
private const val HINT_DELAY_MILLIS = 250L

/**
 * Fade animations in flight, keyed by view.
 *
 * A view can be asked to fade in and out faster than an animation completes; tracking the current
 * animation lets a redundant request be ignored and a contradictory one cancel its predecessor
 * instead of leaving the view at a half-faded alpha.
 */
internal val fadeAnimations = HashMap<View, FadeAnimation>()

internal class FadeAnimation(
    val viewPropertyAnimator: ViewPropertyAnimator,
    val type: Type,
) {
    enum class Type { FADE_IN, FADE_OUT }
}

/** Removes the view's animation bookkeeping once its animation stops for any reason. */
internal class CleanupViewAnimationListener(val view: View) : Animator.AnimatorListener {
    override fun onAnimationStart(animation: Animator) = Unit
    override fun onAnimationRepeat(animation: Animator) = Unit
    override fun onAnimationEnd(animation: Animator) {
        fadeAnimations.remove(view)
    }

    override fun onAnimationCancel(animation: Animator) {
        fadeAnimations.remove(view)
    }
}

// region visibility and fading

@MainThread
fun View?.fadeIn(
    fadeMillis: Long = DEFAULT_FADE_MILLIS,
    setup: (View) -> Unit = {},
    additionalAnimation: (ViewPropertyAnimator) -> Unit = {},
    onAnimationEnd: () -> Unit = {},
) {
    val view = this ?: return
    val running = fadeAnimations[view]
    if (running?.type == FadeAnimation.Type.FADE_IN) return
    if (running != null) {
        fadeAnimations.remove(view)
        running.viewPropertyAnimator.cancel()
    }

    if (view.visibility == View.VISIBLE) {
        view.alpha = 1f
        return
    }

    view.alpha = 0f
    setup(view)
    view.visibility = View.VISIBLE
    val animator = view.animate().alpha(1f)
    additionalAnimation(animator)
    animator
        .setDuration(fadeMillis)
        .setInterpolator(AccelerateDecelerateInterpolator())
        .withEndAction { onAnimationEnd() }
        .setListener(CleanupViewAnimationListener(view))
    fadeAnimations[view] = FadeAnimation(animator, FadeAnimation.Type.FADE_IN)
    animator.start()
}

@MainThread
fun View?.fadeOut(
    fadeMillis: Long = DEFAULT_FADE_MILLIS,
    additionalAnimation: (ViewPropertyAnimator) -> Unit = {},
    onAnimationEnd: () -> Unit = {},
) {
    val view = this ?: return
    val running = fadeAnimations[view]
    if (running?.type == FadeAnimation.Type.FADE_OUT) return
    if (running != null) {
        fadeAnimations.remove(view)
        running.viewPropertyAnimator.cancel()
    }
    if (view.visibility != View.VISIBLE) return

    val animator = view.animate().alpha(0f)
    additionalAnimation(animator)
    animator
        .setDuration(fadeMillis)
        .setInterpolator(AccelerateDecelerateInterpolator())
        .withEndAction {
            view.visibility = View.GONE
            onAnimationEnd()
        }
        .setListener(CleanupViewAnimationListener(view))
    fadeAnimations[view] = FadeAnimation(animator, FadeAnimation.Type.FADE_OUT)
    animator.start()
}

@MainThread
fun View?.fadeBy(isVisible: Boolean, fadeMillis: Long = DEFAULT_FADE_MILLIS) {
    if (isVisible) fadeIn(fadeMillis) else fadeOut(fadeMillis)
}

fun View.cancelFadeAnimations() {
    fadeAnimations.remove(this)?.viewPropertyAnimator?.cancel()
}

// endregion

// region enabled state

fun View.enable() {
    isEnabled = true
}

fun View.disable() {
    isEnabled = false
}

/** Dims a view to communicate that it is unavailable without changing its enabled state. */
fun View.setEnabledAlpha(enabled: Boolean, disabledAlpha: Float = 0.3f) {
    alpha = if (enabled) 1f else disabledAlpha
}

fun View.setEnabledAndAlpha(enabled: Boolean, disabledAlpha: Float = 0.3f) {
    isEnabled = enabled
    setEnabledAlpha(enabled, disabledAlpha)
}

// endregion

// region layout observation

fun View.addOnHeightChangedListener(onHeightChanged: (newHeight: Int, oldHeight: Int) -> Unit) {
    addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
        val newHeight = bottom - top
        val oldHeight = oldBottom - oldTop
        if (newHeight != oldHeight) onHeightChanged(newHeight, oldHeight)
    }
}

fun View.addOnWidthChangedListener(onWidthChanged: (newWidth: Int, oldWidth: Int) -> Unit) {
    addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
        val newWidth = right - left
        val oldWidth = oldRight - oldLeft
        if (newWidth != oldWidth) onWidthChanged(newWidth, oldWidth)
    }
}

fun View.addOnSizeChangedListener(
    onSizeChanged: (newWidth: Int, oldWidth: Int, newHeight: Int, oldHeight: Int) -> Unit,
) {
    addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        val newWidth = right - left
        val newHeight = bottom - top
        val oldWidth = oldRight - oldLeft
        val oldHeight = oldBottom - oldTop
        if (newWidth != oldWidth || newHeight != oldHeight) {
            onSizeChanged(newWidth, oldWidth, newHeight, oldHeight)
        }
    }
}

// endregion

// region interaction

/**
 * Handles a long press and swallows the click that Android would otherwise deliver afterwards.
 *
 * Inside a `RecyclerView` the pending touch is also cancelled, so lifting a finger after a long
 * press on a row does not additionally activate the row.
 */
fun View.setOnLongClickListenerConsumeClick(onLongClick: (View) -> Unit) {
    setOnLongClickListener { view ->
        onLongClick(view)
        var parent: ViewParent? = view.parent
        while (parent != null && parent !is RecyclerView) parent = parent.parent
        (parent as? RecyclerView)?.ignoreCurrentTouch()
        true
    }
}

/** Flashes the touch ripple twice to draw attention to an interactive element. */
fun View.hintWithRipple(delayMillis: Long = HINT_DELAY_MILLIS) {
    isPressed = true
    isPressed = false
    postDelayed({
        isPressed = true
        isPressed = false
    }, delayMillis)
}

fun CompoundButton.setProgrammaticChecked(
    isChecked: Boolean,
    listener: CompoundButton.OnCheckedChangeListener,
) {
    // Detach first so restoring state does not look like a user toggle.
    setOnCheckedChangeListener(null)
    this.isChecked = isChecked
    setOnCheckedChangeListener(listener)
}

// endregion

// region generic view helpers

inline fun <reified T : ViewParent> View.findParent(): T? = parent as? T

/**
 * Assigns a background without letting the drawable's own padding replace the view's padding,
 * which `setBackgroundResource` would do.
 */
fun View.setBackgroundAndKeepPadding(backgroundDrawable: Drawable?) {
    background = backgroundDrawable
}

fun View.useAttrs(
    attrs: AttributeSet?,
    @StyleableRes styleable: IntArray,
    block: (TypedArray) -> Unit,
) {
    val typedArray = context.obtainStyledAttributes(attrs, styleable)
    block(typedArray)
    typedArray.recycle()
}

/**
 * Forwards window insets to every direct child and consumes them.
 *
 * A `ViewGroup` normally stops dispatching once one child consumes the insets; panels that place
 * several siblings edge to edge need all of them to inset.
 */
fun ViewGroup.setForwardingWindowInsetsListener() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        if (view is ViewGroup) {
            for (child in view.children) ViewCompat.dispatchApplyWindowInsets(child, insets)
        }
        insets.consumeSystemWindowInsets()
    }
}

fun NestedScrollView.getContentView(): View = getChildAt(0)

// endregion

// region TextView helpers

/** Sets the text and hides the view entirely when there is nothing to show. */
fun TextView.setTextAndVisibilityBy(text: CharSequence?) {
    this.text = text
    visibility = if (!text.isNullOrBlank()) View.VISIBLE else View.GONE
}

fun TextView.setPluralText(@PluralsRes pluralResId: Int, quantity: Int, vararg formatArgs: Any) {
    text = resources.getQuantityString(pluralResId, quantity, quantity, *formatArgs)
}

fun TextView.setCompoundDrawableWithIntrinsicBounds(
    @DrawableRes start: Int = 0,
    @DrawableRes top: Int = 0,
    @DrawableRes end: Int = 0,
    @DrawableRes bottom: Int = 0,
) {
    setCompoundDrawablesRelativeWithIntrinsicBounds(start, top, end, bottom)
}

// endregion

// region TextInputLayout helpers

fun TextInputLayout.getTextOrEmpty(): String = editText?.text?.toString() ?: ""

fun TextInputLayout.clear(): Unit? = editText?.text?.clear()

fun TextInputLayout.setText(@StringRes resId: Int): Unit? = editText?.setText(resId)

fun TextInputLayout.setText(text: CharSequence?): Unit? = editText?.setText(text)

/** Avoids resetting the cursor position when the value has not actually changed. */
fun TextInputLayout.setTextIfDifferent(text: CharSequence?) {
    if (editText?.text?.toString() != text) editText?.setText(text)
}

fun TextInputLayout.setHint(@StringRes resId: Int) {
    hint = context.getString(resId)
}

/** Uses the edit text's own hint instead of the floating label, keeping the field one line tall. */
fun TextInputLayout.setSingleLineHint(charSequence: CharSequence) {
    isHintEnabled = false
    editText?.hint = charSequence
}

fun TextInputLayout.setSingleLineHint(@StringRes resId: Int) {
    setSingleLineHint(context.getString(resId))
}

fun TextInputLayout.setSelection(index: Int): Unit? = editText?.setSelection(index)

fun TextInputLayout.setSelectionEnd(): Unit? = setSelection(editText?.length() ?: 0)

fun TextInputLayout.moveCursorToEnd() {
    editText?.let { it.setSelection(it.text.toString().length) }
}

fun TextInputLayout.addBindedTextWatcher(
    fragment: Fragment,
    onAfterTextChanged: (Editable) -> Unit,
): Unit? = editText?.addBindedTextWatcher(fragment, onAfterTextChanged)

fun TextInputLayout.setOnEditTextClickListener(l: (View) -> Unit): Unit? =
    editText?.setOnClickListener { view -> l(view) }

fun TextInputLayout.setOnEditTextFocusChangeListener(l: View.OnFocusChangeListener) {
    editText?.onFocusChangeListener = l
}

fun TextInputLayout.setOnEditorActionListener(
    l: (TextView, Int, KeyEvent?) -> Boolean,
): Unit? = editText?.setOnEditorActionListener { textView, actionId, event ->
    l(textView, actionId, event)
}

/**
 * Invokes [onImeActionDone] when the soft keyboard's Done action fires on a non-empty field.
 *
 * @param isEventConsumed true keeps the keyboard open, which forms use when submission may fail
 *   and the user is likely to keep editing.
 */
fun TextInputLayout.setOnImeActionDone(
    isEventConsumed: Boolean = false,
    onImeActionDone: (TextView) -> Unit,
): Unit? = setOnEditorActionListener { textView, actionId, _ ->
    if (actionId == EditorInfo.IME_ACTION_DONE && getTextOrEmpty().isNotEmpty()) {
        onImeActionDone(textView)
    }
    isEventConsumed
}

/**
 * Lets a focused multi-line field scroll itself instead of handing the gesture to the surrounding
 * scroll container.
 */
fun TextInputLayout.interceptScrollWhenInsideScrollable() {
    editText?.setOnTouchListener { view, _: MotionEvent ->
        if (view.isFocused) view.parent.requestDisallowInterceptTouchEvent(true)
        false
    }
}

// endregion
