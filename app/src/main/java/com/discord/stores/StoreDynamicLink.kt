package com.discord.stores

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.discord.utilities.intent.DeepLinkParser
import com.discord.utilities.intent.DeepLinkRoute
import com.discord.utilities.intent.LinkParseResult
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationBuffered
import rx.Observable

/**
 * Consumes a deferred deep link — the URL a user tapped *before* installing, delivered on first
 * launch — and turns it into an authenticated route.
 *
 * Three things can ride on such a link, and each is applied before routing:
 *
 * - `fingerprint` — the anonymous install identifier, handed to [StoreAuthentication] so the
 *   registration that follows is attributed to the invite that caused it.
 * - `auth_token` — a pre-authorised session; if present the user is signed in immediately.
 * - `attemptId` — analytics correlation only; carried on [DynamicLinkData] and otherwise unused,
 *   exactly as in the reference.
 *
 * Routing then prefers an invite code, then a guild-template code, then the raw URI.
 *
 * **The link provider needs Manticord-owned configuration.** The reference resolves these through
 * Firebase Dynamic Links, whose configuration is a Google/Firebase credential recovered from the
 * reference APK and must not be reused (and the service itself is discontinued). [linkResolver]
 * therefore defaults to [NoDeferredLink], which resolves to [Uri.EMPTY] — the store is wired,
 * correct and inert until a provider is supplied. A direct (non-deferred) launch intent is
 * unaffected: it is already handled by `AppIntentParser`/`DeepLinkParser`.
 */
class StoreDynamicLink(
    private val dispatcher: Dispatcher,
    private val authentication: StoreAuthentication,
    /**
     * Resolves the deferred link an install was attributed to.
     *
     * Must emit exactly once. [Uri.EMPTY] means "no deferred link", which the store treats as
     * "nothing to do".
     */
    private val linkResolver: (Intent) -> Observable<Uri> = NoDeferredLink,
    /** Where a resolved route is handed off. Wired to `IntentUtils.consumeRoutingIntent`. */
    private val routeConsumer: (Intent, Context) -> Unit = { _, _ -> },
) {

    /**
     * The pieces of a deferred link the client acts on.
     *
     * [uri] is only populated for an http(s) link — a custom-scheme link is routed through the
     * invite/template branches or not at all.
     */
    data class DynamicLinkData(
        val uri: Uri?,
        val fingerprint: String?,
        val attemptId: String?,
        val inviteCode: String?,
        val guildTemplateCode: String?,
        val authToken: String?,
    ) {
        override fun toString(): String = "DynamicLinkData(uri=$uri, fingerprint=<redacted>, " +
            "attemptId=$attemptId, inviteCode=$inviteCode, guildTemplateCode=$guildTemplateCode, " +
            "authToken=<redacted>)"
    }

    /** Default [linkResolver]: no deferred-link provider is configured. */
    object NoDeferredLink : (Intent) -> Observable<Uri> {
        override fun invoke(intent: Intent): Observable<Uri> = Observable.just(Uri.EMPTY)
    }

    fun storeLinkIfExists(intent: Intent, context: Context) {
        dynamicLinkObservable(intent)
            .map(::parseDynamicLink)
            .computationBuffered()
            .distinctUntilChanged()
            .doOnNext(::applyCredentials)
            .appSubscribe(StoreDynamicLink::class.java) { data ->
                handleDataReceived(data, context)
            }
    }

    /**
     * Races the provider against a [DYNAMIC_LINK_TIMEOUT_MS] "no link" default, and falls back to
     * the same default on error.
     *
     * The timeout is not an optimisation: a provider that never answers would otherwise hold the
     * launch intent forever and the app would open on a blank route.
     */
    private fun dynamicLinkObservable(intent: Intent): Observable<Uri> = Observable.merge(
        Observable.just(Uri.EMPTY).delay(DYNAMIC_LINK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS),
        linkResolver(intent).onErrorReturn { Uri.EMPTY },
    ).onErrorReturn { Uri.EMPTY }

    internal fun parseDynamicLink(uri: Uri): DynamicLinkData? {
        if (uri == Uri.EMPTY) return null
        val parsed = DeepLinkParser.parse(uri.toString())
        val route = (parsed as? LinkParseResult.Internal)?.route
        return DynamicLinkData(
            // Only an http(s) first-party link is worth re-dispatching as a raw VIEW intent;
            // everything else is either already covered by the code branches or untrusted.
            uri = uri.takeIf { parsed is LinkParseResult.Internal && isHttpScheme(it) },
            fingerprint = uri.queryParameterOrNull(QUERY_FINGERPRINT),
            attemptId = uri.queryParameterOrNull(QUERY_ATTEMPT_ID),
            inviteCode = (route as? DeepLinkRoute.Invite)?.code,
            guildTemplateCode = (route as? DeepLinkRoute.GuildTemplate)?.code,
            authToken = uri.queryParameterOrNull(QUERY_AUTH_TOKEN),
        )
    }

    /**
     * The fingerprint is always applied — including as `null`, which clears a stale one — and the
     * auth token only when present.
     */
    private fun applyCredentials(data: DynamicLinkData?) = dispatcher.schedule {
        authentication.setFingerprint(data?.fingerprint, false)
        data?.authToken?.let(authentication::setAuthed)
    }

    private fun handleDataReceived(data: DynamicLinkData?, context: Context) {
        if (data == null) return
        data.inviteCode?.takeIf(String::isNotBlank)?.let { code ->
            routeConsumer(inviteIntent(code), context)
            return
        }
        data.guildTemplateCode?.takeIf(String::isNotBlank)?.let { code ->
            routeConsumer(guildTemplateIntent(code), context)
            return
        }
        data.uri?.let { uri -> routeConsumer(Intent().setData(uri), context) }
    }

    /**
     * Canonical short-link forms, so the intent round-trips back through
     * [DeepLinkParser] into [DeepLinkRoute.Invite] / [DeepLinkRoute.GuildTemplate].
     *
     * `?source=Deeplink` replaces the reference's `RouteBuilders.selectInvite(code, "Deeplink")`
     * analytics argument — the target's parser reads the attribution from the query string.
     */
    private fun inviteIntent(code: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/${code.trim()}?source=$DEEPLINK_SOURCE"))

    private fun guildTemplateIntent(code: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.new/${code.trim()}"))

    private fun isHttpScheme(uri: Uri): Boolean =
        uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)

    /** `Uri.getQueryParameter` throws on an opaque URI; the reference swallows that too. */
    private fun Uri.queryParameterOrNull(key: String): String? =
        runCatching { getQueryParameter(key) }.getOrNull()

    companion object {
        const val DYNAMIC_LINK_TIMEOUT_MS = 1_000L

        private const val QUERY_FINGERPRINT = "fingerprint"
        private const val QUERY_ATTEMPT_ID = "attemptId"
        private const val QUERY_AUTH_TOKEN = "auth_token"

        private const val DEEPLINK_SOURCE = "Deeplink"
    }
}
