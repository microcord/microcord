package com.discord.utilities.intent

import java.net.URI
import java.net.URLDecoder
import java.net.IDN
import java.nio.charset.StandardCharsets
import java.util.Locale

object DeepLinkParser {
    private val shortDiscordHosts = setOf("discord.gg", "discord.gift", "discord.new")
    private val discordHosts = setOf("discord.com", "discordapp.com")
    private val settings = setOf(
        "account", "nitro", "voice", "createServer", "quickSwitcher", "friends", "mentions",
        "settings", "contactSync", "addFriends", "editProfile", "voiceChannel", "xboxLinkBeta",
    )
    private val code = Regex("^[A-Za-z0-9_-]+$")

    fun parse(value: String?): LinkParseResult {
        if (value.isNullOrBlank()) return LinkParseResult.Unsupported(LinkParseResult.Reason.EMPTY)
        val uri = runCatching { URI(value) }.getOrNull()
            ?: return LinkParseResult.Unsupported(LinkParseResult.Reason.MALFORMED)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
            ?: return LinkParseResult.Unsupported(LinkParseResult.Reason.MALFORMED)
        val host = uri.host?.lowercase(Locale.ROOT)

        if (scheme == "https" && !isDiscordHost(host)) {
            return if (ExternalUriPolicy.isAllowed(uri)) LinkParseResult.External(uri.toASCIIString())
            else LinkParseResult.Unsupported(LinkParseResult.Reason.UNSUPPORTED_HOST)
        }
        val appScheme = scheme == "discord" || scheme == "manticord"
        if (scheme != "https" && !appScheme) {
            return LinkParseResult.Unsupported(LinkParseResult.Reason.UNSAFE_SCHEME)
        }
        if (appScheme && (host !in setOf("app", "action") || uri.userInfo != null || uri.port != -1)) {
            return LinkParseResult.Unsupported(LinkParseResult.Reason.UNSUPPORTED_HOST)
        }
        if (scheme == "https" && (uri.userInfo != null || uri.port !in setOf(-1, 443))) {
            return LinkParseResult.Unsupported(LinkParseResult.Reason.UNSUPPORTED_HOST)
        }
        val path = uri.path?.trimEnd('/').orEmpty().ifEmpty { "/" }
        val parts = path.split('/').filter(String::isNotEmpty)
        val query = query(uri.rawQuery)
            ?: return LinkParseResult.Unsupported(LinkParseResult.Reason.MALFORMED)
        val route = when {
            host == "discord.gg" && parts.size == 1 -> invite(parts[0], query)
            host == "discord.gift" && parts.size == 1 -> parts[0].takeIf(code::matches)?.let(DeepLinkRoute::Gift)
            host == "discord.new" && parts.size == 1 -> parts[0].takeIf(code::matches)?.let(DeepLinkRoute::GuildTemplate)
            (appScheme && host == "app" && path == "/") || path == "/app" -> DeepLinkRoute.Home
            parts.take(2) == listOf("discovery", "applications") && parts.size in 2..4 -> when {
                parts.size == 2 || parts[2] == "search" -> DeepLinkRoute.ApplicationDirectory()
                parts[2] == "categories" && parts.size == 4 && positive(parts[3]) != null ->
                    DeepLinkRoute.ApplicationDirectory()
                parts.size == 3 -> positive(parts[2])?.let(DeepLinkRoute::ApplicationDirectory)
                else -> null
            }
            path == "/discovery" -> DeepLinkRoute.Discovery
            parts.firstOrNull() == "invite" && parts.size == 2 -> invite(parts[1], query)
            parts.firstOrNull() == "gifts" && parts.size == 2 -> parts[1].takeIf(code::matches)?.let(DeepLinkRoute::Gift)
            parts.firstOrNull() == "template" && parts.size == 2 -> parts[1].takeIf(code::matches)?.let(DeepLinkRoute::GuildTemplate)
            parts == listOf("channels", "@me", "-1") -> DeepLinkRoute.Friends
            parts.size == 4 && parts.take(3) == listOf("channels", "@me", "user") ->
                positive(parts[3])?.let(DeepLinkRoute::DirectMessage)
            parts.firstOrNull() == "channels" -> channel(parts)
            parts.firstOrNull() == "users" && parts.size == 2 ->
                positive(parts[1])?.let { DeepLinkRoute.User(it, query["friend_token"]) }
            parts.firstOrNull() == "settings" || parts.firstOrNull() == "feature" -> setting(parts)
            parts.firstOrNull() in setOf("oauth2", "connect") && parts.getOrNull(1) == "authorize" && parts.size == 2 ->
                DeepLinkRoute.Authorization(parts[0] == "connect", query)
            parts.firstOrNull() == "oauth2" && parts.getOrNull(1) in setOf("authorized", "callback") && parts.size == 2 ->
                DeepLinkRoute.OAuthCallback(query)
            host == "action" && parts.size == 2 && parts[1] == "authorize" && parts[0] in setOf("oauth2", "connect") ->
                DeepLinkRoute.Authorization(parts[0] == "connect", query)
            host == "action" && parts == listOf("oauth2", "callback") -> DeepLinkRoute.OAuthCallback(query)
            parts.firstOrNull() == "connect" && parts.size <= 2 ->
                parts.getOrNull(1)?.let(::positive)?.let(DeepLinkRoute::Voice)
                    ?: if (parts.size == 1) DeepLinkRoute.Voice(null) else null
            parts.firstOrNull() == "lurk" && parts.size in 2..3 ->
                positive(parts[1])?.let { guildId ->
                    parts.getOrNull(2)?.let(::positive)?.let { DeepLinkRoute.Lurk(guildId, it) }
                        ?: if (parts.size == 2) DeepLinkRoute.Lurk(guildId, null) else null
                }
            parts.firstOrNull() == "ra" && parts.size == 2 && code.matches(parts[1]) -> DeepLinkRoute.RemoteAuth(parts[1])
            parts.firstOrNull() == "guild" && parts.getOrNull(2) == "premiumguild" && parts.size == 3 -> when (parts[1]) {
                "@me" -> DeepLinkRoute.PremiumGuild(null)
                else -> positive(parts[1])?.let(DeepLinkRoute::PremiumGuild)
            }
            parts.firstOrNull() == "events" && parts.size == 3 ->
                positive(parts[1])?.let { owner -> positive(parts[2])?.let { DeepLinkRoute.Event(owner, it) } }
            parts.firstOrNull() == "query" -> parseQuery(query)
            else -> null
        }
        return route?.let(LinkParseResult::Internal)
            ?: LinkParseResult.Unsupported(LinkParseResult.Reason.UNSUPPORTED_PATH)
    }

