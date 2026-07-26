package com.discord.utilities.uri

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsService

/**
 * Picks which installed browser should host a Chrome Custom Tab.
 *
 * A package qualifies only if it both handles `http` view intents and exposes the Custom Tabs
 * service. Chrome and its channels are preferred in release order because they are the reference
 * implementation; any other qualifying browser accepted by the caller's filter is used otherwise.
 */
internal object CustomTabsPackages {

    private val PREFERRED_PACKAGES = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.google.android.apps.chrome",
    )

    /**
     * @param filter additional caller constraint, used to exclude this app itself.
     * @return the package to launch the Custom Tab in, or null when no browser supports them.
     */
    fun getCustomTabsDefaultPackage(context: Context, filter: (String) -> Boolean): String? {
        val packageManager = context.packageManager
        val viewIntent = Intent()
            .setAction(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setData(Uri.fromParts("http", "", null))

        val customTabsPackages = packageManager.queryIntentActivities(viewIntent, 0)
            .mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                val serviceIntent = Intent()
                    .setAction(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION)
                    .setPackage(packageName)
                if (packageManager.resolveService(serviceIntent, 0) != null) packageName else null
            }

        return PREFERRED_PACKAGES.firstOrNull(customTabsPackages::contains)
            ?: customTabsPackages.firstOrNull(filter)
    }
}
