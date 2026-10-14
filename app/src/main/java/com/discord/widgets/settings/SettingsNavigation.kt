package com.discord.widgets.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.discord.app.AppActivity
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.BackPressHandlerRegistry

/**
 * Opens a full-screen settings destination.
 *
 * The reference starts a new [com.discord.app.AppActivity] carrying the fragment class in
 * [AppScreenRouter.EXTRA_SCREEN]. The existing Home activity stays mounted underneath, preserving
 * its selected channel and panel positions when Back returns to it.
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

    /** Pushes [page] into the container [from] lives in, so Back returns to [from]. */
    fun push(from: Fragment, page: Fragment) = from.parentFragmentManager.commit {
        replace(from.id, page)
        addToBackStack(page::class.java.name)
    }

    fun launch(context: Context, screen: Class<out Fragment>, extras: Intent = Intent()) =
        AppScreenRouter.launch(context, screen, extras)

    /**
     * Variant for a destination created through its own `newInstance`, preserving its arguments on
     * the screen intent that [AppActivity] passes to the reconstructed fragment.
     */
    fun launch(context: Context, fragment: Fragment) {
        val extras = Intent().putExtras(fragment.arguments ?: Bundle())
        AppScreenRouter.launch(context, fragment.javaClass, extras)
    }
}
