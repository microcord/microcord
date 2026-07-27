package com.discord.utilities.uri

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import com.discord.R
import com.discord.databinding.LayoutUnhandledUriBinding
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat

/**
 * The single entry point for opening a URI that came from outside the app.
 *
 * Every link the user can tap — chat text, embeds, profile connections, deep links that the app
 * itself cannot handle — funnels through here so one place decides:
 *  - whether the destination is safe to open at all ([isSafeScheme], [isTrustedDomain]);
 *  - whether it opens in a Chrome Custom Tab (themed, stays in-task) or is handed to another app;
 *  - what happens when nothing on the device can handle it.
 */
object UriHandler {

    private const val TAG = "UriHandler"
    private const val APP_PACKAGE = "com.discord"
    private const val URL_PLAY_STORE_DIRECT = "market://details"
    private const val URL_PLAY_STORE_ALT = "https://play.google.com/store/apps/details"
    private const val PLAY_STORE_SOURCE_DEFAULT = "discord"

    /**
     * Schemes that must never be launched from remote content.
     *
     * `javascript`/`vbscript`/`data` can execute in a browsing context; `file`/`content` can leak
     * or exfiltrate app-private data; `intent`/`android-app` let a crafted link address an
     * arbitrary component. Nothing user-facing in the product needs them, so they are dropped
     * before any Intent is built.
     */
    private val UNSAFE_SCHEMES = setOf(
        "javascript",
        "vbscript",
        "data",
        "file",
        "content",
        "intent",
        "android-app",
    )

    /**
     * Hosts that are always considered first-party.
     *
     * Kept here rather than in a store so link safety does not depend on store initialisation.
     */
    private val IMPLICITLY_TRUSTED_HOSTS = setOf(
        "discord.com",
        "discordapp.com",
        "discord.gg",
        "discord.gift",
        "discord.new",
        "discord.media",
        "cdn.discordapp.com",
        "media.discordapp.net",
        "spotify.com",
        "open.spotify.com",
    )

    /**
     * Additional trust decisions contributed by the store graph (domains the user chose to trust,
     * and the relationship-based allowance for masked links from friends). Installed at startup;
     * until then only [IMPLICITLY_TRUSTED_HOSTS] are trusted.
     */
    @JvmStatic
    var trustedDomainResolver: ((url: String, mask: String?) -> Boolean)? = null

    /** Remembers a destination after the user confirms "Trust this domain". */
    @JvmStatic
    var trustDomainHandler: ((url: String) -> Unit)? = null

    /**
     * Presents the "this link may not go where it says" confirmation. Installed by the UI layer;
     * when absent an untrusted link is simply not opened.
     */
    @JvmStatic
    var untrustedLinkHandler: ((context: Context, url: String) -> Unit)? = null

    /**
     * Opens [url].
     *
     * @param forceExternal skip Custom Tabs and this app's own handlers, so the link genuinely
     *   leaves the app (used for links that would otherwise loop back into Discord).
     * @param preventCustomTab honour the destination's own app instead of a Custom Tab.
     * @param onFailure invoked when nothing could handle the URI; returning without showing the
     *   unhandled-link dialog is the caller's way of taking over the failure.
     */
    @JvmStatic
    @JvmOverloads
    fun handle(
        context: Context,
        url: String?,
        forceExternal: Boolean = false,
        preventCustomTab: Boolean = false,
        onFailure: (() -> Unit)? = null,
    ) {
        if (url == null) return
        if (!isSafeScheme(url)) {
            Log.w(TAG, "Refusing to open a URI with an unsupported scheme.")
            onFailure?.invoke()
            return
        }
        val uri = try {
            Uri.parse(url)
        } catch (e: Exception) {
            null
        } ?: return

        if (forceExternal || !handleUriSpecialCase(context, uri)) {
            openUrl(context, uri, url, forceExternal, preventCustomTab, onFailure)
        }
    }

    /**
     * Opens [url] only when its destination is trusted; otherwise defers to
     * [untrustedLinkHandler] so the user can confirm.
     *
     * @param mask the text the link was displayed as. A link whose visible text differs from its
     *   destination is the case this guard exists for.
     */
    @JvmStatic
    @JvmOverloads
    fun handleOrUntrusted(context: Context, url: String, mask: String? = null) {
        if (isTrustedDomain(url, mask)) {
            handle(context, url)
        } else {
            untrustedLinkHandler?.invoke(context, url)
        }
    }

    /**
     * @return true when [url] may be opened without asking the user to confirm the destination.
     */
    @JvmStatic
    @JvmOverloads
    fun isTrustedDomain(url: String, mask: String? = null): Boolean {
        val host = getDomainName(url)
        if (host != null && isImplicitlyTrustedDomain(host)) return true
        return trustedDomainResolver?.invoke(url, mask) ?: false
    }

    /** @return true when [host] is a Discord-owned or otherwise first-party domain. */
    @JvmStatic
    fun isImplicitlyTrustedDomain(host: String): Boolean =
        IMPLICITLY_TRUSTED_HOSTS.contains(host) ||
            host.endsWith(".discord.com") ||
            host.endsWith(".discordapp.com")

