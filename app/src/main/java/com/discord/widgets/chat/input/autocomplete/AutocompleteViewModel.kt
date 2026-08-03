package com.discord.widgets.chat.input.autocomplete

import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import com.discord.app.AppViewModel
import com.discord.api.commands.ApplicationCommandType
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.ApplicationCommandOption
import com.discord.models.commands.ApplicationSubCommand
import com.discord.stores.CommandAutocompleteState
import com.discord.stores.StoreStream
import com.discord.utilities.rx.ui
import com.discord.widgets.chat.input.AutocompleteStickerItem
import com.discord.widgets.chat.input.WidgetChatInputDiscoveryCommandsModel
import com.discord.widgets.chat.input.autocomplete.commands.buildRemoteCommandOptionRequest
import com.discord.widgets.chat.input.autocomplete.commands.AutocompleteCommandUtils
import com.discord.widgets.chat.input.models.ApplicationCommandData
import com.discord.widgets.chat.input.models.ApplicationCommandValue
import com.discord.widgets.chat.input.models.AttachmentOptionValue
import com.discord.widgets.chat.input.models.AutocompleteApplicationCommands
import com.discord.widgets.chat.input.models.ChatInputMentionsMap
import com.discord.widgets.chat.input.models.MentionToken
import java.util.concurrent.TimeUnit
import com.lytefast.flexinput.model.Attachment
import rx.Observable
import rx.Scheduler
import rx.Subscription
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject

/**
 * Owns the autocomplete popup's state for one composer.
 *
 * The model has exactly two inputs — what is typed ([onInputChanged]) and what the channel offers
 * ([observeAutocompletables]) — and one output, [AutocompleteViewState]. Keeping the reduction in
 * one pure function ([reduce]) is what makes it testable and is why it lives here rather than in
 * the fragment.
 *
 * Candidate sets are held as state rather than re-derived per keystroke: on a large guild the set
 * costs a permission computation per member, and it changes on presence updates, not on typing.
 */
