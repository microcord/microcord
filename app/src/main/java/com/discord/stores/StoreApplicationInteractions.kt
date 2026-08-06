package com.discord.stores

import android.content.Context
import android.content.SharedPreferences
import androidx.collection.LruCache
import com.discord.api.commands.ApplicationCommandData
import com.discord.api.interaction.Interaction
import com.discord.api.interaction.InteractionModalCreate
import com.discord.api.interaction.InteractionStateUpdate
import com.discord.api.user.User
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.app.AppLog
import com.discord.models.commands.ApplicationCommandLocalSendData
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.NonceGenerator
import com.discord.models.message.Message
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.restapi.PayloadJSON
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.error.RestApiError
import com.discord.utilities.attachments.UploadAttachment
import com.discord.utilities.rest.FileUpload
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import rx.Observable
import rx.subjects.PublishSubject

/**
 * The client half of the interaction lifecycle: slash-command invocations, button/select presses
 * and modal submissions, from the moment the user acts until the application answers.
 *
 * An interaction is not request/response. The POST only says "accepted"; the real outcome arrives
 * later over the gateway as `INTERACTION_CREATE` → `INTERACTION_SUCCESS` / `INTERACTION_FAILURE`,
 * correlated by a client-generated nonce. Everything in this store exists to bridge that gap:
 *
 * - [applicationCommandLocalSendDataSet] — nonce → the command that was sent. **Persisted**, because
 *   a Play/process death mid-interaction must not orphan the optimistic row; [handleConnectionReady]
 *   marks everything still pending as failed on the next connect.
 * - [componentInteractions] — nonce → which component of which message is in flight. Only one
 *   component per message may be pending, which is what stops a double-tap from firing twice.
 * - [interactionComponentSendState] — the spinner/checkmark/error a component row renders.
 * - [sentInteractions] — a 5-entry LRU used by the modal flow to recover the channel/guild a modal
 *   belongs to when the modal is submitted under a *new* nonce.
 * - [interactionData] — lazily fetched `/interaction-data` for the "used /command" header on a
 *   message someone else sent.
 *
 * `INTERACTION_MODAL_CREATE` goes to a [PublishSubject] rather than the observation deck because it
 * is an event, not state: showing the modal twice on re-subscribe would be wrong.
 */
