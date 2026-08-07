package com.discord.widgets.chat.input.autocomplete

import com.discord.api.channel.isGuildTextyChannel
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.commands.ApplicationCommandType
import com.discord.api.commands.CommandChoice
import com.discord.models.commands.ApplicationCommandOption
import com.discord.utilities.string.stripAccents
import com.discord.widgets.chat.input.DEFAULT_LEADING_IDENTIFIERS
import com.discord.widgets.chat.input.getSelectedToken
import com.discord.widgets.chat.input.models.ChatInputMentionsMap
import com.discord.widgets.chat.input.models.MentionToken
import java.util.Locale
import java.util.regex.Pattern

/**
 * Turns raw composer text plus a caret position into an autocomplete query, and matches that
 * query against the candidate set.
 *
 * Everything here is pure: no stores, no views, no Rx. That is deliberate — this is the part of
 * autocomplete that has to be exactly right, and it is the part that is unit-testable.
 *
 * Reference: `com.discord.widgets.chat.input.autocomplete.AutocompleteModelUtils`.
 */
object AutocompleteModelUtils {

    /**
     * A slash command is autocompleted from the *start of the input*, not from the caret's word:
     * `/ban some` must keep offering `/ban` while the user types its arguments. Up to three
     * whitespace-separated words are absorbed, covering `/group subcommand`.
     */
    private val COMMAND_PREFIX: Pattern =
        Pattern.compile("^(/([a-zA-Z0-9_-]+\\s*){1,3})(.|\\n)*")

    /**
     * The query for a plain message composer.
     *
     * Returns `null` when the caret is not inside an autocompletable token — either there is no
     * word under the caret, or the word does not start with one of
     * [DEFAULT_LEADING_IDENTIFIERS].
     */
    fun getMessageAutocompleteToken(input: CharSequence, selection: IntRange): MentionToken? {
        val (selectedToken, selectedIndex) = getSelectedToken(input.toString(), selection.first)

        val matcher = COMMAND_PREFIX.matcher(input)
        return if (matcher.matches()) {
            matcher.group(0).asMentionToken(0)
        } else {
            selectedToken.asMentionToken(selectedIndex)
        }
    }

    fun getCommandOptionAutocompleteToken(
        input: CharSequence,
        selection: IntRange,
        option: ApplicationCommandOption,
        commandOptions: List<ApplicationCommandOption>,
    ): MentionToken? {
        val valueRange = findCommandOptionValueRange(input, option, commandOptions) ?: return null
        val caret = selection.first.coerceIn(0, input.length)
        if (caret !in valueRange.first..valueRange.last) return null
        val selectedToken = input.subSequence(valueRange.first, caret).toString()

        val expectedIdentifier = when (option.type) {
            ApplicationCommandType.USER,
            ApplicationCommandType.ROLE,
            ApplicationCommandType.MENTIONABLE,
            -> LeadingIdentifier.MENTION

            ApplicationCommandType.CHANNEL -> LeadingIdentifier.CHANNELS
            else -> LeadingIdentifier.NONE
        }
        val token = selectedToken.asMentionToken(valueRange.first, expectedIdentifier == LeadingIdentifier.NONE)
            ?: return null
        return token.takeIf { it.leadingIdentifier == expectedIdentifier }
    }

    fun findCommandOptionValueRange(
        input: CharSequence,
        option: ApplicationCommandOption,
        commandOptions: List<ApplicationCommandOption>,
    ): IntRange? {
        val text = input.toString()
        val marker = Regex("(?:^|\\s)${Regex.escape(option.name)}:").find(text) ?: return null
        val start = marker.range.last + 1
        val end = commandOptions.asSequence()
            .filterNot { it == option }
            .mapNotNull { candidate ->
                Regex("\\s${Regex.escape(candidate.name)}:").find(text, start)?.range?.first
            }
            .minOrNull()
            ?: text.length
        return start..end
    }

