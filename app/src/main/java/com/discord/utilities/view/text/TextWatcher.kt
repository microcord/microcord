@file:JvmName("TextWatcherKt")

package com.discord.utilities.view.text

import android.text.Editable
import android.widget.TextView
import androidx.fragment.app.Fragment

/** A [android.text.TextWatcher] whose three callbacks are all optional lambdas. */
open class TextWatcher(
    private val onBeforeTextChanged: ((CharSequence, Int, Int, Int) -> Unit)? = null,
    private val onTextChanged: ((CharSequence, Int, Int, Int) -> Unit)? = null,
    private val onAfterTextChanged: ((Editable) -> Unit)? = null,
) : android.text.TextWatcher {

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
        onBeforeTextChanged?.invoke(s, start, count, after)
    }

    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        onTextChanged?.invoke(s, start, before, count)
    }

    override fun afterTextChanged(s: Editable) {
        onAfterTextChanged?.invoke(s)
    }

    companion object {
        /** Removes every watcher registered by [fragment]; call from `onDestroyView`. */
        @JvmStatic
        fun reset(fragment: Fragment) {
            bindedTextWatchers[fragment]?.forEach { (textView, watchers) ->
                for (watcher in watchers) textView.removeTextChangedListener(watcher)
            }
            bindedTextWatchers.remove(fragment)
        }
    }
}

/**
 * Watchers registered per fragment so they can all be detached at once.
 *
 * A fragment's view can be recreated while the fragment instance survives; without this registry
 * each recreation would leave the previous watcher attached to a dead view.
 */
internal val bindedTextWatchers =
    HashMap<Fragment, HashMap<TextView, ArrayList<android.text.TextWatcher>>>()

fun TextView.addBindedTextWatcher(fragment: Fragment, textWatcher: android.text.TextWatcher) {
    val fragmentWatchers = bindedTextWatchers.getOrPut(fragment) { HashMap() }
    val viewWatchers = fragmentWatchers.getOrPut(this) { ArrayList() }
    viewWatchers.add(textWatcher)
    addTextChangedListener(textWatcher)
}

fun TextView.addBindedTextWatcher(fragment: Fragment, onAfterTextChanged: (Editable) -> Unit) {
    addBindedTextWatcher(fragment, TextWatcher(onAfterTextChanged = onAfterTextChanged))
}
