package com.discord.utilities.feature

import androidx.annotation.StringRes
import com.discord.R
import com.discord.utilities.cache.SharedPreferencesProvider

object ChatInputCustomization {
    enum class Button(@param:StringRes val label: Int) {
        PROFILE(R.string.profile),
        ATTACHMENT(R.string.attachment_media),
        APPS(R.string.apps),
        EXPAND(R.string.expand_buttons),
        EXPRESSION(R.string.emoji),
        SEND(R.string.chat_input_customization_send_voice),
    }

    enum class Zone(@param:StringRes val label: Int) {
        LEFT(R.string.chat_input_customization_left),
        INSIDE(R.string.chat_input_customization_inside),
        INSIDE_END(R.string.chat_input_customization_inside_end),
        RIGHT(R.string.chat_input_customization_right),
        HIDDEN(R.string.chat_input_customization_hidden),
    }

    data class Entry(val button: Button, val zone: Zone)

    const val LAYOUT_KEY = "feature_chatbox_customizations_layout"
    private val defaults = listOf(
        Entry(Button.PROFILE, Zone.LEFT),
        Entry(Button.ATTACHMENT, Zone.LEFT),
        Entry(Button.APPS, Zone.LEFT),
        Entry(Button.EXPAND, Zone.HIDDEN),
        Entry(Button.EXPRESSION, Zone.INSIDE_END),
        Entry(Button.SEND, Zone.RIGHT),
    )

    fun get(): List<Entry> = decode(SharedPreferencesProvider.get().getString(LAYOUT_KEY, null))

    fun save(entries: List<Entry>) {
        SharedPreferencesProvider.get().edit().putString(LAYOUT_KEY, encode(entries)).apply()
    }

    fun defaultZone(button: Button) = defaults.first { it.button == button }.zone.takeUnless { it == Zone.HIDDEN }
        ?: Zone.LEFT

    internal fun encode(entries: List<Entry>) = entries.joinToString(",") { "${it.button.name}:${it.zone.name}" }

    internal fun decode(value: String?): List<Entry> {
        val parsed = value.orEmpty().split(',').mapNotNull { item ->
            val parts = item.split(':', limit = 2)
            val button = parts.getOrNull(0)?.let { runCatching { Button.valueOf(it) }.getOrNull() } ?: return@mapNotNull null
            val zone = parts.getOrNull(1)?.let { runCatching { Zone.valueOf(it) }.getOrNull() } ?: return@mapNotNull null
            Entry(button, zone)
        }.distinctBy(Entry::button)
        return parsed + defaults.filter { fallback -> parsed.none { it.button == fallback.button } }
    }
}