    fun autocompletablesForCommandOption(
        option: ApplicationCommandOption,
        all: Map<LeadingIdentifier, Set<Autocompletable>>,
    ): Map<LeadingIdentifier, Set<Autocompletable>> {
        val choices = when {
            option.type == ApplicationCommandType.BOOLEAN -> listOf(
                CommandChoice("true", "true"),
                CommandChoice("false", "false"),
            )
            !option.choices.isNullOrEmpty() -> option.choices
            else -> null
        }
        if (choices != null) {
            return mapOf(
                LeadingIdentifier.NONE to choices.mapTo(LinkedHashSet()) {
                    ApplicationCommandChoiceAutocompletable(it, option.name)
                },
            )
        }

        val candidates = when (option.type) {
            ApplicationCommandType.USER ->
                all[LeadingIdentifier.MENTION].orEmpty().filterIsInstance<UserAutocompletable>()
            ApplicationCommandType.ROLE ->
                all[LeadingIdentifier.MENTION].orEmpty().filterIsInstance<RoleAutocompletable>()
            ApplicationCommandType.MENTIONABLE ->
                all[LeadingIdentifier.MENTION].orEmpty().filter {
                    it is UserAutocompletable || it is RoleAutocompletable
                }
            ApplicationCommandType.CHANNEL ->
                all[LeadingIdentifier.CHANNELS].orEmpty().filterIsInstance<ChannelAutocompletable>()
                    .filter { option.channelTypes.isNullOrEmpty() || it.channel.type in option.channelTypes }
            else -> emptyList()
        }
        val identifier = when (option.type) {
            ApplicationCommandType.USER,
            ApplicationCommandType.ROLE,
            ApplicationCommandType.MENTIONABLE,
            -> LeadingIdentifier.MENTION
            ApplicationCommandType.CHANNEL -> LeadingIdentifier.CHANNELS
            else -> LeadingIdentifier.NONE
        }
        return mapOf(identifier to candidates.toSet())
    }

    /**
     * Wraps [this] in a [MentionToken] when it opens with a known leading identifier.
     *
     * [useNullLeadingIdentifier] is what lets a *command option value* be autocompleted with no
     * leading character at all — the caller already knows the caret is inside an option, so the
     * identifier check is skipped and the token is bucketed under [LeadingIdentifier.NONE].
     */
    internal fun String?.asMentionToken(
        tokenPosition: Int,
        useNullLeadingIdentifier: Boolean = false,
    ): MentionToken? {
        val token: String = this ?: return null
        val leading: Char? =
            if (token.isNotBlank() && DEFAULT_LEADING_IDENTIFIERS.contains(token[0])) token[0] else null
        if (leading == null && !useNullLeadingIdentifier) return null
        return MentionToken(
            leadingIdentifier = LeadingIdentifier.fromChar(leading),
            token = token,
            isAtStart = tokenPosition == 0,
            startIndex = tokenPosition,
        )
    }

    /**
     * Drops candidates that are not legal in an ordinary message: channels the client cannot
     * render as a link, users who cannot read the channel, and roles the author may not ping.
     */
    fun filterAutocompletablesForMessageContext(
        autocompletables: List<Autocompletable>,
    ): List<Autocompletable> = autocompletables.filter {
        when (it) {
            // Vocal channels carry a chat, so a mention of one resolves to a real link.
            is ChannelAutocompletable ->
                it.channel.isGuildTextyChannel() || it.channel.isGuildVocalChannel()
            is UserAutocompletable -> it.canUserReadChannel
            is RoleAutocompletable -> it.canMention
            else -> true
        }
    }

    /**
     * Ranks [mentions] against [token].
     *
     * Matching is deliberately forgiving on both sides: the query and every candidate matcher are
     * lower-cased, accent-stripped, and stripped of spaces before a substring test. That is what
     * makes `@jose` find `José` and `#off topic` find `#off-topic`… while still being a substring
     * test rather than a fuzzy score, so `@bc` does *not* match `abc def`'s initials.
     *
     * An "empty" token — no text after the identifier and no identifier either — matches nothing.
     * Without that guard, moving the caret onto a blank line would open the popup with the entire
     * member list.
     */
    fun filterMentionsFromToken(
        token: MentionToken,
        mentions: Map<LeadingIdentifier, List<Autocompletable>>,
    ): List<Autocompletable> {
        val isEmptyQuery =
            token.formattedToken.isBlank() && token.leadingIdentifier == LeadingIdentifier.NONE
        if (isEmptyQuery) return emptyList()

        val query = token.formattedToken.normalizeForMatching()
        val candidates = mentions[token.leadingIdentifier] ?: return emptyList()

        return candidates.filter { candidate ->
            val matchers = candidate.iterateAutocompleteMatchers()
            var matched = false
            while (!matched && matchers.hasNext()) {
                matched = matchers.next().normalizeForMatching().contains(query)
            }
            matched
        }
    }

