package com.discord.widgets.chat.input.autocomplete.commands

import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import com.discord.api.commands.ApplicationCommandType
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.ApplicationCommandOption
import com.discord.stores.flattenSubCommands
import com.discord.widgets.chat.input.autocomplete.ApplicationCommandChoiceAutocompletable
import com.discord.widgets.chat.input.autocomplete.Autocompletable
import com.discord.widgets.chat.input.autocomplete.ChannelAutocompletable
import com.discord.widgets.chat.input.autocomplete.InputEditTextAction
import com.discord.widgets.chat.input.autocomplete.RoleAutocompletable
import com.discord.widgets.chat.input.autocomplete.UserAutocompletable
import com.discord.widgets.chat.input.models.AttachmentOptionValue
import com.discord.widgets.chat.input.models.AutocompleteApplicationCommands
import com.discord.widgets.chat.input.models.BooleanOptionValue
import com.discord.widgets.chat.input.models.ChannelOptionValue
import com.discord.widgets.chat.input.models.ChatInputMentionsMap
import com.discord.widgets.chat.input.models.CommandOptionValue
import com.discord.widgets.chat.input.models.InputCommandContext
import com.discord.widgets.chat.input.models.IntegerOptionValue
import com.discord.widgets.chat.input.models.MentionInputModel
import com.discord.widgets.chat.input.models.NumberOptionValue
import com.discord.widgets.chat.input.models.OptionRange
import com.discord.widgets.chat.input.models.RawOptionValue
import com.discord.widgets.chat.input.models.RoleOptionValue
import com.discord.widgets.chat.input.models.SnowflakeOptionValue
import com.discord.widgets.chat.input.models.StringOptionValue
import com.discord.widgets.chat.input.models.UserOptionValue
import com.lytefast.flexinput.model.Attachment
import java.text.NumberFormat
import java.text.ParsePosition

object AutocompleteCommandUtils {
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    @MainThread
    fun appendParam(
        input: CharSequence,
        applicationCommandsOption: ApplicationCommandOption,
        insertIndex: Int? = null,
    ): InputEditTextAction.InsertText {
        val prefix = if (insertIndex == null && !input.endsWith(' ')) " " else ""
        val identifier = when (applicationCommandsOption.type) {
            ApplicationCommandType.USER,
            ApplicationCommandType.ROLE,
            ApplicationCommandType.MENTIONABLE,
            -> "@"
            ApplicationCommandType.CHANNEL -> "#"
            else -> ""
        }
        val text = "$prefix${applicationCommandsOption.name}:$identifier"
        val index = insertIndex ?: input.length
        return InputEditTextAction.InsertText(input, text, index..index, input.length + text.length)
    }

    @MainThread
    fun appendTextForCommandForInput(model: MentionInputModel.VerifiedCommandInputModel): InputEditTextAction {
        val command = model.inputCommandContext.selectedCommand
        if (command != null && command.options.size == 1) {
            val option = command.options.first()
            val prefix = "/${command.name} "
            if (option !in model.inputCommandOptionsRanges &&
                model.input.length > prefix.length &&
                option.type != ApplicationCommandType.ATTACHMENT
            ) {
                return appendParam(model.input, option, prefix.length)
            }
        }
        return InputEditTextAction.None(model.input)
    }

    fun CharSequence.findOptionRanges(
        selectedCommand: ApplicationCommand?,
        attachments: Map<ApplicationCommandOption, Attachment<*>>,
    ): Map<ApplicationCommandOption, OptionRange> = buildMap {
        selectedCommand?.options?.forEach { option ->
            val paramStart = findStartOfParam(option.name) ?: return@forEach
            val valueRange = findValueRange(selectedCommand, option.name) ?: return@forEach
            val filename = attachments[option]?.displayName
            val adjustedValue = if (filename != null &&
                lastIndex in valueRange &&
                subSequence(valueRange.first, valueRange.last).contains(filename)
            ) {
                valueRange.first..(valueRange.first + filename.length)
            } else {
                valueRange
            }
            put(option, OptionRange(paramStart..valueRange.first, adjustedValue))
        }
    }

    @VisibleForTesting
    fun CharSequence.findStartOfParam(paramName: String): Int? =
        Regex("[\\s|\\n]${Regex.escape(paramName)}:").find(this)?.range?.first?.plus(1)

    @VisibleForTesting
    fun CharSequence.findStartOfValue(paramName: String): Int? =
        findStartOfParam(paramName)?.plus(paramName.length + 1)

