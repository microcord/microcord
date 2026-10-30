package com.discord.widgets.chat.input.autocomplete

import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.widgets.chat.input.WidgetChatInputAutocompleteStickerAdapter
import com.discord.R
import com.discord.api.commands.ApplicationCommandType
import com.discord.app.AppComponent
import com.discord.i18n.setI18nText
import com.discord.panels.PanelsChildGestureRegionObserver
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.ApplicationCommandOption
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.rx.ui
import com.discord.stores.StoreStream
import com.discord.widgets.chat.input.ChatInputApplicationsAdapter
import com.discord.widgets.chat.input.ComposerMentionSpan
import com.discord.widgets.chat.input.applicationcommands.SelectedApplicationCommandAdapter
import com.discord.widgets.chat.input.autocomplete.adapter.ChatInputAutocompleteAdapter
import com.discord.widgets.chat.input.models.ApplicationCommandData
import com.lytefast.flexinput.model.Attachment
import rx.Observable

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
    /** Opens the file picker; an attachment option has no text form to type into. */
    private val onAttachmentOptionClicked: () -> Unit = {},
) {

    private val adapter = ChatInputAutocompleteAdapter()

    /** Horizontal rows currently claiming their swipes from the side panels; see [setRowShown]. */
    private val gestureRows = HashSet<View>()
    private val stickersHeader: TextView? =
        stickersContainer.findViewById(R.id.chat_input_stickers_matching_header)
    private val stickersRecycler: RecyclerView? = stickersContainer.findViewById(R.id.chat_input_stickers_recycler)
    private val stickerAdapter: WidgetChatInputAutocompleteStickerAdapter? =
        stickersContainer.findViewById<RecyclerView>(R.id.chat_input_stickers_recycler)?.let { recycler ->
            recycler.layoutManager = LinearLayoutManager(recycler.context, LinearLayoutManager.HORIZONTAL, false)
            WidgetChatInputAutocompleteStickerAdapter(recycler) { sticker ->
                viewModel.sendSticker(sticker)
                clearAutocompleteToken()
            }
        }
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
     * The row of app icons under the slash-command browser. Tapping one scrolls the list to that
     * app's commands; scrolling the list highlights the app whose commands are in view.
     */
    private val categoriesRecycler: RecyclerView? = autocompleteRecyclerView.rootView.findViewById(
        R.id.chat_input_categories_recycler,
    )
    private val categoriesAdapter = ChatInputApplicationsAdapter().apply {
        onClickApplication = { application ->
            if (categoriesBrowsing) viewModel.selectCommandBrowserApplication(application)
            else scrollToFirstCommandOf(application.id)
            selectApplication(application.id)
        }
    }
    private var browserApplications: List<Application> = emptyList()

    /** The apps the row currently lists, and whether they are the `/` browser's or a query's. */
    private var categoryApplications: List<Application> = emptyList()
    private var categoriesBrowsing = true
    private val browserScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (categoriesRecycler?.visibility != View.VISIBLE) return
            val top = (recyclerView.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: return
            if (top < 0 || top >= adapter.itemCount) return
            // A typed query lists bare commands; the browser groups them under app headers.
            val application = (adapter.getItem(top) as? ApplicationCommandAutocompletable)?.application
                ?: (adapter.getItem(adapter.getHeaderPositionForItem(top) ?: 0) as? ApplicationPlaceholder)?.application
                ?: return
            selectCategory(application.id)
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
        autocompleteRecyclerView.addOnScrollListener(browserScrollListener)
        categoriesRecycler?.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = categoriesAdapter
            itemAnimator = null
        }
    }

    /** Call from `onViewBoundOrOnResume`; the subscription ends with [appComponent]'s pause. */
    fun onViewBoundOrOnResume() {
        viewModel.observeViewState()
            .ui(appComponent)
            .subscribe({ configureUI(it) }, { /* a render failure must not kill the composer */ })
        viewModel.observeSelectedCommandState()
            .ui(appComponent)
            .subscribe({ configureSelectedCommand(it) }, { /* keep the composer usable */ })
        viewModel.observeCommandBrowserState()
            .ui(appComponent)
            .subscribe({ state ->
                browserApplications = state?.applications.orEmpty()
                if (categoriesBrowsing) {
                    categoryApplications = browserApplications
                    categoriesAdapter.setApplicationData(browserApplications)
                }
            }, { /* the list still works without the app row */ })
        viewModel.observeEvents()
            .ui(appComponent)
            .subscribe({ event ->
                if (event is Event.ScrollAutocompletablesToApplication) {
                    adapter.scrollToPosition(event.targetPosition)
                    selectCategory(event.applicationId)
                }
            }, { /* a missed scroll leaves the list where it is */ })
        Observable.merge(
            StoreStream.getUsers().observeAllUsers().map { Unit },
            StoreStream.getChannels().observeNames().map { Unit },
            StoreStream.getGuilds().observeRoles().map { Unit },
        ).ui(appComponent).subscribe(
            { replaceMentionPills() },
            { /* unresolved pills remain as their safe wire tokens */ },
        )
    }

    fun onDestroyView() {
        editText.removeTextChangedListener(textWatcher)
        autocompleteRecyclerView.removeOnScrollListener(browserScrollListener)
        autocompleteRecyclerView.adapter = null
        selectedCommandRecycler?.adapter = null
        setStickersShown(false)
        setRowShown(selectedCommandRoot, selectedCommandRecycler, false)
        categoriesRecycler?.let(PanelsChildGestureRegionObserver.Provider.get()::unregister)
        categoriesRecycler?.adapter = null
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

    private fun configureStickers(viewState: AutocompleteViewState.Autocomplete) {
        val adapter = stickerAdapter
        if (adapter == null || viewState.stickers.isEmpty()) {
            setStickersShown(false)
            return
        }
        setStickersShown(true)
        stickersHeader?.setI18nText(R.string.sticker_matching, "prefix" to viewState.token)
        adapter.setData(viewState.stickers)
    }

    private fun showAutocomplete(viewState: AutocompleteViewState.Autocomplete) {
        adapter.setData(
            renderedItems(viewState),
            hasStickers = viewState.stickers.isNotEmpty(),
            sizeToMax = viewState.isLoading && !viewState.isError,
        )
        autocompleteRecyclerView.visibility = View.VISIBLE
        configureStickers(viewState)
        // `isAutocomplete` is set for every typed token; only the bare `/` browser lacks it.
        val commandToken = viewState.token.startsWith(AutocompleteViewModel.COMMAND_DISCOVER_TOKEN)
        when {
            !commandToken -> configureCategories(null, browsing = true)
            !viewState.isAutocomplete && viewState.token == AutocompleteViewModel.COMMAND_DISCOVER_TOKEN ->
                configureCategories(browserApplications, browsing = true)
            // While a command is being typed the row stays, narrowed to the bots still matching,
            // until only one is left.
            else -> configureCategories(
                viewState.autocompletables.filterIsInstance<ApplicationCommandAutocompletable>()
                    .mapNotNull { it.application }.distinctBy { it.id },
                browsing = false,
            )
        }
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
        setStickersShown(false)
        configureCategories(null, browsing = true)
    }

    private fun hideAutocomplete() {
        autocompleteRecyclerView.visibility = View.GONE
        autocompleteHeader.visibility = View.GONE
        setStickersShown(false)
        configureCategories(null, browsing = true)
    }

    private fun setStickersShown(shown: Boolean) = setRowShown(stickersContainer, stickersRecycler, shown)

    /**
     * Shows or hides [container]. While it shows, a swipe on its horizontal [row] scrolls the row
     * rather than opening a side panel; the region must go with it, or a hidden row would keep
     * swallowing panel swipes over the spot it used to occupy.
     */
    private fun setRowShown(container: View?, row: View?, shown: Boolean) {
        container?.visibility = if (shown) View.VISIBLE else View.GONE
        if (row == null || shown == (row in gestureRows)) return
        val regions = PanelsChildGestureRegionObserver.Provider.get()
        if (shown) {
            gestureRows += row
            regions.register(row)
        } else {
            gestureRows -= row
            regions.unregister(row)
        }
    }

    /**
     * The app row: the `/` browser's apps, or the bots a typed command still matches, shown only
     * while there is more than one to choose between. `null` hides it.
     */
    private fun configureCategories(applications: List<Application>?, browsing: Boolean) {
        val recycler = categoriesRecycler ?: return
        val visible = applications != null && applications.size > 1
        val changed = visible && (applications != categoryApplications || browsing != categoriesBrowsing)
        categoriesBrowsing = browsing
        if (changed) {
            categoryApplications = applications.orEmpty()
            categoriesAdapter.setApplicationData(categoryApplications)
        }
        val wasVisible = recycler.visibility == View.VISIBLE
        if (wasVisible != visible) {
            recycler.visibility = if (visible) View.VISIBLE else View.GONE
            // Swipes on the row scroll it instead of opening the side panels, but only while it shows.
            val gestureRegions = PanelsChildGestureRegionObserver.Provider.get()
            if (visible) gestureRegions.register(recycler) else gestureRegions.unregister(recycler)
        }
        if (visible && (changed || !wasVisible)) {
            categoriesAdapter.selectApplication(if (browsing) 0L else categoryApplications.first().id)
            recycler.scrollToPosition(0)
        }
    }

    /** Brings [applicationId]'s first command in a typed query's results to the top of the list. */
    private fun scrollToFirstCommandOf(applicationId: Long) {
        val position = (0 until adapter.itemCount).firstOrNull {
            (adapter.getItem(it) as? ApplicationCommandAutocompletable)?.application?.id == applicationId
        } ?: return
        adapter.scrollToPosition(position)
    }

    private fun selectCategory(applicationId: Long) {
        categoriesAdapter.selectApplication(applicationId)
        val position = categoriesAdapter.getPositionOfApplication(applicationId)
        if (position >= 0) categoriesRecycler?.smoothScrollToPosition(position)
    }

    fun getApplicationCommandData(attachments: List<Attachment<*>>): ApplicationCommandData? =
        viewModel.buildSelectedCommandData(editText.text ?: "", attachments)

    companion object {
        private const val REMOTE_LOADING_ITEMS = 4
        private val MENTION_TOKEN = Regex("<(@&|@!?|#)(\\d+)>")

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

        internal fun mentionDisplay(
            token: String,
            userName: (Long) -> String?,
            channelName: (Long) -> String?,
            roleName: (Long) -> String?,
        ): String? {
            val match = MENTION_TOKEN.matchEntire(token) ?: return null
            val id = match.groupValues[2].toLongOrNull() ?: return null
            return when (match.groupValues[1]) {
                "#" -> channelName(id)?.let { "#$it" }
                "@&" -> roleName(id)?.let { "@$it" }
                else -> userName(id)?.let { "@$it" }
            }
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
                setRowShown(selectedCommandRoot, selectedCommandRecycler, false)
            }
            is SelectedCommandViewState.SelectedCommand -> {
                setRowShown(selectedCommandRoot, selectedCommandRecycler, true)
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
    /** Drops the `:query` the sticker row answered, since a sticker is sent rather than inserted. */
    private fun clearAutocompleteToken() {
        val editable = editText.editableText ?: return
        val caret = editText.selectionStart.coerceAtLeast(0)
        val token = AutocompleteModelUtils.getMessageAutocompleteToken(editable, caret..caret) ?: return
        val start = token.startIndex
        val end = (token.startIndex + token.token.length).coerceAtMost(editable.length)
        if (start !in 0..end) return
        applyingSelection = true
        try {
            editable.replace(start, end, "")
            editText.setSelection(start.coerceAtMost(editable.length))
        } finally {
            applyingSelection = false
        }
        resyncAfterEdit()
    }

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
        if (option.type == ApplicationCommandType.ATTACHMENT) {
            onAttachmentOptionClicked()
            return
        }
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
                val display = mentionDisplay(
                    match.value,
                    userName = { StoreStream.getUsers().getUsers()[it]?.username },
                    channelName = { StoreStream.getChannels().getChannel(it)?.name },
                    roleName = { roleId ->
                        StoreStream.getGuilds().getRoles().values.asSequence()
                            .mapNotNull { it[roleId]?.name }
                            .firstOrNull()
                    },
                ) ?: return@forEach
                put(
                    match.range,
                    listOf(
                        ComposerMentionSpan(
                            display = display,
                            textColor = ColorCompat.getThemedColor(editText, R.attr.colorInteractiveActive),
                            backgroundColor = ColorCompat.getThemedColor(editText, R.attr.theme_chat_mention_background),
                            density = editText.resources.displayMetrics.density,
                        ),
                    ),
                )
            }
            // A deliberate Manticord addition — 126.21 leaves typed URLs unstyled — so an
            // appended GIF link reads as a link. `Patterns.WEB_URL` is the platform matcher.
            val linkColor = ColorCompat.getThemedColor(editText, R.attr.colorTextLink)
            val urlMatcher = Patterns.WEB_URL.matcher(editable)
            while (urlMatcher.find()) {
                put(urlMatcher.start() until urlMatcher.end(), listOf(ForegroundColorSpan(linkColor)))
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
                editable.getSpans(0, editable.length, ForegroundColorSpan::class.java).forEach(editable::removeSpan)
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
