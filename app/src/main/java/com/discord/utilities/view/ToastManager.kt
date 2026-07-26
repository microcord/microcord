package com.discord.utilities.view

import android.annotation.SuppressLint
import android.content.Context
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import java.io.Closeable

/**
 * Owns at most one visible [Toast] at a time.
 *
 * Android queues toasts, so repeatedly toasting (for example while tapping a disabled control)
 * leaves a backlog that keeps showing long after the user moved on. Showing a new toast through
 * this manager cancels the previous one; [close] cancels whatever is on screen when the owning
 * screen goes away.
 */
open class ToastManager : Closeable {

    private var toast: Toast? = null

    @MainThread
    @JvmOverloads
    fun show(context: Context, @StringRes stringRes: Int, duration: Int = Toast.LENGTH_SHORT) {
        show(context, context.getString(stringRes), duration)
    }

    @SuppressLint("ShowToast")
    @MainThread
    @JvmOverloads
    open fun show(context: Context, text: CharSequence?, duration: Int = Toast.LENGTH_SHORT) {
        show(Toast.makeText(context, text, duration))
    }

    @MainThread
    open fun show(newToast: Toast) {
        toast?.cancel()
        newToast.show()
        toast = newToast
    }

    override fun close() {
        toast?.cancel()
        toast = null
    }
}