    fun CharSequence.findValueRange(
        selectedCommand: ApplicationCommand?,
        paramName: String,
    ): IntRange? {
        val start = findStartOfValue(paramName) ?: return null
        val optionNames = selectedCommand?.options.orEmpty().mapTo(HashSet()) { it.name }
        val next = Regex(" ([\\p{L}0-9-_]*):")
            .findAll(subSequence(start, length))
            .firstOrNull { it.groupValues.getOrNull(1) in optionNames }
            ?.range
            ?.first
        return start..(next?.plus(start) ?: length)
    }

    fun CharSequence.getCommandPrefix(): String? =
        Regex("^(/([\\p{L}0-9-_]+\\s*){0,3})").find(this)?.groupValues?.getOrNull(1)

    fun getErrorsToShowForCommandParameters(
        selectedCommand: ApplicationCommand?,
        selectedCommandOption: ApplicationCommandOption?,
        previouslySelected: ApplicationCommandOption?,
        validMap: Map<ApplicationCommandOption, Boolean>,
        parsedCommandOptions: Map<ApplicationCommandOption, CommandOptionValue>,
    ): Set<ApplicationCommandOption> {
        if (selectedCommand == null) return emptySet()
        return selectedCommand.options.filterTo(LinkedHashSet()) { option ->
            val parsed = parsedCommandOptions[option] ?: return@filterTo false
            val value = parsed.value.toString()
            val blank = value.isBlank() || value.length == 1 && value[0] in "/:#@"
            val suppress = option == selectedCommandOption &&
                (option == previouslySelected || blank)
            validMap[option] == false && !suppress
        }
    }

    fun getInputValidity(
        selectedCommand: ApplicationCommand,
        parsedCommandOptions: Map<ApplicationCommandOption, CommandOptionValue>,
        commandOptionAttachments: Map<ApplicationCommandOption, Attachment<*>>,
        inputMentionsMap: ChatInputMentionsMap,
    ): Map<ApplicationCommandOption, Boolean> = buildMap {
        selectedCommand.options.forEach { option ->
            if (option.type == ApplicationCommandType.ATTACHMENT) {
                val present = option in commandOptionAttachments
                if (present || option.required) put(option, present)
                return@forEach
            }
            val parsed = parsedCommandOptions[option]
            if (parsed == null) {
                if (option.required) put(option, false)
                return@forEach
            }
            put(option, option.isValid(parsed, inputMentionsMap))
        }
    }

    fun getSelectedCommandOption(
        cursorPosition: Int,
        optionRanges: Map<ApplicationCommandOption, OptionRange>?,
    ): ApplicationCommandOption? = optionRanges?.entries?.firstOrNull { (option, range) ->
        range.param.first < cursorPosition && range.value.last >= cursorPosition &&
            option.type != ApplicationCommandType.ATTACHMENT
    }?.key

    fun parseCommandOptions(
        input: CharSequence,
        command: ApplicationCommand,
        mentionMap: ChatInputMentionsMap,
        attachments: Map<ApplicationCommandOption, Attachment<*>>,
    ): Map<ApplicationCommandOption, CommandOptionValue> = buildMap {
        input.findOptionRanges(command, attachments).forEach { (option, range) ->
            val source = input.subSequence(range.value.first, range.value.last).toString()
            val raw = source.trim()
            val match = mentionMap.mentions.values.firstOrNull { it.matchesText(raw) }
            val choiceValue = if (option.autocomplete && match is ApplicationCommandChoiceAutocompletable) {
                match.choice.value.toString()
            } else {
                option.choices?.firstOrNull { it.displayName.trim() == raw || it.name.trim() == raw }
                    ?.value?.toString() ?: raw
            }
            put(option, option.parseValue(choiceValue, source, match, attachments[option]))
        }
    }

    fun String?.transformParameterSpannableString(): String? {
        if (this == null) return null
        if (trim().endsWith(':')) return trim()
        if (':' !in this) return this
        val parts = split(':')
        return "${parts.firstOrNull()?.trim()}: ${parts.drop(1).joinToString(":").trim()}"
    }

