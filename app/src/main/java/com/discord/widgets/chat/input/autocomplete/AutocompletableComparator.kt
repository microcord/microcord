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
 * actually reads — nickname before username, channel display name before raw name.
 *
 * The comparator is used as a `TreeSet` ordering, so returning 0 does not merely leave two rows
 * unordered, it *discards* one of them. Every kind whose label can repeat therefore ends in a
 * tiebreak on something unique: two members can share a nickname, a guild emoji can share a name
 * with a unicode one, two roles or two apps can share a name. Only the placeholder kinds return
 * 0, and at most one of each ever exists.
 */
class AutocompletableComparator : Comparator<Autocompletable> {

    override fun compare(o1: Autocompletable, o2: Autocompletable): Int {
        if (o1::class != o2::class) return o1.getSortIndex().compareTo(o2.getSortIndex())

        return when (o1) {
            is ApplicationCommandChoiceAutocompletable -> {
                val other = o2 as ApplicationCommandChoiceAutocompletable
                compareBy(o1.choice.name.lower(), other.choice.name.lower())
                    ?: o1.choice.value.toString().compareTo(other.choice.value.toString())
            }

            is ApplicationCommandLoadingPlaceholder -> 0

            is ApplicationCommandAutocompletable -> {
                val other = o2 as ApplicationCommandAutocompletable
                val sameApp = o1.application?.id == other.application?.id
                val byName = if (sameApp) {
                    compareBy(o1.command.name.lower(), other.command.name.lower())
                } else {
                    compareBy(
                        o1.application?.name.toString().lower(),
                        other.application?.name.toString().lower(),
                    )
                }
                byName ?: o1.command.id.compareTo(other.command.id)
            }

            is ApplicationPlaceholder -> {
                val other = o2 as ApplicationPlaceholder
                compareBy(o1.application.name.lower(), other.application.name.lower())
                    ?: o1.application.id.compareTo(other.application.id)
            }

            is ChannelAutocompletable -> {
                val other = o2 as ChannelAutocompletable
                compareBy(o1.channel.getDisplayName().lower(), other.channel.getDisplayName().lower())
                    ?: o1.channel.id.compareTo(other.channel.id)
            }

            is EmojiAutocompletable -> {
                val other = o2 as EmojiAutocompletable
                compareBy(o1.emoji.firstName.lower(), other.emoji.firstName.lower())
                    ?: o1.emoji.getUniqueId().compareTo(other.emoji.getUniqueId())
            }

            is EmojiUpsellPlaceholder -> 0

            is GlobalRoleAutocompletable ->
                o1.text.lower().compareTo((o2 as GlobalRoleAutocompletable).text.lower())

            is RoleAutocompletable -> {
                val other = o2 as RoleAutocompletable
                compareBy(o1.role.name.lower(), other.role.name.lower())
                    ?: o1.role.id.compareTo(other.role.id)
            }

            is UserAutocompletable -> {
                val other = o2 as UserAutocompletable
                val left = o1.user.getDisplayName(o1.nickname).lower()
                val right = other.user.getDisplayName(other.nickname).lower()
                compareBy(left, right) ?: o1.user.id.compareTo(other.user.id)
            }
        }
    }
}

/** The ordering of two labels, or `null` when they are equal and a tiebreak is needed. */
private fun compareBy(left: String, right: String): Int? =
    left.compareTo(right).takeIf { it != 0 }

internal fun String.lower(): String = lowercase(Locale.getDefault())
