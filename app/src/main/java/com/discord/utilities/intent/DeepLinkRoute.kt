package com.discord.utilities.intent

sealed interface DeepLinkRoute {
    data object Home : DeepLinkRoute
    data object Friends : DeepLinkRoute
    data object Discovery : DeepLinkRoute
    data class ApplicationDirectory(val applicationId: Long? = null) : DeepLinkRoute
    /**
     * @param targetChannelId `?channel=` and [targetMessageId] `?message=`, the deep-link context
     *   the resolve endpoint validates and echoes back on the invite.
     * @param installationId `?installationId=`, handed back on accept so the server can attribute
     *   the install that followed the link.
     */
    data class Invite(
        val code: String,
        val eventId: Long?,
        val source: String?,
        val targetChannelId: Long? = null,
        val targetMessageId: Long? = null,
        val installationId: String? = null,
    ) : DeepLinkRoute
    data class Gift(val code: String) : DeepLinkRoute
    data class GuildTemplate(val code: String) : DeepLinkRoute
    data class Guild(val guildId: Long?) : DeepLinkRoute
    data class Channel(val guildId: Long?, val channelId: Long, val messageId: Long?) : DeepLinkRoute
    data class DirectMessage(val userId: Long) : DeepLinkRoute
    data class User(val userId: Long, val friendToken: String?) : DeepLinkRoute
    data class Settings(val destination: String) : DeepLinkRoute
    data class Voice(val channelId: Long?) : DeepLinkRoute
    data class Lurk(val guildId: Long, val channelId: Long?) : DeepLinkRoute
    /** @param fingerprint null when the link carried nothing usable, which reopens the scanner. */
    data class RemoteAuth(val fingerprint: String?) : DeepLinkRoute {
        override fun toString() = "RemoteAuth(fingerprint=<redacted>)"
    }
    /** `discord.com/login/one-time?token=`, the only credential recovery the app redeems itself. */
    data class OneTimeLogin(val ticket: String) : DeepLinkRoute {
        override fun toString() = "OneTimeLogin(ticket=<redacted>)"
    }

    data class PremiumGuild(val guildId: Long?) : DeepLinkRoute
    data class Event(val guildOrChannelId: Long, val eventId: Long) : DeepLinkRoute
    data class StaticChannel(val guildId: Long, val destination: String) : DeepLinkRoute
    data class Authorization(val connect: Boolean, val parameters: Map<String, String>) : DeepLinkRoute {
        override fun toString() = "Authorization(connect=$connect, parameters=<redacted>)"
    }
    data class OAuthCallback(val parameters: Map<String, String>) : DeepLinkRoute {
        override fun toString() = "OAuthCallback(parameters=<redacted>)"
    }
    data class Query(
        val type: String,
        val channelName: String?,
        val serverName: String?,
        val userName: String?,
        val alternateUserName: String?,
        val messageText: String?,
    ) : DeepLinkRoute {
        override fun toString() = "Query(type=$type, content=<redacted>)"
    }
}

sealed interface LinkParseResult {
    data class Internal(val route: DeepLinkRoute) : LinkParseResult
    data class External(val uri: String) : LinkParseResult
    data class Unsupported(val reason: Reason) : LinkParseResult

    enum class Reason { EMPTY, MALFORMED, UNSAFE_SCHEME, UNSUPPORTED_HOST, UNSUPPORTED_PATH }
}