    private fun channel(parts: List<String>): DeepLinkRoute? {
        if (parts.size !in 2..4) return null
        val guildId = guild(parts[1]) ?: if (parts[1] == "@me") null else return null
        if (parts.size == 2) return DeepLinkRoute.Guild(guildId)
        if (parts.size == 3 && code.matches(parts[2]) && positive(parts[2]) == null) {
            return if (guildId != null && parts[2] == "role-subscriptions") {
                DeepLinkRoute.StaticChannel(guildId, parts[2])
            } else null
        }
        val channelId = positive(parts[2]) ?: return null
        val messageId = parts.getOrNull(3)?.let(::positive)
        if (parts.size == 4 && messageId == null) return null
        return DeepLinkRoute.Channel(guildId, channelId, messageId)
    }

    private fun setting(parts: List<String>): DeepLinkRoute? {
        val destination = parts.drop(1).joinToString("/")
        if (destination !in settings) return null
        return if (destination == "friends") DeepLinkRoute.Friends else DeepLinkRoute.Settings(destination)
    }

    private fun invite(value: String, query: Map<String, String>) = value.takeIf(code::matches)?.let {
        DeepLinkRoute.Invite(it, query["event"]?.let(::positive), query["source"]?.takeIf(String::isNotBlank))
    }

    private fun parseQuery(query: Map<String, String>): DeepLinkRoute? {
        val type = query["type"]?.takeIf { it in setOf("channel", "server", "user") } ?: return null
        if (type == "user" && query["userName"].isNullOrBlank() && query["userNameAlt"].isNullOrBlank()) return null
        return DeepLinkRoute.Query(
            type, query["channelName"], query["serverName"], query["userName"],
            query["userNameAlt"], query["messageText"],
        )
    }

    private fun guild(value: String): Long? = if (value == "@me") null else positive(value)
    private fun positive(value: String): Long? = value.toLongOrNull()?.takeIf { it > 0 }
    internal fun isDiscordHost(host: String?): Boolean = host in shortDiscordHosts || host in discordHosts ||
        discordHosts.any { host?.removeSuffix(".$it") in setOf("www", "ptb", "canary") }

    private fun query(raw: String?): Map<String, String>? = runCatching {
        raw.orEmpty().split('&').filter(String::isNotEmpty).associate { field ->
            val parts = field.split('=', limit = 2)
            decode(parts[0]) to decode(parts.getOrElse(1) { "" })
        }
    }.getOrNull()

    private fun decode(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}

object ExternalUriPolicy {
    fun isAllowed(value: String): Boolean = runCatching { isAllowed(URI(value)) }.getOrDefault(false)

    internal fun isAllowed(uri: URI): Boolean {
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.isOpaque || uri.userInfo != null || uri.port !in setOf(-1, 443)) {
            return false
        }
        val host = uri.host ?: return false
        val asciiHost = runCatching { IDN.toASCII(host).lowercase(Locale.ROOT).trimEnd('.') }.getOrNull() ?: return false
        return asciiHost.contains('.') && asciiHost != "localhost" && !asciiHost.endsWith(".local") &&
            ':' !in asciiHost &&
            !Regex("(?:\\d{1,3}\\.){3}\\d{1,3}").matches(asciiHost) &&
            asciiHost.none(Char::isWhitespace) && '\\' !in uri.rawSchemeSpecificPart
    }
}
