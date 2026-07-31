@file:JvmName("AutocompletableKt")

package com.discord.widgets.chat.input.autocomplete

fun Autocompletable.getSortIndex(): Int = when (this) {
    is UserAutocompletable -> 0
    is RoleAutocompletable -> 1
    is GlobalRoleAutocompletable -> 2
    is ChannelAutocompletable -> 3
    is EmojiAutocompletable -> 4
    is ApplicationCommandAutocompletable -> 5
    is ApplicationCommandChoiceAutocompletable -> 6
    is ApplicationCommandLoadingPlaceholder -> 7
    is ApplicationPlaceholder -> 8
    is EmojiUpsellPlaceholder -> 9
}
