package com.discord.utilities.intent

import android.content.Intent
import android.net.Uri
import com.discord.api.activity.Activity
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder

sealed interface AppIntentContract {
    data class Link(val result: LinkParseResult) : AppIntentContract
    data class Share(val content: SharedContent) : AppIntentContract
    data class SdkAction(val uri: Uri, val recipient: String?, val activity: Activity?) : AppIntentContract
    data object Unsupported : AppIntentContract
}

data class SharedContent(
    val text: CharSequence?,
    val attachments: List<Uri>,
    val mimeType: String?,
    val directShareId: Long?,
    val recipient: String?,
)

object AppIntentParser {
    const val ACTION_SDK = "com.discord.intent.action.SDK"
    const val EXTRA_RECIPIENT = "EXTRA_RECIPIENT"
    const val EXTRA_ACTIVITY = "com.discord.intent.extra.EXTRA_ACTIVITY"
    private const val EXTRA_SHORTCUT_ID = "android.intent.extra.shortcut.ID"
    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerDiscordApiTypeAdapters()
        .create()

    fun parse(intent: Intent): AppIntentContract = runCatching {
        when (intent.action) {
            Intent.ACTION_VIEW -> AppIntentContract.Link(DeepLinkParser.parse(intent.dataString))
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> parseShare(intent)
            ACTION_SDK -> parseSdk(intent)
            else -> AppIntentContract.Unsupported
        }
    }.getOrDefault(AppIntentContract.Unsupported)

    private fun parseShare(intent: Intent): AppIntentContract {
        val attachments = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelableUri(Intent.EXTRA_STREAM))
            else -> intent.parcelableUris(Intent.EXTRA_STREAM)
        }
        val normalized = ShareContractParser.parse(
            intent.action,
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
            attachments.map(Uri::toString),
        ) ?: return AppIntentContract.Unsupported
        return AppIntentContract.Share(
            SharedContent(
                normalized.text,
                attachments,
                intent.type,
                intent.getStringExtra(EXTRA_SHORTCUT_ID)?.toLongOrNull(),
                intent.getStringExtra(EXTRA_RECIPIENT),
            )
        )
    }

    private fun parseSdk(intent: Intent): AppIntentContract {
        val uri = intent.data ?: return AppIntentContract.Unsupported
        if (!ShareContractParser.isSdkUri(uri.toString())) return AppIntentContract.Unsupported
        val activity = intent.getStringExtra(EXTRA_ACTIVITY)?.let {
            runCatching { gson.fromJson(it, Activity::class.java) }.getOrNull()
        }
        return AppIntentContract.SdkAction(uri, intent.getStringExtra(EXTRA_RECIPIENT), activity)
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableUri(name: String): Uri? = getParcelableExtra(name)

    @Suppress("DEPRECATION")
    private fun Intent.parcelableUris(name: String): List<Uri> = getParcelableArrayListExtra<Uri>(name).orEmpty()
}