    private fun ApplicationCommandOption.isValid(
        parsed: CommandOptionValue,
        mentions: ChatInputMentionsMap,
    ): Boolean {
        choices?.takeIf { it.isNotEmpty() }?.let { choices ->
            return choices.any { it.value.toString() == parsed.value.toString() }
        }
        if (autocomplete) {
            return mentions.mentions.values.filterIsInstance<ApplicationCommandChoiceAutocompletable>()
                .any { it.optionName == name }
        }
        return when (type) {
            ApplicationCommandType.STRING -> parsed is StringOptionValue && parsed.value.toString().isNotBlank()
                && minLength?.let { parsed.value.toString().length >= it } != false
                && maxLength?.let { parsed.value.toString().length <= it } != false
            ApplicationCommandType.INTEGER -> parsed is IntegerOptionValue &&
                minValue?.toLong()?.let { parsed.value as Long >= it } != false &&
                maxValue?.toLong()?.let { parsed.value as Long <= it } != false
            ApplicationCommandType.BOOLEAN -> parsed is BooleanOptionValue
            ApplicationCommandType.USER -> parsed is UserOptionValue || parsed is SnowflakeOptionValue
            ApplicationCommandType.CHANNEL -> parsed is ChannelOptionValue &&
                (channelTypes.isNullOrEmpty() || parsed.channel.type in channelTypes) || parsed is SnowflakeOptionValue
            ApplicationCommandType.ROLE -> parsed is RoleOptionValue || parsed is SnowflakeOptionValue
            ApplicationCommandType.MENTIONABLE -> parsed is UserOptionValue || parsed is RoleOptionValue || parsed is SnowflakeOptionValue
            ApplicationCommandType.NUMBER -> parsed is NumberOptionValue &&
                minValue?.toDouble()?.let { (parsed.value as Number).toDouble() >= it } != false &&
                maxValue?.toDouble()?.let { (parsed.value as Number).toDouble() <= it } != false
            ApplicationCommandType.SUBCOMMAND,
            ApplicationCommandType.SUBCOMMAND_GROUP,
            ApplicationCommandType.ATTACHMENT,
            ApplicationCommandType.UNKNOWN,
            -> true
        }
    }

    private fun ApplicationCommandOption.parseValue(
        value: String,
        source: String,
        match: Autocompletable?,
        attachment: Attachment<*>?,
    ): CommandOptionValue = when (type) {
        ApplicationCommandType.STRING -> StringOptionValue(value)
        ApplicationCommandType.INTEGER -> value.toLongOrNull()?.takeIf(::isSafeNumber)?.let(::IntegerOptionValue)
        ApplicationCommandType.BOOLEAN -> value.takeIf {
            it.equals("true", ignoreCase = true) || it.equals("false", ignoreCase = true)
        }?.toBoolean()?.let(::BooleanOptionValue)
        ApplicationCommandType.USER -> (match as? UserAutocompletable)?.let { UserOptionValue(it.user) }
            ?: USER_MENTION.matchEntire(value)?.groupValues?.get(1)?.toLongOrNull()?.let(::SnowflakeOptionValue)
        ApplicationCommandType.CHANNEL -> (match as? ChannelAutocompletable)?.let { ChannelOptionValue(it.channel) }
            ?: CHANNEL_MENTION.matchEntire(value)?.groupValues?.get(1)?.toLongOrNull()?.let(::SnowflakeOptionValue)
        ApplicationCommandType.ROLE -> (match as? RoleAutocompletable)?.let { RoleOptionValue(it.role) }
            ?: ROLE_MENTION.matchEntire(value)?.groupValues?.get(1)?.toLongOrNull()?.let(::SnowflakeOptionValue)
        ApplicationCommandType.MENTIONABLE -> when (match) {
            is UserAutocompletable -> UserOptionValue(match.user)
            is RoleAutocompletable -> RoleOptionValue(match.role)
            else -> MENTIONABLE.matchEntire(value)?.groupValues?.get(1)?.toLongOrNull()?.let(::SnowflakeOptionValue)
        }
        ApplicationCommandType.NUMBER -> parseNumber(value)?.takeIf(::isSafeNumber)?.let(::NumberOptionValue)
        ApplicationCommandType.ATTACHMENT -> attachment?.uri?.toString()?.let(::AttachmentOptionValue)
        else -> null
    } ?: value.toLongOrNull()?.takeIf { it > 1_420_070_400_000L }?.let(::SnowflakeOptionValue)
        ?: RawOptionValue(source)

    private fun parseNumber(value: String): Number? {
        val position = ParsePosition(0)
        return NumberFormat.getInstance().parse(value, position)
            ?.takeIf { position.index == value.length && position.index != 0 }
    }

    private fun isSafeNumber(value: Number): Boolean =
        value.toDouble() in -MAX_SAFE_INTEGER.toDouble()..MAX_SAFE_INTEGER.toDouble()

    private val USER_MENTION = Regex("^<@!?(\\d+)>$")
    private val ROLE_MENTION = Regex("^<@&(\\d+)>$")
    private val CHANNEL_MENTION = Regex("^<#(\\d+)>$")
    private val MENTIONABLE = Regex("^<@(?:!?|&)(\\d+)>$")
}
