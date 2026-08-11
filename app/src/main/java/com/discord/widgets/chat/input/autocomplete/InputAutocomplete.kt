package com.discord.widgets.chat.input.autocomplete

import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.commands.ApplicationCommandType
import com.discord.app.AppComponent
import com.discord.i18n.setI18nText
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.ApplicationCommandOption
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.rx.ui
import com.discord.stores.StoreStream
import com.discord.widgets.chat.input.ComposerMentionSpan
import com.discord.widgets.chat.input.applicationcommands.SelectedApplicationCommandAdapter
import com.discord.widgets.chat.input.autocomplete.adapter.ChatInputAutocompleteAdapter
import com.discord.widgets.chat.input.models.ApplicationCommandData
import com.lytefast.flexinput.model.Attachment

/**
 * Binds one composer to one [AutocompleteViewModel]: it feeds edits in, renders the resulting
 * [AutocompleteViewState], and splices the picked candidate back into the text.
 *
 * This is a controller rather than a `View` because the pieces it drives live in three different
 * places in `widget_chat_input.xml` (the header, the recycler, the sticker row) and the composer
 * itself is inside the FlexInput child fragment. The reference makes the same choice.
 *
 * Reference: `com.discord.widgets.chat.input.autocomplete.InputAutocomplete`. The full command
 * browser and attachment options remain separate from this typed-query/selected-command surface.
 */
