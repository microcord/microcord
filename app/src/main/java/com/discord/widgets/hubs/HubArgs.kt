package com.discord.widgets.hubs

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import androidx.fragment.app.Fragment
import com.discord.app.AppFragment

/**
 * How a hub screen receives its arguments.
 *
 * Every hub screen is entered either as a fragment (`FragmentTransaction.replace` with an arguments
 * bundle) or as a new `AppActivity` (an intent extra). The reference stores both under the single
 * key `intent_args_key` — `b.c.a.a0.d.e2/g2` in the obfuscated build — and reads them back with an
 * `args` delegate that falls back from the arguments bundle to the launching intent. Reproduced here
 * because a screen may be re-entered either way.
 *
 * This helper belongs in `com.discord.app` next to [AppFragment]; it lives in this package only
 * because that file is owned by another wave. See the handoff report.
 */
internal const val INTENT_ARGS_KEY: String = "intent_args_key"

/** `Intent().putExtra(INTENT_ARGS, this)` — the reference's `Parcelable.toIntent()`. */
internal fun Parcelable.toArgsIntent(): Intent = Intent().putExtra(INTENT_ARGS_KEY, this)

/** `Bundle().putParcelable(INTENT_ARGS, this)` — the reference's `Parcelable.toBundle()`. */
internal fun Parcelable.toArgsBundle(): Bundle = Bundle().apply { putParcelable(INTENT_ARGS_KEY, this@toArgsBundle) }

/** Reads a result parcelable back out of an activity result intent. */
@Suppress("DEPRECATION")
internal inline fun <reified T : Parcelable> Intent.getArgsFromIntent(): T? =
    getParcelableExtra(INTENT_ARGS_KEY) as? T

/**
 * The arguments this screen was entered with.
 *
 * Looks in the fragment arguments first, then in the intent that most recently entered the host
 * activity. Throws when neither carries them, matching the reference: a hub screen has no usable
 * behaviour without its arguments, so failing loudly beats rendering an empty surface.
 */
@Suppress("DEPRECATION")
internal inline fun <reified T : Parcelable> AppFragment.hubArgs(): Lazy<T> = lazy {
    val fromArguments = arguments?.get(INTENT_ARGS_KEY) as? T
    val args = fromArguments ?: mostRecentIntent.getParcelableExtra<T>(INTENT_ARGS_KEY)
    checkNotNull(args) { "Missing args for class type ${T::class.java.name} + key $INTENT_ARGS_KEY!" }
}

/** [hubArgs] for dialogs and bottom sheets, which only ever receive an arguments bundle. */
@Suppress("DEPRECATION")
internal inline fun <reified T : Parcelable> Fragment.hubDialogArgs(): Lazy<T> = lazy {
    val args = arguments?.get(INTENT_ARGS_KEY) as? T
    checkNotNull(args) { "Missing args for class type ${T::class.java.name} + key $INTENT_ARGS_KEY!" }
}