@Suppress("TooManyFunctions")
class StoreApplicationInteractions(
    private val dispatcher: Dispatcher,
    private val storeMessages: StoreMessages,
    private val storeLocalActionComponentState: StoreLocalActionComponentState,
    private val sharedPrefs: SharedPreferences = SharedPreferencesProvider.get(),
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
    private val nonceGenerator: NonceGenerator = NonceGenerator(),
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Must carry the API type adapters. The persisted ledger contains
     * `api.user.User.avatar: NullSerializable<String>?`, a sealed class that a bare [Gson] cannot
     * reconstruct — the restore would throw, [loadCachedApplicationCommandSendDataSet]'s `catch`
     * would clear the cache, and every pending slash-command send would be dropped on every cold
     * start, silently.
     *
     * Deliberately **no** [com.google.gson.FieldNamingPolicy]: the reference sets none, so the
     * persisted keys are the raw Kotlin field names. Adding one would invalidate every ledger
     * already on disk.
     */
    private val gson: Gson = GsonBuilder().registerDiscordApiTypeAdapters().create(),
) : StoreV2() {

    /** Where a component lives, so a nonce can be mapped back to a row. */
    data class ComponentLocation(val messageId: Long, val componentIndex: Int)

    /** What a component row shows while and after an interaction. */
    sealed class InteractionSendState {
        object Loading : InteractionSendState()
        object Success : InteractionSendState()

        /** [errorMessage] is the server's own message when it supplied one. */
        data class Failed(val errorMessage: String? = null) : InteractionSendState()
    }

    enum class SentInteractionState { SENDING, FAILURE, SUCCESS }

    /**
     * Enough context to submit a modal that an interaction opened.
     *
     * [state] is `var` because the LRU holds one instance per nonce and the gateway result mutates
     * it in place — the reference does the same.
     */
    data class SentInteraction(
        val channelId: Long,
        val messageId: Long?,
        val guildId: Long?,
        var state: SentInteractionState = SentInteractionState.SENDING,
    )

    /** Lazily fetched detail of the command a message was produced by. */
    sealed class State {
        object Fetching : State()
        object Failure : State()
        data class Loaded(val commandOptions: ApplicationCommandData) : State()
    }

    var applicationCommandLocalSendDataSet: MutableMap<String, ApplicationCommandLocalSendData> =
        LinkedHashMap()
        private set
    private var applicationCommandLocalSendDataSnapshot: Map<String, ApplicationCommandLocalSendData> =
        emptyMap()

    val componentInteractions: MutableMap<String, ComponentLocation> = LinkedHashMap()

    val interactionComponentSendState: MutableMap<Long, Map<Int, InteractionSendState>> = LinkedHashMap()
    var interactionComponentSendStateSnapshot: Map<Long, Map<Int, InteractionSendState>> = emptyMap()
        private set

    private val interactionData = HashMap<Long, State>()
    private var interactionDataSnapshot: Map<Long, State> = emptyMap()

    internal val sentInteractions = LruCache<String, SentInteraction>(SENT_INTERACTIONS_CACHE_SIZE)
    private var sentInteractionsSnapshot: Map<String, SentInteraction> = emptyMap()

    private val interactionModalCreate = PublishSubject.create<InteractionModalCreate>()

    private var sessionId: String? = null

    fun getSessionId(): String? = sessionId

    // -- Reads ---------------------------------------------------------------------------------

    fun getComponentInteractionData(): Map<Long, Map<Int, InteractionSendState>> =
        interactionComponentSendStateSnapshot

    fun getInteractionData(interactionId: Long): State? = interactionDataSnapshot[interactionId]

    fun observeComponentInteractionState(): Observable<Map<Long, Map<Int, InteractionSendState>>> =
        observationDeck.connectRx(ComponentStateUpdate) { getComponentInteractionData() }
            .distinctUntilChanged()

    fun observeInteractionData(interactionId: Long): Observable<State?> =
        observationDeck.connectRx(this) { getInteractionData(interactionId) }.distinctUntilChanged()

    fun observeModalCreate(): Observable<InteractionModalCreate> = interactionModalCreate

    fun observeSentInteractions(): Observable<Map<String, SentInteraction>> =
        observationDeck.connectRx(SentInteractionsUpdate) { sentInteractionsSnapshot }

    fun getSentInteraction(nonce: String): SentInteraction? = sentInteractionsSnapshot[nonce]

    // -- Lifecycle -----------------------------------------------------------------------------

    @StoreThread
    override fun init(context: Context) {
        super.init(context)
        loadCachedApplicationCommandSendDataSet()
    }

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        sessionId = payload.sessionId
    }

    /**
     * Every command still awaiting an `INTERACTION_CREATE` when the socket comes back is declared
     * failed: the gateway only replays live interactions, so anything older is unrecoverable and
     * would otherwise spin forever.
     */
    @StoreThread
    fun handleConnectionReady(connectionReady: Boolean) {
        if (!connectionReady) return
        applicationCommandLocalSendDataSet.values.toList().forEach { sendData ->
            handleInteractionFailure(
                InteractionStateUpdate(sendData.interactionId, sendData.nonceString),
            )
        }
    }

    /** Leaving a channel clears finished component badges but keeps anything still in flight. */
    @StoreThread
    fun handleChannelSelected() = clearComponentInteractionSendSuccessAndFailures()

    @StoreThread
    fun handlePreLogout() {
        applicationCommandLocalSendDataSet.clear()
        componentInteractions.clear()
        interactionComponentSendState.clear()
        interactionData.clear()
        sentInteractions.evictAll()
        sessionId = null
        markChanged(ComponentStateUpdate, SentInteractionsUpdate)
    }

    // -- Gateway handlers ----------------------------------------------------------------------

    /** `INTERACTION_CREATE`: the server accepted the command and assigned it an interaction id. */
    @StoreThread
    fun handleInteractionCreate(interactionStateUpdate: InteractionStateUpdate) {
        val sendData = applicationCommandLocalSendDataSet[interactionStateUpdate.nonce] ?: return
        storeMessages.handleApplicationCommandInteraction(
            sendData,
            interactionStateUpdate,
            failed = false,
            loading = true,
        )
        handleApplicationCommandRequestStateUpdate(sendData, interactionStateUpdate.id)
    }

    /**
     * `INTERACTION_SUCCESS`: the application answered.
     *
     * The component's send state is *removed* rather than set to success — the message edit that
     * follows re-renders the row from the new component definition, so leaving a checkmark behind
     * would double up.
     */
    @StoreThread
    fun handleInteractionSuccess(interactionStateUpdate: InteractionStateUpdate) {
        sentInteractions[interactionStateUpdate.nonce]?.let { sent ->
            sent.state = SentInteractionState.SUCCESS
            markChanged(SentInteractionsUpdate)
        }
        val location = componentInteractions[interactionStateUpdate.nonce] ?: return
        interactionComponentSendState.remove(location.messageId)
        componentInteractions.remove(interactionStateUpdate.nonce)
        markChanged(ComponentStateUpdate)
    }

    /** `INTERACTION_FAILURE`: the application errored or timed out. */
    @StoreThread
    fun handleInteractionFailure(interactionStateUpdate: InteractionStateUpdate) {
        sentInteractions[interactionStateUpdate.nonce]?.let { sent ->
            sent.state = SentInteractionState.FAILURE
            markChanged(SentInteractionsUpdate)
        }
        val location = componentInteractions[interactionStateUpdate.nonce]
        if (location != null) {
            storeLocalActionComponentState.clearState(location.messageId, location.componentIndex)
            componentInteractions.remove(interactionStateUpdate.nonce)
            addInteractionStateToComponent(
                location.messageId,
                location.componentIndex,
                InteractionSendState.Failed(),
            )
            return
        }
        applicationCommandLocalSendDataSet[interactionStateUpdate.nonce]?.let { sendData ->
            storeMessages.handleApplicationCommandInteraction(
                sendData,
                interactionStateUpdate,
                failed = true,
                loading = false,
            )
            handleApplicationCommandRequestStateUpdate(sendData, interactionStateUpdate.id)
        }
    }

    @StoreThread
    fun handleInteractionModalCreate(interactionModalCreate: InteractionModalCreate) {
        applicationCommandLocalSendDataSet[interactionModalCreate.nonce]?.let(
            storeMessages::handleApplicationCommandModal,
        )
        this.interactionModalCreate.onNext(interactionModalCreate)
    }

    @StoreThread
    fun handleMessageCreate(message: com.discord.api.message.Message) =
        handleMessagesCreateOrLoad(listOf(Message(message)))

    /**
     * A message edit invalidates the components attached to it, so every pending interaction on
     * that message is dropped along with its optimistic select-menu state.
     */
    @StoreThread
    fun handleMessageUpdate(message: com.discord.api.message.Message) {
        val pending = componentInteractions.filterValues { it.messageId == message.id }
        if (pending.isNotEmpty()) {
            storeLocalActionComponentState.clearState(message.id, null)
            pending.keys.forEach(componentInteractions::remove)
            markChanged(ComponentStateUpdate)
        }
        if (interactionComponentSendState.containsKey(message.id)) {
            interactionComponentSendState.remove(message.id)
            markChanged(ComponentStateUpdate)
        }
    }

    /**
     * Resolves optimistic state against messages that just arrived.
     *
     * Two different reconciliations happen here:
     *
     * - a message whose nonce matches a pending **component** interaction is the application's
     *   reply, so the component is marked succeeded;
     * - a message that matches a pending **command** — by nonce, or failing that by the interaction
     *   id it carries — is the real version of the optimistic command row, so the local row is
     *   deleted and the send data forgotten.
     */
    @StoreThread
    fun handleMessagesCreateOrLoad(messagesList: List<Message>) {
        messagesList.forEach { message ->
            val nonce = message.nonce
            val componentLocation = nonce?.let(componentInteractions::get)
            if (componentLocation != null) {
                handleComponentInteractionMessage(componentLocation)
                componentInteractions.remove(nonce)
                return@forEach
            }
            val interactionId = message.interaction?.id
            val sendData = when {
                !nonce.isNullOrBlank() && applicationCommandLocalSendDataSet.containsKey(nonce) ->
                    applicationCommandLocalSendDataSet[nonce]

                interactionId != null ->
                    applicationCommandLocalSendDataSet.values
                        .firstOrNull { it.interactionId == interactionId }

                else -> null
            } ?: return@forEach
            removeApplicationCommandSendData(sendData.nonceString)
            storeMessages.deleteLocalMessage(sendData.channelId, sendData.nonceString)
        }
    }

    @StoreThread
    fun handleLocalMessageDelete(message: Message) {
        message.nonce?.let(::removeApplicationCommandSendData)
    }

    // -- Interaction data ----------------------------------------------------------------------

    /**
     * Fetches the command detail behind a message, unless it is already known.
     *
     * When the message was sent by *this* client the answer is already in
     * [applicationCommandLocalSendDataSnapshot], so the network call is skipped entirely.
     */
    fun fetchInteractionDataIfNonExisting(
        interactionId: Long,
        channelId: Long,
        messageId: Long,
        messageNonce: String?,
    ) {
        val existing = interactionData[interactionId]
        if (existing is State.Fetching || existing is State.Loaded) return

        if (!messageNonce.isNullOrBlank()) {
            applicationCommandLocalSendDataSnapshot[messageNonce]?.let { local ->
                dispatcher.schedule {
                    handleInteractionDataFetchSuccess(
                        interactionId,
                        local.toRestApiApplicationCommandDataParam(),
                    )
                }
                return
            }
        }

        dispatcher.schedule { handleInteractionDataFetchStart(interactionId) }
        val api = restApi()
        if (api == null) {
            dispatcher.schedule { handleInteractionDataFetchFailure(interactionId) }
            return
        }
        api.getInteractionData(channelId, messageId)
            .restSubscribeOn(false)
            .appSubscribe(
                StoreApplicationInteractions::class.java,
                errorHandler = {
                    dispatcher.schedule { handleInteractionDataFetchFailure(interactionId) }
                },
            ) { detail ->
                dispatcher.schedule { handleInteractionDataFetchSuccess(interactionId, detail) }
            }
    }

    @StoreThread
    private fun handleInteractionDataFetchStart(interactionId: Long) {
        interactionData[interactionId] = State.Fetching
        markChanged()
    }

    @StoreThread
    private fun handleInteractionDataFetchSuccess(
        interactionId: Long,
        interactionData: ApplicationCommandData,
    ) {
        this.interactionData[interactionId] = State.Loaded(interactionData)
        markChanged()
    }

    @StoreThread
    private fun handleInteractionDataFetchFailure(interactionId: Long) {
        interactionData[interactionId] = State.Failure
        markChanged()
    }

    // -- Sending: application commands ---------------------------------------------------------

    @Suppress("LongParameterList")
    fun sendApplicationCommand(
        channelId: Long,
        guildId: Long?,
        version: String?,
        data: com.discord.widgets.chat.input.models.ApplicationCommandData,
        attachments: List<UploadAttachment>? = null,
        onSuccess: () -> Unit = {},
        onFail: (RestApiError) -> Unit = {},
    ) = dispatcher.schedule {
        val localSendData = ApplicationCommandLocalSendData(
            nonce = nonceGenerator.nonce(),
            channelId = channelId,
            guildId = guildId,
            commandGuildId = data.applicationCommand.guildId,
            application = data.application,
            sessionId = sessionId,
            applicationCommandName = data.applicationCommand.name,
            applicationCommandId = data.applicationCommand.id,
            applicationCommandsValues = data.values,
            version = version ?: data.applicationCommand.version,
            applicationCommand = data.applicationCommand.apiCommand,
        )
        handleSendApplicationCommandRequest(localSendData, attachments, onSuccess, onFail)
    }

    /** Re-sends a command whose optimistic row failed. */
    fun resendApplicationCommand(message: Message) {
        if (message.type != LOCAL_APPLICATION_COMMAND_MESSAGE_TYPE) return
        val nonce = message.nonce ?: return
        val sendData = applicationCommandLocalSendDataSnapshot[nonce] ?: return
        dispatcher.schedule {
            storeMessages.deleteLocalMessage(message.channelId, nonce)
            handleSendApplicationCommandRequest(sendData)
            removeApplicationCommandSendData(nonce)
        }
    }

    @StoreThread
    private fun handleSendApplicationCommandRequest(
        localSendData: ApplicationCommandLocalSendData,
        attachments: List<UploadAttachment>? = null,
        onSuccess: () -> Unit = {},
        onFail: (RestApiError) -> Unit = {},
    ) {
        upsertApplicationCommandSendData(localSendData)
        val localMessage = buildApplicationCommandLocalMessage(localSendData)
        sentInteractions.put(localSendData.nonceString, SentInteraction(localSendData.channelId, null, localSendData.guildId))
        storeMessages.sendMessage(localMessage, localSendData, attachments).subscribe { result ->
            dispatcher.schedule { handleApplicationCommandResult(result, localSendData, onSuccess, onFail) }
        }
    }

    @StoreThread
    private fun handleApplicationCommandResult(
        result: com.discord.utilities.messagesend.MessageResult,
        localSendData: ApplicationCommandLocalSendData,
        onSuccess: () -> Unit,
        onFail: (RestApiError) -> Unit,
    ) {
        val awaitingInteraction = applicationCommandLocalSendDataSet[localSendData.nonceString]?.interactionId == null
        when (result) {
            is com.discord.utilities.messagesend.MessageResult.Success -> {
                onSuccess()
                if (awaitingInteraction) handleApplicationCommandRequestStateUpdate(localSendData)
            }

            is com.discord.utilities.messagesend.MessageResult.UnknownFailure -> {
                onFail(result.error)
                if (awaitingInteraction) {
                    handleInteractionFailure(InteractionStateUpdate(localSendData.interactionId, localSendData.nonceString))
                }
            }

            else -> if (awaitingInteraction) {
                handleInteractionFailure(InteractionStateUpdate(localSendData.interactionId, localSendData.nonceString))
            }
        }
    }

    private fun buildApplicationCommandLocalMessage(localSendData: ApplicationCommandLocalSendData): Message {
        val application = localSendData.application.bot ?: User(
            id = localSendData.application.id,
            username = localSendData.application.name,
        )
        return Message(
            id = localSendData.nonce,
            channelId = localSendData.channelId,
            author = application,
            content = "",
            timestamp = UtcDateTime(now()),
            nonce = localSendData.nonceString,
            type = LOCAL_APPLICATION_COMMAND_MESSAGE_TYPE_SENDING,
            applicationId = application.id,
            interaction = Interaction(
                id = localSendData.interactionId,
                type = Interaction.TYPE_INTERACTION_APPLICATION_COMMAND,
                name = localSendData.applicationCommandName,
            ),
        )
    }

    @StoreThread
    private fun handleApplicationCommandRequestStateUpdate(
        localSendData: ApplicationCommandLocalSendData,
        interactionId: Long? = null,
    ) = upsertApplicationCommandSendData(localSendData.copy(interactionId = interactionId))

    @StoreThread
    private fun upsertApplicationCommandSendData(localSendData: ApplicationCommandLocalSendData) {
        applicationCommandLocalSendDataSet[localSendData.nonceString] = localSendData
        markChanged()
    }

    @StoreThread
    private fun removeApplicationCommandSendData(nonce: String) {
        if (applicationCommandLocalSendDataSet.remove(nonce) != null) markChanged()
    }

    // -- Sending: components -------------------------------------------------------------------

    /**
     * Presses a button or submits a select menu.
     *
     * Only one component per message may be in flight. A second press on the *same* component is
     * ignored outright; a press on a *different* component of the same message additionally clears
     * that component's optimistic selection, so the menu snaps back rather than showing a choice
     * that was never sent.
     */
    @Suppress("LongParameterList")
    fun sendComponentInteraction(
        applicationId: Long,
        guildId: Long?,
        channelId: Long,
        messageId: Long,
        componentIndex: Int,
        data: RestAPIParams.ComponentInteractionData,
        messageFlags: Long?,
    ) = dispatcher.schedule {
        val alreadyPending = componentInteractions.values.any { it.messageId == messageId }
        if (alreadyPending) {
            val allOtherComponents = componentInteractions.values.all {
                it.messageId == messageId && it.componentIndex != componentIndex
            }
            if (allOtherComponents) {
                storeLocalActionComponentState.clearState(messageId, componentIndex)
            }
            return@schedule
        }

        val nonce = nonceGenerator.nonce().toString()
        componentInteractions[nonce] = ComponentLocation(messageId, componentIndex)
        addInteractionStateToComponent(messageId, componentIndex, InteractionSendState.Loading)
        sentInteractions.put(nonce, SentInteraction(channelId, messageId, guildId))

        val api = restApi()
        if (api == null) {
            componentInteractions.remove(nonce)
            addInteractionStateToComponent(messageId, componentIndex, InteractionSendState.Failed())
            return@schedule
        }
        api.sendComponentInteraction(
            RestAPIParams.ComponentInteraction(
                type = TYPE_COMPONENT_INTERACTION,
                channelId = channelId,
                applicationId = applicationId,
                guildId = guildId,
                messageId = messageId,
                messageFlags = messageFlags,
                data = data,
                sessionId = sessionId,
                nonce = nonce,
            ),
        )
            .restSubscribeOn(false)
            .appSubscribe(
                StoreApplicationInteractions::class.java,
                errorHandler = { error ->
                    dispatcher.schedule {
                        storeLocalActionComponentState.clearState(messageId, componentIndex)
                        componentInteractions.remove(nonce)
                        addInteractionStateToComponent(
                            messageId,
                            componentIndex,
                            InteractionSendState.Failed(error.message),
                        )
                    }
                },
            ) { }
    }

    /**
     * Submits a modal that an earlier interaction opened.
     *
     * The submission travels under a *fresh* nonce, and the original [SentInteraction] is copied
     * onto it — that is how the channel/guild survive from the interaction that opened the modal to
     * the one that submits it. A modal whose opening interaction has fallen out of the LRU cannot
     * be submitted at all, which is the reference behaviour.
     */
    fun sendModalInteraction(
        modal: InteractionModalCreate,
        components: List<RestAPIParams.ModalInteractionDataComponent>,
        uploads: List<FileUpload> = emptyList(),
        onNext: (String) -> Unit,
        onError: () -> Unit,
    ) {
        dispatcher.schedule {
            val sent = sentInteractions[modal.nonce] ?: return@schedule
            val nonce = nonceGenerator.nonce()
            sentInteractions.put(nonce.toString(), sent)
            val api = restApi()
            if (api == null) {
                onError()
                return@schedule
            }
            val body = RestAPIParams.ModalInteraction(
                type = TYPE_MODAL_INTERACTION,
                applicationId = modal.application.id,
                messageId = sent.messageId,
                channelId = sent.channelId,
                guildId = sent.guildId,
                sessionId = sessionId,
                nonce = nonce,
                data = RestAPIParams.ModalInteractionData(
                    id = modal.id,
                    customId = modal.customId,
                    components = components,
                    attachments = uploads.takeIf { it.isNotEmpty() }?.mapIndexed { index, upload ->
                        RestAPIParams.ModalAttachment(index.toLong(), upload.name)
                    },
                ),
            )
            val request = if (uploads.isEmpty()) {
                api.sendModalInteraction(body)
            } else {
                api.sendModalInteraction(PayloadJSON(body), uploads.mapNotNull(FileUpload::part).toTypedArray())
            }
            request
                .restSubscribeOn(false)
                .appSubscribe(
                    StoreApplicationInteractions::class.java,
                    errorHandler = { onError() },
                ) { onNext(nonce.toString()) }
        }
    }

    // -- Component send state ------------------------------------------------------------------

    /**
     * Replaces the whole per-message map rather than merging: only one component of a message can
     * be interacting at a time, so a second entry would be stale by construction.
     */
    @StoreThread
    private fun addInteractionStateToComponent(
        messageId: Long,
        componentIndex: Int,
        sendState: InteractionSendState,
    ) {
        interactionComponentSendState[messageId] = mapOf(componentIndex to sendState)
        markChanged(ComponentStateUpdate)
    }

    @StoreThread
    private fun handleComponentInteractionMessage(componentLocation: ComponentLocation) =
        addInteractionStateToComponent(
            componentLocation.messageId,
            componentLocation.componentIndex,
            InteractionSendState.Success,
        )

    /** Keeps only the still-[InteractionSendState.Loading] entries. */
    private fun clearComponentInteractionSendSuccessAndFailures() = dispatcher.schedule {
        val stillLoading = interactionComponentSendState.mapValues { (_, byIndex) ->
            byIndex.filterValues { it is InteractionSendState.Loading }
        }
        interactionComponentSendState.clear()
        interactionComponentSendState.putAll(stillLoading)
        markChanged(ComponentStateUpdate)
    }

    // -- Persistence ---------------------------------------------------------------------------

    /**
     * Restores the persisted send-data ledger.
     *
     * Any failure — missing, blank, or unparsable JSON — clears both the cache and the in-memory
     * map rather than propagating, because a corrupt ledger would otherwise fail every launch.
     */
    @StoreThread
    private fun loadCachedApplicationCommandSendDataSet() {
        try {
            val cached = sharedPrefs.getString(CACHE_KEY_APPLICATION_COMMAND_SEND_DATA_SET, null)
            applicationCommandLocalSendDataSet = if (cached.isNullOrBlank()) {
                LinkedHashMap()
            } else {
                val type = object :
                    TypeToken<MutableMap<String, ApplicationCommandLocalSendData>>() {}.type
                gson.fromJson<MutableMap<String, ApplicationCommandLocalSendData>>(cached, type)
            }
        } catch (throwable: Exception) {
            clearCache()
            applicationCommandLocalSendDataSet.clear()
            AppLog.e("Error restoring cached command send data", throwable)
        }
        markChanged()
    }

    @StoreThread
    private fun clearCache() {
        sharedPrefs.edit().putString(CACHE_KEY_APPLICATION_COMMAND_SEND_DATA_SET, null).apply()
        applicationCommandLocalSendDataSet.clear()
        markChanged()
    }

    /**
     * The ledger is written back on every dispatch that touched this store, not on a timer: a
     * pending interaction that is not persisted before the process dies can never be resolved.
     */
    @StoreThread
    override fun snapshotData() {
        interactionDataSnapshot = HashMap(interactionData)
        interactionComponentSendStateSnapshot = HashMap(interactionComponentSendState)
        sentInteractionsSnapshot = sentInteractions.snapshot()
        val sendData = HashMap(applicationCommandLocalSendDataSet)
        applicationCommandLocalSendDataSnapshot = sendData
        sharedPrefs.edit()
            .putString(CACHE_KEY_APPLICATION_COMMAND_SEND_DATA_SET, gson.toJson(sendData))
            .apply()
    }

    companion object {
        val ComponentStateUpdate = object : ObservationDeck.UpdateSource {}
        val SentInteractionsUpdate = object : ObservationDeck.UpdateSource {}

        /** `RestAPIParams.ComponentInteraction.type`. */
        const val TYPE_COMPONENT_INTERACTION = 3L

        /** `RestAPIParams.ModalInteraction.type`. */
        const val TYPE_MODAL_INTERACTION = 5L

        private const val CACHE_KEY_APPLICATION_COMMAND_SEND_DATA_SET =
            "CACHE_KEY_APPLICATION_COMMAND_SEND_DATA_SET"

        private const val SENT_INTERACTIONS_CACHE_SIZE = 5

        const val LOCAL_APPLICATION_COMMAND_MESSAGE_TYPE_SENDING = -5

        /**
         * `Message.type` of the optimistic "using /command" row. Negative because it is a purely
         * local type that the API never sends.
         */
        const val LOCAL_APPLICATION_COMMAND_MESSAGE_TYPE = -4
    }
}
