package com.discord.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ContextThemeWrapper
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.discord.R
import com.discord.utilities.view.ToastManager
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.facebook.drawee.span.DraweeSpanStringBuilder

/**
 * The app's toasts.
 *
 * Two things make this more than a wrapper over [Toast.makeText]:
 *
 * * **Themed text.** The toast is built from a themed [TextView] so it matches the app rather than
 *   the platform, and a [DraweeSpanStringBuilder] — used whenever a toast contains an emoji — is
 *   rendered by a [SimpleDraweeSpanTextView] so those spans actually load.
 * * **Optional deduplication.** Passing a [ToastManager] routes the toast through it, cancelling
 *   whatever it showed last. Without one, Android queues toasts and a screen that toasts on every
 *   tap leaves a backlog visible long after the user has moved on.
 *
 * A `null` context is tolerated throughout: these are called from views and fragments that may
 * already be detached, and a dropped toast is always better than a crash.
 *
 * NOTE: the reference declares these as top-level functions in a file named `AppToast.kt`; they are
 * grouped into an object here so Kotlin call sites read `AppToast.show(...)`, which is how the rest
 * of this project refers to them.
 */
object AppToast {

    /** Shows [text]; [duration] is a [Toast] length constant. */
    @JvmOverloads
    @JvmStatic
    fun show(
        context: Context?,
        text: CharSequence?,
        duration: Int = Toast.LENGTH_SHORT,
        toastManager: ToastManager? = null,
    ) {
        if (context == null) return

        val themedContext = ContextThemeWrapper(context, R.style.UiKit_Toast)
        val toastView: TextView = if (text is DraweeSpanStringBuilder) {
            SimpleDraweeSpanTextView(themedContext).also { it.setDraweeSpanStringBuilder(text) }
        } else {
            TextView(themedContext).also { it.text = text }
        }

        @Suppress("DEPRECATION")
        val toast = Toast(context)
        @Suppress("DEPRECATION")
        toast.view = toastView
        toast.duration = duration

        if (toastManager != null) toastManager.show(toast) else toast.show()
    }

    /** Shows the string [stringResId] resolves to against [context]. */
    @JvmOverloads
    @JvmStatic
    fun show(
        context: Context?,
        @StringRes stringResId: Int,
        duration: Int = Toast.LENGTH_SHORT,
        toastManager: ToastManager? = null,
    ) = show(context, context?.getString(stringResId), duration, toastManager)

    /** Shows [text] against [fragment]'s context, if it still has one. */
    @JvmOverloads
    @JvmStatic
    fun show(fragment: Fragment?, text: CharSequence?, duration: Int = Toast.LENGTH_SHORT) =
        show(fragment?.context, text, duration)

    /** Shows the string [stringResId] resolves to against [fragment]'s context. */
    @JvmOverloads
    @JvmStatic
    fun show(fragment: Fragment?, @StringRes stringResId: Int, duration: Int = Toast.LENGTH_SHORT) =
        show(fragment?.context, fragment?.context?.getString(stringResId), duration)

    /**
     * Copies [text] to the clipboard and confirms it with a toast reading [toastText].
     *
     * A missing clipboard service is survivable — the toast still appears, which matches the
     * reference. It only happens on devices where the service has been stripped.
     */
    @JvmStatic
    fun copyToClipboard(context: Context, text: CharSequence, toastText: CharSequence) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.setPrimaryClip(ClipData.newPlainText("", text))
        show(context, toastText)
    }

    /** [copyToClipboard] with a confirmation read from resources; defaults to "Copied to clipboard.". */
    @JvmOverloads
    @JvmStatic
    fun copyToClipboard(
        context: Context,
        text: CharSequence,
        @StringRes toastResId: Int = R.string.copied_text,
    ) = copyToClipboard(context, text, context.getString(toastResId))
}
