package com.discord.widgets.chat.input.autocomplete

import com.discord.api.channel.getDisplayName
import com.discord.utilities.user.UserUtils.getUserNameWithDiscriminator
import java.util.Locale
import com.discord.utilities.user.UserUtils.getDisplayName

/**
 * Total ordering over autocomplete rows.
 *
 * Rows of different kinds are ordered by [getSortIndex] alone, so the popup never interleaves
 * users with channels. Within a kind the comparison is case-insensitive on the label the user
 * actually reads — nickname before username, channel display name before raw name — with a
 * stable id tiebreak for channels, which can legitimately share a name.
 *
 * The comparator is used as a `TreeSet` ordering, so it must never return 0 for rows that should
 * both survive; it deliberately does return 0 for the two placeholder kinds, of which at most one
 * of each exists.
 */
class AutocompletableComparator : Comparator<Autocompletable> {

    override fun compare(o1: Autocompletable, o2: Autocompletable): Int {
        if (o1::class != o2::class) return o1.getSortIndex().compareTo(o2.getSortIndex())

        return when (o1) {
            is ApplicationCommandChoiceAutocompletable ->
                o1.choice.name.lower()
                    .compareTo((o2 as ApplicationCommandChoiceAutocompletable).choice.name.lower())

            is ApplicationCommandLoadingPlaceholder -> 0

            is ApplicationCommandAutocompletable -> {
                val other = o2 as ApplicationCommandAutocompletable
                if (o1.application?.id == other.application?.id) {
                    o1.command.name.lower().compareTo(other.command.name.lower())
                } else {
                    o1.application?.name.toString().lower()
                        .compareTo(other.application?.name.toString().lower())
                }
            }

            is ApplicationPlaceholder ->
                o1.application.name.lower().compareTo((o2 as ApplicationPlaceholder).application.name.lower())

            is ChannelAutocompletable -> {
                val other = o2 as ChannelAutocompletable
                val left = o1.channel.getDisplayName().lower()
                val right = other.channel.getDisplayName().lower()
                if (left != right) left.compareTo(right)
                else o1.channel.id.compareTo(other.channel.id)
            }

            is EmojiAutocompletable ->
                o1.emoji.firstName.lower().compareTo((o2 as EmojiAutocompletable).emoji.firstName.lower())

            is EmojiUpsellPlaceholder -> 0

            is GlobalRoleAutocompletable ->
                o1.text.lower().compareTo((o2 as GlobalRoleAutocompletable).text.lower())

            is RoleAutocompletable ->
                o1.role.name.lower().compareTo((o2 as RoleAutocompletable).role.name.lower())

            is UserAutocompletable -> {
                val other = o2 as UserAutocompletable
                val left = o1.user.getDisplayName(o1.nickname)
                val right = other.user.getDisplayName(other.nickname)
                left.lower().compareTo(right.lower())
            }
        }
    }
}

internal fun String.lower(): String = lowercase(Locale.getDefault())