    /**
     * Locates every finished mention in [input] so the composer can draw pills over them.
     *
     * The overlap rules are the subtle part, and they exist because matchers are not prefix-free:
     *
     * * A longer match wins over a shorter one it contains, so `@ab` inside `@abc` is discarded.
     * * A shorter match already recorded is *removed* when a longer one arrives.
     * * For an exact range collision, a [RoleAutocompletable] wins — a role and a user can share
     *   a name, and the role form (`<@&id>`) is the one the server will echo back.
     * * A range already present in [currentInputMentionMap] keeps its existing candidate, so a
     *   pill the user explicitly picked is never silently re-bound to a different user.
     */
    fun mapInputToMentions(
        input: String,
        autocompletables: Map<LeadingIdentifier, Set<Autocompletable>>,
        currentInputMentionMap: Map<IntRange, Autocompletable>,
        isCommand: Boolean,
    ): ChatInputMentionsMap {
        val result = LinkedHashMap<IntRange, Autocompletable>()

        // Only scan buckets whose identifier actually occurs in the input. On a large guild this
        // is the difference between iterating a handful of channels and iterating every member.
        val relevant = autocompletables.filterKeys { identifier ->
            identifier.identifier?.let { input.contains(it) } ?: true
        }

        val candidates = relevant.values.flatten() + currentInputMentionMap.values
        val searchable = if (isCommand) candidates else filterAutocompletablesForMessageContext(candidates)

        for (candidate in searchable) {
            val matchers = candidate.iterateTextMatchers()
            while (matchers.hasNext()) {
                val matcher = matchers.next()
                var index = input.indexOf(matcher)
                while (index != -1) {
                    val range = IntRange(index, index + matcher.length)
                    if (shouldRecord(range, candidate, result)) {
                        result[range] = currentInputMentionMap[range] ?: candidate
                    }
                    index = input.indexOf(matcher, index + 1)
                }
            }
        }

        return ChatInputMentionsMap(input, result)
    }

    /**
     * Decides whether [range] should be added to [recorded], evicting shorter overlapping ranges
     * as a side effect. Returns `false` when an existing entry already covers this range.
     */
    private fun shouldRecord(
        range: IntRange,
        candidate: Autocompletable,
        recorded: MutableMap<IntRange, Autocompletable>,
    ): Boolean {
        if (recorded.isEmpty()) return true
        val iterator = recorded.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val existing = entry.key
            val roleWinsTie =
                range == existing && candidate is RoleAutocompletable && entry.value !is RoleAutocompletable
            if (roleWinsTie || existing.isSubRangeOf(range)) {
                iterator.remove()
                continue
            }
            if (range == existing || range.isSubRangeOf(existing)) return false
        }
        return true
    }

    /**
     * Re-maps a recorded mention range after the composer text changed at [start], where [before]
     * characters were replaced by [count].
     *
     * Returns `null` when the edit touched the mention itself, which is how a pill is destroyed by
     * typing inside it.
     */
    fun IntRange.shiftOrRemove(start: Int, before: Int, count: Int): IntRange? {
        if (start >= last) return this
        val delta = count - before
        return when {
            start < first && start + before >= first -> null
            start > first && start < last -> null
            start >= first && start < last && before > 0 -> null
            first >= start + count - before || (first == start && before == 0) ->
                IntRange(first + delta, last + delta)
            else -> null
        }
    }

    /** True when [this] is strictly contained by [other]. Equal ranges are not sub-ranges. */
    private fun IntRange.isSubRangeOf(other: IntRange): Boolean =
        this != other && other.contains(first) && other.contains(last)

    /**
     * Lower-cases, strips diacritics, and removes spaces. Applied identically to the query and to
     * every matcher so the two are compared in the same space.
     */
    private fun String.normalizeForMatching(): String =
        lowercase(Locale.getDefault()).stripAccents().replace(" ", "")
}
