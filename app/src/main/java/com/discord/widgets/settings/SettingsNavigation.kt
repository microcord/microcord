package com.discord.widgets.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.fragment.app.Fragment
import com.discord.R
import com.discord.app.AppActivity
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.app.AppScreenRouter
import com.discord.app.BackPressHandlerRegistry

/**
 * Opens a full-screen settings destination.
 *
 * The reference calls `AppScreen.d(context, screenClass, extras)`, which starts `AppActivity` with
 * the fragment class on [AppScreenRouter.EXTRA_SCREEN]. This project's `AppActivity` does not read
 * that extra — it only hosts the fragment for the current `MainRoute` — so, exactly as
 * `widgets/media/WidgetMedia` already does, the transaction is made against the hosting activity's
 * `app_root` container and the intent path is kept as the fallback.
 *
 * Extras are passed twice on purpose: as intent extras (what `AppFragment.mostRecentIntent` reads
 * once the activity honours `EXTRA_SCREEN`) and as fragment arguments (what the destinations read
 * today). Screens built by this slice read arguments first and fall back to the intent, so nothing
 * here changes when the activity starts consuming `EXTRA_SCREEN`.
 */
internal object SettingsNavigation {

    fun bindBack(fragment: AppFragment) {
        fragment.setOnBackPressed(
            handler = {
                val fragmentManager = fragment.parentFragmentManager
                if (fragmentManager.backStackEntryCount == 0) return@setOnBackPressed false
                fragmentManager.popBackStack()
                true
            },
            priority = BackPressHandlerRegistry.DEFAULT_PRIORITY - 1,
        )
    }

    fun launch(context: Context, screen: Class<out Fragment>, extras: Intent = Intent()) {
        val activity = context.findAppActivity()
        if (activity == null || activity.isFinishing) {
            AppScreenRouter.launch(context, screen, extras)
            return
        }
        val fragment = activity.supportFragmentManager.fragmentFactory
            .instantiate(context.classLoader, screen.name)
            .apply { arguments = extras.extras }
        show(activity, fragment)
    }

    /**
     * Variant for a destination that is created through its own `newInstance`, so its argument keys
     * stay private to it. There is no intent fallback: the arguments live only on [fragment].
     */
    fun launch(context: Context, fragment: Fragment) {
        val activity = context.findAppActivity() ?: return
        if (activity.isFinishing) return
        show(activity, fragment)
    }

    private fun show(activity: AppActivity, fragment: Fragment) {
        val name = fragment.javaClass.name
        AppLog.recordNavigation(activity.javaClass.simpleName, fragment.javaClass.simpleName)
        activity.supportFragmentManager.beginTransaction()
            .replace(R.id.app_root, fragment, name)
            .addToBackStack(null)
            .commit()
    }

    private fun Context.findAppActivity(): AppActivity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is AppActivity) return current
            current = current.baseContext
        }
        return null
    }
}