    /**
     * @return false for URLs whose scheme must not be launched from untrusted content, and for
     *   URLs with no scheme at all (a bare `evil.com` would otherwise be resolved by the platform).
     */
    @JvmStatic
    fun isSafeScheme(url: String): Boolean {
        val scheme = parseScheme(url) ?: return false
        return scheme !in UNSAFE_SCHEMES
    }

    /**
     * Extracts the scheme without `android.net.Uri`, so the safety decision is a pure function
     * that does not depend on the platform's lenient parsing.
     */
    private fun parseScheme(url: String): String? {
        val separator = url.indexOf(':')
        if (separator <= 0) return null
        val scheme = url.substring(0, separator)
        if (!scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
        if (!scheme.first().isLetter()) return null
        return scheme.lowercase()
    }

    /**
     * Sends the user to this app's Play Store listing, preferring the Play app's `market://`
     * handler and falling back to the web listing when Play is not installed.
     */
    @JvmStatic
    @JvmOverloads
    fun directToPlayStore(
        context: Context,
        packageName: String = APP_PACKAGE,
        source: String = PLAY_STORE_SOURCE_DEFAULT,
    ) {
        val createFullUriString = { base: String ->
            Uri.parse(base)
                .buildUpon()
                .appendQueryParameter("id", packageName)
                .appendQueryParameter("referrer", "utm_source=$source")
                .build()
                .toString()
        }
        handle(context, createFullUriString(URL_PLAY_STORE_DIRECT)) {
            handle(context, createFullUriString(URL_PLAY_STORE_ALT))
        }
    }

    /**
     * URIs the app claims but that are not ordinary navigation (currently the role-subscription
     * handoff). Returns true when the URI was consumed.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun handleUriSpecialCase(context: Context, uri: Uri): Boolean = false

    private fun openUrl(
        context: Context,
        uri: Uri,
        url: String,
        forceExternal: Boolean,
        preventCustomTab: Boolean,
        onFailure: (() -> Unit)?,
    ) {
        if (preventCustomTab || !isChromeCustomTabsEnabled()) {
            openUrlExternally(context, uri, url, forceExternal, onFailure)
            return
        }

        val fallback = { openUrlExternally(context, uri, url, forceExternal, onFailure) }
        // When forcing an external open, exclude ourselves from the candidate browsers so the
        // link cannot be routed straight back into this app.
        val browserPackage = CustomTabsPackages.getCustomTabsDefaultPackage(context) { candidate ->
            !forceExternal || candidate != context.packageName
        } ?: return fallback()

        val themedColor = ColorCompat.getThemedColor(context, R.attr.colorPrimary)
        val colorScheme = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(themedColor)
            .setSecondaryToolbarColor(themedColor)
            .setNavigationBarColor(themedColor)
            .build()
        val customTabsIntent = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(colorScheme)
            .setShowTitle(false)
            .setStartAnimations(
                context,
                R.anim.activity_slide_horizontal_open_in,
                R.anim.activity_slide_horizontal_open_out,
            )
            .setExitAnimations(
                context,
                R.anim.activity_slide_horizontal_close_in,
                R.anim.activity_slide_horizontal_close_out,
            )
            .build()

        try {
            if (forceExternal) customTabsIntent.intent.setPackage(browserPackage)
            customTabsIntent.intent.data = uri
            customTabsIntent.launchUrl(context, uri)
        } catch (e: ActivityNotFoundException) {
            fallback()
        }
    }

    private fun openUrlExternally(
        context: Context,
        uri: Uri,
        url: String?,
        forceExternal: Boolean,
        onFailure: (() -> Unit)?,
    ) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            if (forceExternal) {
                intent.setPackage(
                    context.packageManager
                        .queryIntentActivities(intent, 0)
                        .asSequence()
                        .mapNotNull { it.activityInfo?.packageName }
                        .firstOrNull { it != context.packageName },
                )
            }
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            if (onFailure != null) {
                onFailure()
            } else if (url != null) {
                showUnhandledUrlDialog(context, url)
            }
        }
    }

    /** Last resort: tell the user nothing on the device can open the link, and let them copy it. */
    private fun showUnhandledUrlDialog(context: Context, url: String) {
        val binding = LayoutUnhandledUriBinding.inflate(LayoutInflater.from(context))
        val dialog = AlertDialog.Builder(context).setView(binding.root).create()
        binding.unhandledUriDisplay.text = url
        binding.unhandledUriDisplay.setOnClickListener { view -> copyToClipboard(view, url) }
        binding.unhandledUriOkay.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun copyToClipboard(view: View, url: String) {
        val clipboard = view.context
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(url, url))
    }

    private fun isChromeCustomTabsEnabled(): Boolean = runCatching {
        StoreStream.getUserSettings().getIsChromeCustomTabsEnabled()
    }.getOrDefault(true)

    /**
     * Extracts the host, lower-cased, without `android.net.Uri` so trust decisions stay a pure
     * function. Userinfo (`https://discord.com@evil.com/`) is stripped exactly as a browser would,
     * because that is the classic way to make a hostile host look first-party.
     */
    internal fun getDomainName(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return null
        val afterScheme = url.substring(schemeEnd + 3)
        val authorityEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd < 0) afterScheme else afterScheme.substring(0, authorityEnd)
        val hostPort = authority.substringAfterLast('@')
        val host = hostPort.substringBefore(':')
        return host.ifEmpty { null }?.lowercase()
    }
}
