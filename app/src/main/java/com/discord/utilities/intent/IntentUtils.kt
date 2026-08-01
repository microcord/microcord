package com.discord.utilities.intent

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.discord.R
import com.discord.stores.IntentRouteResult
import com.discord.stores.StoreStream
import java.util.Locale

object IntentUtils {
    @JvmStatic
    @JvmOverloads
    fun performChooserSendIntent(
        context: Context,
        text: String,
        chooserText: CharSequence = context.getString(R.string.share_to),
    ) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, chooserText))
    }

    fun consumeExternalRoutingIntent(intent: Intent, context: Context): Boolean =
        consumeRoutingIntent(intent, context)

    fun consumeRoutingIntent(
        intent: Intent,
        context: Context,
        callback: (Uri, Boolean) -> Unit = { _, _ -> },
    ): Boolean {
        val uri = intent.data ?: Uri.EMPTY
        val routable = isDiscordAppUri(uri) || isHttpDomainUrl(uri)
        callback(uri, routable)
        if (!routable) return false

        val contract = AppIntentParser.parse(Intent(Intent.ACTION_VIEW, uri))
        val consumed = runCatching { StoreStream.getNavigation().routeIntent(contract) }
            .getOrNull() == IntentRouteResult.Queued
        if (consumed) intent.data = Uri.EMPTY
        return consumed
    }

    fun Intent.getDirectShareId(): Long? =
        getStringExtra("android.intent.extra.shortcut.ID")?.toLongOrNull()

    fun isDiscordAppUri(uri: Uri): Boolean =
        uri.scheme?.lowercase(Locale.ROOT) in setOf("discord", "manticord") &&
            uri.host?.lowercase(Locale.ROOT) in setOf("app", "action") &&
            uri.userInfo == null && uri.port == -1

    fun isHttpDomainUrl(uri: Uri): Boolean {
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null || uri.port !in setOf(-1, 443)) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return DeepLinkParser.isDiscordHost(host)
    }

    fun Intent.toExternalizedSend(): Intent {
        val external = (data ?: Uri.EMPTY).buildUpon()
            .scheme("https")
            .authority("discord.com")
            .build()
        return setData(external)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, external.toString())
    }

    object RouteBuilders {
        @JvmStatic
        fun selectChannel(channelId: Long, guildId: Long = 0L, messageId: Long? = null): Intent =
            view("discord://app/channels/${guildSegment(guildId)}/$channelId/${messageId ?: ""}")

        @JvmStatic
        fun selectExternalEvent(guildId: Long, eventId: Long?): Intent =
            view("discord://app/events/${guildSegment(guildId)}/$eventId")

        @JvmStatic
        fun selectGuild(guildId: Long): Intent =
            view("discord://app/channels/${guildSegment(guildId)}")

        @JvmStatic
        fun selectUserProfile(userId: Long): Intent =
            view("discord://app/users/$userId")

        fun connectVoice(voiceChannelId: Long): Intent =
            Intent("com.discord.intent.action.CONNECT", Uri.parse("discord://app/connect/$voiceChannelId"))

        fun selectDirectMessage(userId: Long): Intent =
            view("discord://app/channels/@me/user/$userId")

        fun selectGuildTemplate(guildTemplateText: String?, source: String?): Intent =
            Intent().setData(routeInput(guildTemplateText, "discord.new", "template", source))

        fun selectInvite(inviteText: String?, source: String?): Intent =
            Intent().setData(routeInput(inviteText, "discord.gg", "invite", source))

        private fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

        private fun guildSegment(guildId: Long): Any =
            if (guildId == 0L || guildId == -1L) "@me" else guildId

        private fun routeInput(value: String?, webHost: String, appPath: String, source: String?): Uri {
            val parsed = Uri.parse(value)
            if (parsed.scheme != null) return parsed
            if (value?.startsWith(webHost) == true) return Uri.parse("https://$value")
            return Uri.parse("discord://app/$appPath/$value?source=$source")
        }

        object SDK {
            @JvmStatic
            fun join(deeplink: String, applicationId: Long, secret: String?): Intent =
                Intent(
                    "com.discord.intent.action.SDK",
                    Uri.parse(deeplink).buildUpon()
                        .appendPath("join")
                        .appendQueryParameter("application_id", applicationId.toString())
                        .appendQueryParameter("secret", secret)
                        .build(),
                )
        }

        object Uris {
            val app: Uri get() = Uri.parse("discord://app")
            val oauth2Authorize: Uri get() = Uri.parse("discord://action/oauth2/authorize")
            val selectSettingsVoice: Uri get() = Uri.parse("discord://app/settings/voice")
        }
    }
}
