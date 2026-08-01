package com.discord.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

/**
 * Screen level navigation.
 *
 * A "screen" is a fragment class carried on a dedicated [AppActivity] launch intent. Everything
 * that starts a screen goes through here so navigation breadcrumbs stay consistent and the intent
 * extras keep one shape.
 *
 * Reconstructed from the reference's obfuscated `AppScreen.kt` helper.
 */
object AppScreenRouter {

    /** Fragment class of the screen the activity should host. */
    const val EXTRA_SCREEN = "com.discord.intent.extra.EXTRA_SCREEN"

    /** Requests that the home surface opens its side panel once the screen is shown. */
    const val EXTRA_OPEN_PANEL = "com.discord.intent.extra.EXTRA_OPEN_PANEL"

    /** Legacy marker accepted by [AppActivity] when restoring an older restart intent. */
    const val EXTRA_RECREATE = "com.discord.intent.extra.EXTRA_RECREATE"

    /** Optional activity transition override used by screen routes. */
    const val EXTRA_TRANSITION = "transition"

    /**
     * Builds the intent that opens [screen] inside [AppActivity].
     *
     * Targets the **base** [AppActivity], never [AppActivity.Main]. `Main` is exported, and
     * [EXTRA_SCREEN] names a fragment that the receiver instantiates with the sending intent's own
     * extras as its arguments — so honouring it on an exported component would let any installed
     * app show any fragment with attacker-chosen arguments. `WidgetIncomingShare` makes that
     * concretely exploitable: it reads a `uris` argument and opens each one with our own
     * `contentResolver`, which turns the primitive into private-file exfiltration.
     *
     * The base activity is declared `exported="false"`, so only this process can address it, and
     * [AppActivity.consumeIntent] additionally refuses to read [EXTRA_SCREEN] when it is running as
     * `Main`. Both halves are needed: the manifest stops the intent arriving, the guard stops it
     * mattering if a future change re-exports something.
     */
    fun createScreenIntent(context: Context, screen: Class<out Fragment>, extras: Intent? = null): Intent {
        AppLog.recordNavigation(context.javaClass.simpleName, screen.simpleName)
        return Intent(extras ?: Intent())
            .setClass(context, AppActivity::class.java)
            .putExtra(EXTRA_SCREEN, screen)
    }

    /** Opens [screen] inside [AppActivity]. */
    fun launch(
        context: Context,
        screen: Class<out Fragment>,
        extras: Intent? = null,
        transition: AppTransitionActivity.Transition? = null,
    ) {
        val intent = createScreenIntent(context, screen, extras)
        if (transition != null) intent.putExtra(EXTRA_TRANSITION, transition)
        context.startActivity(intent)
    }

    /** Opens [screen] and routes its activity result back through [launcher]. */
    fun launchForResult(
        context: Context,
        launcher: ActivityResultLauncher<Intent>,
        screen: Class<out Fragment>,
        extras: Intent? = null,
    ) {
        launcher.launch(createScreenIntent(context, screen, extras))
    }

    /** Reads the screen requested by [intent], if any. */
    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    fun screenOf(intent: Intent?): Class<out Fragment>? {
        val screen = runCatching { intent?.getSerializableExtra(EXTRA_SCREEN) as? Class<*> }.getOrNull()
            ?: return null
        return screen.takeIf(Fragment::class.java::isAssignableFrom) as? Class<out Fragment>
    }

    /** Replaces the fragment hosted in [containerId] with a new instance of [screen]. */
    fun replaceFragment(
        fragmentManager: FragmentManager?,
        context: Context,
        screen: Class<out Fragment>,
        containerId: Int = android.R.id.content,
        addToBackStack: Boolean = false,
        arguments: Bundle? = null,
    ) {
        fragmentManager ?: return
        val fragment = fragmentManager.fragmentFactory.instantiate(context.classLoader, screen.name)
        if (arguments != null) fragment.arguments = arguments
        fragmentManager.beginTransaction()
            .replace(containerId, fragment, screen.name)
            .apply { if (addToBackStack) addToBackStack(null) }
            .commit()
    }

    /**
     * Recreates [activity] so a locale, theme or font-scale change is applied while Android retains
     * its saved state, caller result contract and position in the activity stack.
     */
    fun recreate(activity: AppActivity) {
        activity.recreate()
    }
}