class InputAutocomplete(
    private val appComponent: AppComponent,
    private val editText: EditText,
    private val autocompleteHeader: TextView,
    private val autocompleteRecyclerView: RecyclerView,
    private val stickersContainer: View,
    private val viewModel: AutocompleteViewModel,
) {

    private val adapter = ChatInputAutocompleteAdapter()
    private val selectedCommandRoot: View? = autocompleteRecyclerView.rootView.findViewById(
        R.id.application_commands_root,
    )
    private val selectedCommandDescription: TextView? = autocompleteRecyclerView.rootView.findViewById(
        R.id.chat_input_application_commands_option_description,
    )
    private val selectedCommandRecycler: RecyclerView? = autocompleteRecyclerView.rootView.findViewById(
        R.id.chat_input_application_commands_recycler,
    )
    private val selectedCommandAdapter: SelectedApplicationCommandAdapter? =
        selectedCommandRecycler?.let { recycler ->
                recycler.layoutManager = LinearLayoutManager(
                    recycler.context,
                    LinearLayoutManager.HORIZONTAL,
                    false,
                )
                SelectedApplicationCommandAdapter(recycler, ::focusCommandOption).also {
                    recycler.adapter = it
                    recycler.itemAnimator = null
                }
            }

    /**
     * Suppresses the [TextWatcher] while [applySelection] rewrites the text. Without it, splicing
     * a mention in would immediately re-open the popup on the text we just inserted.
     */
    private var applyingSelection = false
    private var pendingPillDeletion: Pair<Int, Int>? = null

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            if (applyingSelection || count <= after || s !is Spanned) return
            val deletedEnd = start + count
            val span = s.getSpans(start, deletedEnd, ComposerMentionSpan::class.java).firstOrNull() ?: return
            val spanStart = s.getSpanStart(span)
            val spanEnd = s.getSpanEnd(span)
            val removed = count - after
            val remainingStart = if (spanStart < start) spanStart else start
            val remainingEnd = if (spanEnd > deletedEnd) spanEnd - removed else start
            pendingPillDeletion = remainingStart to remainingEnd
        }
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            if (applyingSelection) return
            val remaining = pendingPillDeletion
            pendingPillDeletion = null
            if (s != null && remaining != null && remaining.second > remaining.first) {
                applyEditTextAction(
                    InputEditTextAction.RemoveText(
                        assumedInput = s.toString(),
                        range = remaining.first until remaining.second,
                        selectionIndex = remaining.first,
                    ),
                )
                return
            }
            replaceMentionPills()
            viewModel.onInputChanged(s ?: "", editText.selectionStart.coerceAtLeast(0).let { it..it })
        }
    }

    init {
        autocompleteRecyclerView.layoutManager = LinearLayoutManager(autocompleteRecyclerView.context)
        autocompleteRecyclerView.adapter = adapter
        adapter.onItemSelected = ::applySelection
        editText.addTextChangedListener(textWatcher)
    }

    /** Call from `onViewBoundOrOnResume`; the subscription ends with [appComponent]'s pause. */
    fun onViewBoundOrOnResume() {
        viewModel.observeViewState()
            .ui(appComponent)
            .subscribe({ configureUI(it) }, { /* a render failure must not kill the composer */ })
        viewModel.observeSelectedCommandState()
            .ui(appComponent)
            .subscribe({ configureSelectedCommand(it) }, { /* keep the composer usable */ })
    }

    fun onDestroyView() {
        editText.removeTextChangedListener(textWatcher)
        autocompleteRecyclerView.adapter = null
        selectedCommandRecycler?.adapter = null
    }

    /** True when the popup is open, so the host can consume a back press. */
    fun isShowing(): Boolean = autocompleteRecyclerView.visibility == View.VISIBLE

    fun dismiss() = viewModel.onAutocompleteDismissed()

    /** Re-targets the candidate set when the composer moves to another channel. */
    fun setChannelId(channelId: Long?) = viewModel.setChannelId(channelId)

    /**
     * The composer's text as the API should receive it.
     *
     * Whitespace-only input yields `""` rather than running the mention substitution — the
     * reference makes the same short-circuit, and it matters because a blank composer still holds
     * a mention map from before the user cleared it.
     */
    fun getInputContent(): String {
        val raw = editText.text?.toString().orEmpty()
        return if (raw.isBlank()) "" else viewModel.replaceAutocompletableDataWithServerValues(raw)
    }

    /**
     * Inserts a mention for [autocompletable] at the caret, as the member list's "Mention" action
     * does. Unlike [applySelection] there is no token to replace, so the text is only extended.
     */
    fun appendMention(autocompletable: Autocompletable) {
        val editable = editText.editableText ?: return
        val caret = editText.selectionStart.coerceIn(0, editable.length)
        val replacement = autocompletable.getInputReplacement() + " "
        applyingSelection = true
        try {
            editable.insert(caret, replacement)
            editText.setSelection((caret + replacement.length).coerceAtMost(editable.length))
        } finally {
            applyingSelection = false
        }
        resyncAfterEdit()
    }

    private fun configureUI(viewState: AutocompleteViewState) = when (viewState) {
        is AutocompleteViewState.Autocomplete -> showAutocomplete(viewState)
        is AutocompleteViewState.LoadingItems -> showLoading(viewState)
        AutocompleteViewState.Hidden -> hideAutocomplete()
    }

    private fun showAutocomplete(viewState: AutocompleteViewState.Autocomplete) {
        adapter.setData(
            renderedItems(viewState),
            hasStickers = viewState.stickers.isNotEmpty(),
            sizeToMax = viewState.isLoading && !viewState.isError,
        )
        autocompleteRecyclerView.visibility = View.VISIBLE
        stickersContainer.visibility = View.GONE
        if (viewState.isAutocomplete) {
            autocompleteHeader.visibility = if (viewState.token.isBlank()) View.GONE else View.VISIBLE
            autocompleteHeader.setTextColor(
                ColorCompat.getThemedColor(
                    autocompleteHeader.context,
                    if (viewState.isError) R.attr.colorTextDanger else R.attr.colorTextNormal,
                ),
            )
            val status = statusTextRes(viewState)
            if (status != null) autocompleteHeader.setText(status)
            else autocompleteHeader.setI18nText(R.string.emoji_matching, "prefix" to viewState.token)
        } else {
            autocompleteHeader.visibility =
                if (viewState.autocompletables.any { it is EmojiAutocompletable }) View.VISIBLE else View.GONE
            autocompleteHeader.setI18nText(R.string.emoji_matching, "prefix" to viewState.token)
        }
        adapter.scrollToPosition(0)
    }

    private fun showLoading(viewState: AutocompleteViewState.LoadingItems) {
        adapter.setData(
            List(viewState.numberLoadingItems) { ApplicationCommandLoadingPlaceholder() },
            sizeToMax = true,
        )
        autocompleteRecyclerView.visibility = View.VISIBLE
        autocompleteHeader.visibility = View.GONE
        stickersContainer.visibility = View.GONE
    }

    private fun hideAutocomplete() {
        autocompleteRecyclerView.visibility = View.GONE
        autocompleteHeader.visibility = View.GONE
        stickersContainer.visibility = View.GONE
    }

    fun getApplicationCommandData(attachments: List<Attachment<*>>): ApplicationCommandData? =
        viewModel.buildSelectedCommandData(editText.text ?: "", attachments)

    companion object {
        private const val REMOTE_LOADING_ITEMS = 4
        private val MENTION_TOKEN = Regex("<@!?(\\d+)>")

        /** A shortcode as the composer leaves it once an emoji has been picked. */
        private val EMOJI_TOKEN = Regex(":([a-zA-Z0-9_+~-]+):")

        internal fun renderedItems(state: AutocompleteViewState.Autocomplete): List<Autocompletable> =
            if (state.isLoading && !state.isError) {
                List(REMOTE_LOADING_ITEMS) { ApplicationCommandLoadingPlaceholder() }
            } else {
                state.autocompletables
            }

        internal fun statusTextRes(state: AutocompleteViewState.Autocomplete): Int? = when {
            state.isError -> R.string.application_command_autocomplete_failed
            !state.isLoading && state.autocompletables.isEmpty() ->
                R.string.application_command_autocomplete_no_options
            else -> null
        }
    }

    fun selectCommand(application: Application, command: ApplicationCommand) {
        if (!viewModel.selectAutocompleteItem(ApplicationCommandAutocompletable(application, command, true, true))) return
        applyingSelection = true
        try {
            editText.setText("/${command.name} ")
            editText.setSelection(editText.length())
        } finally {
            applyingSelection = false
        }
        command.options.firstOrNull { it.required }?.let(::focusCommandOption)
    }

    private fun configureSelectedCommand(state: SelectedCommandViewState) {
        when (state) {
            SelectedCommandViewState.Hidden -> {
                selectedCommandAdapter?.clear()
                selectedCommandRoot?.visibility = View.GONE
            }
            is SelectedCommandViewState.SelectedCommand -> {
                selectedCommandRoot?.visibility = View.VISIBLE
                selectedCommandAdapter?.clear()
                selectedCommandAdapter?.setApplicationCommand(state.command, state.application)
                selectedCommandAdapter?.setVerified(emptyMap(), emptySet())
                if (state.option != null) {
                    selectedCommandAdapter?.highlightOption(state.option)
                } else {
                    selectedCommandAdapter?.clearParamOptionHighlight()
                }
                selectedCommandDescription?.text = state.option?.description
                    ?: state.option?.descriptionRes?.let(editText.resources::getText)
                    ?: state.command.description
                    ?: state.command.descriptionRes?.let(editText.resources::getText)
            }
        }
    }

    /**
     * Replaces the token under the caret with the picked candidate's input form, plus a trailing
     * space so the next word starts a fresh token.
     *
     * The replacement range is recomputed from the *current* text rather than trusting the token
     * captured when the popup was populated: an in-flight keystroke can arrive between render and
     * tap, and replacing a stale range corrupts the composer.
     */
    private fun applySelection(autocompletable: Autocompletable) {
        val editable = editText.editableText ?: return
        val caret = editText.selectionStart.coerceAtLeast(0)
        val selectedOption = viewModel.getSelectedCommandOption()
        val token = if (selectedOption != null && autocompletable !is ApplicationCommandAutocompletable) {
            AutocompleteModelUtils.getCommandOptionAutocompleteToken(
                editable,
                caret..caret,
                selectedOption,
                viewModel.getSelectedCommandOptions(),
            )
        } else {
            AutocompleteModelUtils.getMessageAutocompleteToken(editable, caret..caret)
        } ?: return
        if (!viewModel.selectAutocompleteItem(autocompletable)) return

        val replacement = autocompletable.getInputReplacement() + " "
        val start = token.startIndex
        val end = (token.startIndex + token.token.length).coerceAtMost(editable.length)
        if (start !in 0..end) return

        applyingSelection = true
        try {
            editable.replace(start, end, replacement)
            editText.setSelection((start + replacement.length).coerceAtMost(editable.length))
        } finally {
            applyingSelection = false
        }
        resyncAfterEdit()
        if (selectedOption != null) viewModel.completeSelectedCommandOption()
        if (autocompletable is ApplicationCommandAutocompletable && autocompletable.command.options.size == 1) {
            focusCommandOption(autocompletable.command.options.single())
            return
        }
        resyncAfterEdit()
    }

    private fun focusCommandOption(option: ApplicationCommandOption) {
        val editable = editText.editableText ?: return
        viewModel.selectCommandOption(option)
        val commandOptions = viewModel.getSelectedCommandOptions()
        val existing = AutocompleteModelUtils.findCommandOptionValueRange(editable, option, commandOptions)
        if (existing != null) {
            editText.setSelection(existing.first, existing.last.coerceAtMost(editable.length))
            resyncAfterEdit()
            return
        }

        val prefix = when (option.type) {
            ApplicationCommandType.USER,
            ApplicationCommandType.ROLE,
            ApplicationCommandType.MENTIONABLE,
            -> "@"
            ApplicationCommandType.CHANNEL -> "#"
            else -> ""
        }
        val separator = if (editable.isEmpty() || editable.last().isWhitespace()) "" else " "
        val insertion = "$separator${option.name}:$prefix"
        applyingSelection = true
        try {
            editable.append(insertion)
            editText.setSelection(editable.length)
        } finally {
            applyingSelection = false
        }
        resyncAfterEdit()
    }

    /**
     * Re-derives the mention map after a programmatic edit.
     *
     * The [TextWatcher] is suppressed while the edit is applied — otherwise picking a candidate
     * would immediately re-open the popup on the text just inserted — so the model would otherwise
     * be left holding a map built from the *previous* text and would refuse to rewrite any other
     * mention in the message on send.
     *
     * Feeding the new text back in is safe: the caret now sits after a trailing space, so
     * `getSelectedToken` finds no word under it and the state reduces to `Hidden`.
     */
    private fun resyncAfterEdit() {
        replaceMentionPills()
        val caret = editText.selectionStart.coerceAtLeast(0)
        viewModel.onInputChanged(editText.text ?: "", caret..caret)
    }

    /**
     * Marks up the tokens the composer produced, so a completed mention or emoji reads differently
     * to the same characters typed by hand.
     *
     * A shortcode only counts once it names an emoji that exists — half-typed text such as `:jo`,
     * or a name nothing resolves to, is left plain.
     */
    private fun replaceMentionPills() {
        val editable = editText.editableText ?: return
        val spans = buildMap<IntRange, List<CharacterStyle>> {
            EMOJI_TOKEN.findAll(editable).forEach { match ->
                if (!isKnownEmoji(match.groupValues[1])) return@forEach
                put(
                    match.range,
                    listOf(
                        BackgroundColorSpan(
                            ColorCompat.getThemedColor(editText, R.attr.colorBackgroundModifierAccent),
                        ),
                    ),
                )
            }
            MENTION_TOKEN.findAll(editable).forEach { match ->
                val userId = match.groupValues[1].toLongOrNull() ?: return@forEach
                val username = StoreStream.getUsers().getUsers()[userId]?.username ?: return@forEach
                put(
                    match.range,
                    listOf(
                        ComposerMentionSpan(
                            display = "@$username",
                            textColor = ColorCompat.getThemedColor(editText, R.attr.colorInteractiveActive),
                            backgroundColor = ColorCompat.getThemedColor(editText, R.attr.theme_chat_mention_background),
                            density = editText.resources.displayMetrics.density,
                        ),
                    ),
                )
            }
        }
        applyEditTextAction(InputEditTextAction.ReplacePillSpans(editable.toString(), spans))
    }

    private fun isKnownEmoji(name: String): Boolean {
        // A custom emoji shares its name across guilds, so the composer disambiguates with a ~N suffix.
        val undisambiguated = name.substringBefore('~')
        if (StoreStream.getEmojis().unicodeEmojisNamesMap.containsKey(undisambiguated)) return true
        return StoreStream.getCustomEmojis().getAllGuildEmoji().values.any { guildEmoji ->
            guildEmoji.values.any { it.name == undisambiguated }
        }
    }

    private fun applyEditTextAction(action: InputEditTextAction) {
        val editable = editText.editableText ?: return
        if (editable.toString() != action.assumedInput.toString()) return
        when (action) {
            is InputEditTextAction.RemoveText -> {
                applyingSelection = true
                try {
                    editable.replace(action.range.first, action.range.last + 1, "")
                    editText.setSelection(action.selectionIndex.coerceAtMost(editable.length))
                } finally {
                    applyingSelection = false
                }
                resyncAfterEdit()
            }
            is InputEditTextAction.ReplacePillSpans -> {
                editable.getSpans(0, editable.length, ComposerMentionSpan::class.java).forEach(editable::removeSpan)
                editable.getSpans(0, editable.length, BackgroundColorSpan::class.java).forEach(editable::removeSpan)
                action.spans.forEach { (range, styles) ->
                    styles.forEach { style ->
                        editable.setSpan(style, range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            else -> Unit
        }
    }
}