class AutocompleteViewModel(
    channelId: Long? = null,
    private val autocompletablesSource: (Long) -> Observable<Map<LeadingIdentifier, Set<Autocompletable>>> =
        ChatInputAutocompletables::observeChannelAutocompletables,
    /**
     * Sticker suggestions for the current token. The reference sources these from `StoreStickers`
     * via `StickerUtils.findStickerMatches`; neither exists in this project, so the default offers
     * none and the sticker row stays hidden.
     */
    private val stickerSource: (MentionToken?) -> List<AutocompleteStickerItem> = { emptyList() },
    private val commandQuery: (channelId: Long, query: String) -> Unit = { id, query ->
        val channel = StoreStream.getChannels().getChannel(id)
        StoreStream.getApplicationCommands().requestApplicationCommandsQuery(channel?.guildId, query)
    },
    private val commandStateSource: (Long) -> Observable<CommandState> = ::observeCommandState,
    private val remoteOptionRequest: (channelId: Long, guildId: Long?, data: ApplicationCommandData) -> Unit =
        { id, guildId, data ->
            StoreStream.getApplicationCommands().requestApplicationCommandAutocompleteData(guildId, id, data)
        },
    private val commandIndexRequest: (Long) -> Unit = { id ->
        StoreStream.getChannels().getChannel(id)?.let { channel ->
            StoreStream.getApplicationCommands().requestApplicationCommandIndex(
                channelId = channel.id,
                guildId = channel.guildId.takeUnless { it == 0L },
                isBotDm = channel.recipients.orEmpty().any { it.bot == true },
            )
        }
    },
    private val clearRemoteResults: () -> Unit = {
        StoreStream.getApplicationCommands().clearAutocompleteResults()
    },
    private val channelGuildId: (Long) -> Long? = { id ->
        StoreStream.getChannels().getChannel(id)?.guildId
    },
    private val remoteAutocompleteDelayMs: Long = REMOTE_AUTOCOMPLETE_DELAY_MS,
    private val remoteRequestScheduler: Scheduler = Schedulers.computation(),
) : AppViewModel<AutocompleteViewState>(AutocompleteViewState.Hidden) {

    private var autocompletables: Map<LeadingIdentifier, Set<Autocompletable>> = emptyMap()
    private var currentToken: MentionToken? = null
    private var subscription: Subscription? = null
    private var commandSubscription: Subscription? = null
    private var remoteRequestSubscription: Subscription? = null
    private var selectedCommand: ApplicationCommandAutocompletable? = null
    private var selectedCommandOption: ApplicationCommandOption? = null
    private var lastCommandQuery: String? = null
    private var commandState: CommandState = CommandState()
    private var pendingRemoteQuery: Pair<String, String>? = null
    private var sentRemoteQuery: Pair<String, String>? = null
    private var lastJumpedSequenceId = -1
    private val remoteRequests = PublishSubject.create<RemoteOptionRequest>()
    private val events = PublishSubject.create<Event>()
    private val commandBrowserState = BehaviorSubject.create(null as CommandBrowserState?)
    private val selectedCommandState = BehaviorSubject.create<SelectedCommandViewState>(
        SelectedCommandViewState.Hidden,
    )

    /**
     * Where every recognised mention currently sits in the composer.
     *
     * Recomputed from scratch on each edit rather than shifted, because
     * [AutocompleteModelUtils.mapInputToMentions] re-scans the text anyway and only consults the
     * previous map to (a) keep a picked candidate alive after its source left the store and
     * (b) preserve the exact candidate the user chose for an unchanged range.
     */
    private var inputMentionsMap: ChatInputMentionsMap = ChatInputMentionsMap("", emptyMap())

    private var currentChannelId: Long? = null

    init {
        remoteRequestSubscription = remoteRequests
            .debounce(remoteAutocompleteDelayMs, TimeUnit.MILLISECONDS, remoteRequestScheduler)
            .subscribe({ request ->
                sentRemoteQuery = request.option.name to request.query
                events.onNext(Event.RequestAutocompleteData(request.option))
                remoteOptionRequest(request.channelId, request.guildId, request.data)
            }, { /* a failed request is reflected by the store */ })
        setChannelId(channelId)
    }

    /**
     * Points the model at a different channel.
     *
     * The reference scopes one `AutocompleteViewModel` to the activity and lets the channel arrive
     * through `StoreChannelsSelected`; here the composer owns the model and re-targets it, which
     * keeps the popup alive across a channel switch instead of tearing the whole controller down.
     * Re-targeting clears the candidate set immediately so the popup can never offer a member of
     * the channel the user just left.
     */
    @MainThread
    fun setChannelId(channelId: Long?) {
        if (channelId == currentChannelId) return
        currentChannelId = channelId
        subscription?.unsubscribe()
        commandSubscription?.unsubscribe()
        subscription = null
        commandSubscription = null
        autocompletables = emptyMap()
        currentToken = null
        selectedCommand = null
        selectedCommandOption = null
        lastCommandQuery = null
        commandState = CommandState()
        pendingRemoteQuery = null
        sentRemoteQuery = null
        lastJumpedSequenceId = -1
        inputMentionsMap = ChatInputMentionsMap("", emptyMap())
        updateViewState(AutocompleteViewState.Hidden)
        selectedCommandState.onNext(SelectedCommandViewState.Hidden)
        commandBrowserState.onNext(null)
        if (channelId != null) {
            commandIndexRequest(channelId)
            subscription = autocompletablesSource(channelId)
                .ui(this)
                .subscribe({ onAutocompletablesChanged(it) }, { /* keep the last good set */ })
            commandSubscription = commandStateSource(channelId)
                .ui(this)
                .subscribe(::onCommandStateChanged, { /* keep the last good command state */ })
        }
    }

    @MainThread
    internal fun onCommandStateChanged(state: CommandState) {
        val pending = pendingRemoteQuery
        if (pending != null && pending == sentRemoteQuery) {
            val result = state.remoteResults[pending.first]?.get(pending.second)
            if (result is CommandAutocompleteState.Choices || result is CommandAutocompleteState.Failed) {
                pendingRemoteQuery = null
                sentRemoteQuery = null
            }
        }
        commandState = state
        commandBrowserState.onNext(state.browserState())
        publish()
    }

    /** The candidate set, exposed so the composer can resolve finished text into mention pills. */
    fun getAutocompletables(): Map<LeadingIdentifier, Set<Autocompletable>> = autocompletables

    @MainThread
    fun onAutocompletablesChanged(next: Map<LeadingIdentifier, Set<Autocompletable>>) {
        autocompletables = next
        publish()
    }

    /**
     * Called on every composer edit. [selection] is the caret, as a range so a selection (rather
     * than a caret) can be distinguished later; only [IntRange.first] is consulted today, matching
     * the reference.
     */
    @MainThread
    fun onInputChanged(input: CharSequence, selection: IntRange) {
        val text = input.toString()
        selectedCommand?.takeUnless { text.startsWith("/${it.command.name}") }?.let {
            clearSelectedCommand()
        }
        currentToken = selectedCommandOption?.let { option ->
            AutocompleteModelUtils.getCommandOptionAutocompleteToken(
                input,
                selection,
                option,
                selectedCommand?.command?.options.orEmpty(),
            )
        } ?: if (selectedCommand == null) {
            AutocompleteModelUtils.getMessageAutocompleteToken(input, selection)
        } else {
            null
        }
        if (selectedCommand == null && currentToken?.leadingIdentifier == LeadingIdentifier.APP_COMMAND) {
            val query = currentToken?.formattedToken?.trim().orEmpty()
            val channelId = currentChannelId
            if (query.isNotEmpty() && channelId != null && query != lastCommandQuery) {
                lastCommandQuery = query
                commandQuery(channelId, query)
            }
        }
        inputMentionsMap = AutocompleteModelUtils.mapInputToMentions(
            input = text,
            autocompletables = autocompletables,
            currentInputMentionMap = if (inputMentionsMap.input == text) inputMentionsMap.mentions else emptyMap(),
            isCommand = currentToken?.let { it.isAtStart && it.leadingIdentifier == LeadingIdentifier.APP_COMMAND } == true,
        )
        requestRemoteOptionIfNeeded(input)
        publish()
    }

    fun observeSelectedCommandState(): Observable<SelectedCommandViewState> = selectedCommandState

    fun observeEvents(): Observable<Event> = events

    fun observeCommandBrowserState(): Observable<CommandBrowserState?> = commandBrowserState

    @MainThread
    fun selectAutocompleteItem(item: Autocompletable): Boolean {
        if (item !is ApplicationCommandAutocompletable) return true
        val application = item.application ?: return false
        if (!item.hasPermissionToUse) return false
        selectedCommand = item
        selectedCommandOption = null
        currentToken = null
        selectedCommandState.onNext(
            SelectedCommandViewState.SelectedCommand(item.command, null, application),
        )
        updateViewState(AutocompleteViewState.Hidden)
        return true
    }

    @MainThread
    fun selectCommandOption(option: ApplicationCommandOption) {
        val command = selectedCommand ?: return
        selectedCommandOption = option
        pendingRemoteQuery = null
        sentRemoteQuery = null
        clearRemoteResults()
        selectedCommandState.onNext(
            SelectedCommandViewState.SelectedCommand(
                command.command,
                option,
                command.application ?: return,
            ),
        )
    }

    @MainThread
    fun completeSelectedCommandOption() {
        selectedCommandOption = null
        pendingRemoteQuery = null
        sentRemoteQuery = null
        val command = selectedCommand ?: return
        selectedCommandState.onNext(
            SelectedCommandViewState.SelectedCommand(
                command.command,
                null,
                command.application ?: return,
            ),
        )
        currentToken = null
        updateViewState(AutocompleteViewState.Hidden)
    }

    fun getSelectedCommandOption(): ApplicationCommandOption? = selectedCommandOption

    fun getSelectedCommandOptions(): List<ApplicationCommandOption> =
        selectedCommand?.command?.options.orEmpty()

    fun buildSelectedCommandData(
        input: CharSequence,
        attachments: List<Attachment<*>>,
    ): ApplicationCommandData? {
        val selected = selectedCommand ?: return null
        val command = selected.command
        val attachmentMap = command.options
            .filter { it.type == ApplicationCommandType.ATTACHMENT }
            .zip(attachments)
            .toMap()
        val parsed = AutocompleteCommandUtils.parseCommandOptions(
            input,
            command,
            inputMentionsMap,
            attachmentMap,
        ).filterNot { (option, parsedValue) ->
            !option.required && (parsedValue.value as? String)?.isBlank() == true
        }
        val validity = AutocompleteCommandUtils.getInputValidity(
            command,
            parsed,
            attachmentMap,
            inputMentionsMap,
        )
        val leafValues = command.options.mapNotNull { option ->
            val parsedValue = parsed[option]
                ?: attachmentMap[option]?.uri?.toString()?.let(::AttachmentOptionValue)
                ?: return@mapNotNull null
            ApplicationCommandValue(
                name = option.untranslatedName,
                value = parsedValue.value,
                type = option.type.type,
            )
        }
        val root = (command as? ApplicationSubCommand)?.rootCommand ?: command
        val values = if (command is ApplicationSubCommand) {
            val group = command.parentGroupName?.let { groupName ->
                root.options.firstOrNull { it.name == groupName }
            }
            val subcommand = (group?.options ?: root.options).firstOrNull { it.name == command.subCommandName }
            val subcommandValue = ApplicationCommandValue(
                name = subcommand?.untranslatedName ?: command.subCommandName,
                type = ApplicationCommandType.SUBCOMMAND.type,
                options = leafValues,
            )
            if (group == null) listOf(subcommandValue) else listOf(
                ApplicationCommandValue(
                    name = group.untranslatedName,
                    type = ApplicationCommandType.SUBCOMMAND_GROUP.type,
                    options = listOf(subcommandValue),
                ),
            )
        } else {
            leafValues
        }
        val valid = command.options.filter(ApplicationCommandOption::required).all { validity[it] == true } &&
            validity.values.all { it } && attachments.size == attachmentMap.size
        return ApplicationCommandData(selected.application ?: return null, root, values, valid)
    }

    fun getCommandBrowserCommandPositions(
        discoveryCommands: WidgetChatInputDiscoveryCommandsModel,
    ): Map<Long, Int> {
        var offset = 0
        return buildMap {
            discoveryCommands.commandsByApplication.forEach { (application, commands) ->
                put(
                    application.id,
                    offset + commands.indexOfFirst { it !is ApplicationCommandLoadingPlaceholder }.coerceAtLeast(0),
                )
                offset += commands.size
            }
        }
    }

    fun selectCommandBrowserApplication(application: Application) {
        val browser = commandState.browser ?: return
        val position = getCommandBrowserCommandPositions(browser)[application.id]
        val store = StoreStream.getApplicationCommands()
        if (position != null && store.hasFetchedApplicationCommands(application.id)) {
            events.onNext(Event.ScrollAutocompletablesToApplication(application.id, position))
        } else {
            store.requestDiscoverCommands(application.id)
        }
    }

    fun onCommandsBrowserScroll(firstVisible: Int, lastVisible: Int, itemCount: Int) {
        val browser = commandState.browser ?: return
        when {
            browser.hasMoreBefore && firstVisible <= 3 -> StoreStream.getApplicationCommands().requestLoadMoreUp()
            browser.hasMoreAfter && lastVisible >= itemCount - 9 ->
                StoreStream.getApplicationCommands().requestLoadMoreDown()
        }
    }

    @MainThread
    private fun clearSelectedCommand() {
        selectedCommand = null
        selectedCommandOption = null
        pendingRemoteQuery = null
        selectedCommandState.onNext(SelectedCommandViewState.Hidden)
    }

    private fun requestRemoteOptionIfNeeded(input: CharSequence) {
        val selected = selectedCommand ?: return
        val option = selectedCommandOption?.takeIf(ApplicationCommandOption::autocomplete) ?: run {
            pendingRemoteQuery = null
            return
        }
        val channelId = currentChannelId ?: return
        val token = currentToken ?: return
        val query = token.formattedToken
        val key = option.untranslatedName to query
        if (key == pendingRemoteQuery) return
        pendingRemoteQuery = key
        val application = selected.application ?: return
        remoteRequests.onNext(
            RemoteOptionRequest(
                option,
                query,
                channelId,
                channelGuildId(channelId),
                buildRemoteCommandOptionRequest(
                    input,
                    application,
                    selected.command,
                    option,
                    inputMentionsMap.mentions.values,
                ),
            ),
        )
    }

    /**
     * The composer text as the API should receive it: every recognised mention swapped for its
     * server form.
     *
     * Falls back to returning [input] unchanged when the map was built from different text, which
     * happens if the user types while the send is in flight. Sending the literal `@name` is a far
     * smaller failure than splicing a mention into the wrong offset.
     *
     * Reference: `AutocompleteViewModel.replaceAutocompletableDataWithServerValues`, which returns
     * a `MessageContent` carrying the mentioned users alongside the text. `MessageContent` is not
     * reconstructed in this project — the send pipeline takes a plain string — so only the text
     * half is returned.
     */
    fun replaceAutocompletableDataWithServerValues(input: String): String =
        if (inputMentionsMap.input != input) input
        else input.replaceAutocompleteDataWithServerValues(inputMentionsMap.mentions)

    /** Closes the popup, e.g. after a candidate is picked or the composer loses focus. */
    @MainThread
    fun onAutocompleteDismissed() {
        currentToken = null
        updateViewState(AutocompleteViewState.Hidden)
    }

    private fun publish() {
        if (selectedCommand == null && currentToken?.token == COMMAND_DISCOVER_TOKEN) {
            val browser = commandState.browser
            if (browser != null) {
                maybeEmitBrowserJump(browser)
                updateViewState(
                    AutocompleteViewState.Autocomplete(
                        isAutocomplete = false,
                        autocompletables = browser.getFlattenCommandsModel(),
                        stickers = emptyList(),
                        token = COMMAND_DISCOVER_TOKEN,
                    ),
                )
            } else {
                updateViewState(AutocompleteViewState.LoadingItems(COMMAND_LOADING_ITEMS))
            }
            return
        }
        val remoteState = selectedCommandOption
            ?.takeIf(ApplicationCommandOption::autocomplete)
            ?.let { option ->
                val query = currentToken?.formattedToken.orEmpty()
                commandState.remoteResults[option.untranslatedName]?.get(query)
            }
        val visibleRemoteState = remoteState.takeUnless {
            pendingRemoteQuery ==
                (selectedCommandOption?.untranslatedName to currentToken?.formattedToken.orEmpty())
        }
        val optionCandidates = selectedCommandOption?.let {
            AutocompleteModelUtils.autocompletablesForCommandOption(it, autocompletables)
        }
        val staticChoices = optionCandidates?.values.orEmpty().flatten()
            .filterIsInstance<ApplicationCommandChoiceAutocompletable>()
        reduceRemoteOption(currentToken, selectedCommandOption, visibleRemoteState, staticChoices)?.let {
            updateViewState(it)
            return
        }
        updateViewState(
            reduce(
                currentToken,
                optionCandidates ?: autocompletables,
                stickerSource,
                allowEmptyQuery = selectedCommandOption != null,
            ),
        )
    }

    private fun maybeEmitBrowserJump(browser: WidgetChatInputDiscoveryCommandsModel) {
        val applicationId = browser.jumpedApplicationId ?: return
        if (browser.jumpedSequenceId == lastJumpedSequenceId) return
        lastJumpedSequenceId = browser.jumpedSequenceId
        events.onNext(
            Event.ScrollAutocompletablesToApplication(
                applicationId,
                getCommandBrowserCommandPositions(browser)[applicationId] ?: 3,
            ),
        )
    }

    override fun onCleared() {
        subscription?.unsubscribe()
        commandSubscription?.unsubscribe()
        remoteRequestSubscription?.unsubscribe()
        subscription = null
        commandSubscription = null
        remoteRequestSubscription = null
        super.onCleared()
    }

    companion object {
        private const val COMMAND_DISCOVER_TOKEN = "/"
        private const val COMMAND_LOADING_ITEMS = 3
        private const val REMOTE_AUTOCOMPLETE_DELAY_MS = 500L

        private fun observeCommandState(channelId: Long): Observable<CommandState> {
            val channel = StoreStream.getChannels().getChannel(channelId)
                ?: return Observable.just(CommandState())
            val commands = StoreStream.getApplicationCommands()
            return Observable.combineLatest(
                StoreStream.getUsers().observeMeId(),
                StoreStream.getGuilds().observeComputed(channel.guildId),
                commands.observeGuildApplications(channelId),
                commands.observeQueryCommands(channelId),
                commands.observeDiscoverCommands(channelId),
                commands.observeFrecencyCommands(channel.guildId),
                commands.observeAutocompleteResults(),
            ) { myId, members, applications, queryCommands, discovery, frequent, remoteResults ->
                CommandState(
                    AutocompleteApplicationCommands(
                        applications,
                        queryCommands,
                        WidgetChatInputDiscoveryCommandsModel.parseModelDiscoveryCommands(
                            myId,
                            members[myId]?.getRoles().orEmpty(),
                            applications,
                            discovery,
                            includeHeaders = true,
                            placeholdersCount = 3,
                            frecencyCommands = frequent,
                        ),
                    ),
                    frequent,
                    remoteResults,
                )
            }
        }

        @VisibleForTesting
        fun reduceRemoteOption(
            token: MentionToken?,
            option: ApplicationCommandOption?,
            state: CommandAutocompleteState?,
            staticChoices: List<ApplicationCommandChoiceAutocompletable> = emptyList(),
        ): AutocompleteViewState? {
            if (option?.autocomplete != true) return null
            token ?: return AutocompleteViewState.Hidden
            if (state !is CommandAutocompleteState.Choices) {
                if (staticChoices.isNotEmpty()) {
                    return AutocompleteViewState.Autocomplete(
                        isLoading = false,
                        isError = false,
                        isAutocomplete = true,
                        autocompletables = staticChoices,
                        stickers = emptyList(),
                        token = token.token,
                    )
                }
                return AutocompleteViewState.Autocomplete(
                    isLoading = true,
                    isError = state is CommandAutocompleteState.Failed,
                    isAutocomplete = true,
                    autocompletables = emptyList(),
                    stickers = emptyList(),
                    token = token.token,
                )
            }
            val candidates = staticChoices.toCollection(LinkedHashSet<Autocompletable>())
            state.choices.mapTo(candidates) { choice ->
                ApplicationCommandChoiceAutocompletable(
                    com.discord.api.commands.CommandChoice(
                        choice.name,
                        choice.value,
                        nameLocalized = choice.nameLocalized,
                    ),
                    option.untranslatedName,
                )
            }
            return AutocompleteViewState.Autocomplete(
                isLoading = false,
                isError = false,
                isAutocomplete = true,
                autocompletables = candidates.toList(),
                stickers = emptyList(),
                token = token.token,
            )
        }

        /**
         * The whole of the model's logic.
         *
         * `Hidden` is produced in two distinct situations that must not be conflated:
         *
         * * there is no token under the caret at all, and
         * * there is a token but nothing matches it *and* there are no sticker suggestions.
         *
         * The second is deliberate — showing an empty popup would steal a row of screen height and
         * swallow the next Enter keypress.
         */
        @VisibleForTesting
        fun reduce(
            token: MentionToken?,
            autocompletables: Map<LeadingIdentifier, Set<Autocompletable>>,
            stickerSource: (MentionToken?) -> List<AutocompleteStickerItem> = { emptyList() },
            allowEmptyQuery: Boolean = false,
        ): AutocompleteViewState {
            if (token == null) return AutocompleteViewState.Hidden

            val matches = if (allowEmptyQuery && token.formattedToken.isBlank()) {
                autocompletables[token.leadingIdentifier].orEmpty().toList()
            } else {
                AutocompleteModelUtils.filterMentionsFromToken(
                    token = token,
                    mentions = autocompletables.mapValues { (_, value) -> value.toList() },
                )
            }
            val stickers = stickerSource(token)
            if (matches.isEmpty() && stickers.isEmpty()) return AutocompleteViewState.Hidden

            return AutocompleteViewState.Autocomplete(
                isLoading = false,
                isError = false,
                isAutocomplete = true,
                autocompletables = matches,
                stickers = stickers,
                token = token.token,
            )
        }
    }

    data class CommandState(
        val applicationCommands: AutocompleteApplicationCommands = AutocompleteApplicationCommands(),
        val frequentCommands: List<ApplicationCommand> = emptyList(),
        val remoteResults: Map<String, Map<String, CommandAutocompleteState>> = emptyMap(),
    ) {
        val browser: WidgetChatInputDiscoveryCommandsModel? get() = applicationCommands.discoveryCommands

        fun browserState(): CommandBrowserState? = browser?.let {
            CommandBrowserState(
                frequentCommands,
                applicationCommands.applications.filter { application ->
                    application.commandCount > 0 ||
                        (application.id == -2L && frequentCommands.isNotEmpty())
                },
                it,
            )
        }
    }

    private data class RemoteOptionRequest(
        val option: ApplicationCommandOption,
        val query: String,
        val channelId: Long,
        val guildId: Long?,
        val data: ApplicationCommandData,
    )
}
