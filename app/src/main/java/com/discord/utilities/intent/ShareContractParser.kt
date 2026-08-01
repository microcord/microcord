package com.discord.utilities.intent

import java.net.URI

internal data class NormalizedShare(val text: String?, val attachments: List<String>)

internal object ShareContractParser {
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

    fun parse(
        action: String?,
        text: CharSequence?,
        subject: String?,
        attachments: List<String>,
    ): NormalizedShare? {
        if (action != ACTION_SEND && action != ACTION_SEND_MULTIPLE) return null
        if (action == ACTION_SEND && attachments.size > 1) return null
        if (attachments.any { !isSafeAttachment(it) }) return null
        val selectedText = text?.toString()?.takeUnless(String::isBlank)
            ?: subject?.takeUnless(String::isBlank)
        return if (selectedText != null || attachments.isNotEmpty()) {
            NormalizedShare(selectedText, attachments)
        } else null
    }

    fun isSdkUri(value: String?): Boolean = runCatching {
        val uri = URI(value ?: return false)
        (uri.scheme.equals("discord", true) || uri.scheme.equals("manticord", true)) && uri.host.equals("sdk", true) &&
            !uri.isOpaque && uri.userInfo == null && uri.port == -1 && uri.path.startsWith('/')
    }.getOrDefault(false)

    private fun isSafeAttachment(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "content" && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)
}
