package com.discord.widgets.mobile_reports

import android.content.Context
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.directory.DirectoryEntryGuild
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.report.MenuAPIResponse
import com.discord.api.report.NodeElementResult
import com.discord.api.report.NodeResult
import com.discord.api.report.ReportNode
import com.discord.api.report.ReportNodeBottomButton
import com.discord.api.report.ReportNodeChild
import com.discord.api.report.ReportNodeElementData
import com.discord.api.report.ReportSubmissionBody
import com.discord.api.stageinstance.StageInstance
import com.discord.app.AppLog
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.models.user.User
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreDirectories
import com.discord.stores.StoreStream
import com.discord.stores.utilities.RestCallState
import com.discord.stores.utilities.Success
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.message.MessageUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rest.contextPropertiesOf
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessagePreprocessor
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.time.Clock
import com.discord.widgets.directories.DirectoryStores
import com.facebook.drawee.span.DraweeSpanStringBuilder
import java.lang.ref.WeakReference
import java.util.HashSet
import rx.Observable

class MobileReportsViewModel @JvmOverloads constructor(
    private val context: WeakReference<Context>,
    private val args: MobileReportArgs,
    private val restAPI: RestAPIInterface = RestAPI.api,
    private val clock: Clock = Clock(System::currentTimeMillis),
    storeStateObservable: Observable<StoreState> = getStoreState(args, restAPI),
) : AppViewModel<MobileReportsViewModel.ViewState>() {

    data class BlockUserElement(val isBlocked: Boolean, val user: User)
    data class ChannelPreview(val guild: Guild, val stageInstance: StageInstance)
    data class DirectoryServerPreview(val hub: Guild, val directoryEntry: DirectoryEntryGuild)
    data class GuildScheduledEventPreview(val guild: Guild, val event: GuildScheduledEvent)
    data class MessagePreview(
        val text: DraweeSpanStringBuilder,
        val authorName: String,
        val authorNameColor: Int,
        val author: User,
        val hasEmbeds: Boolean = false,
    )

    data class CheckboxElement(
        val name: String,
        val data: List<ReportNodeElementData>,
        val selections: HashSet<ReportNodeElementData>,
    )

    sealed class NodeNavigationType(open val node: ReportNode) {
        data class Initial(val initialNode: ReportNode) : NodeNavigationType(initialNode)
        data class Next(val nextNode: ReportNode) : NodeNavigationType(nextNode)
        data class Back(val prevNode: ReportNode) : NodeNavigationType(prevNode)
    }

    data class NodeState(
        val node: ReportNode,
        val checkboxElement: CheckboxElement?,
        val messagePreviewElement: MessagePreview?,
        val channelPreviewElement: ChannelPreview?,
        val directoryServerPreviewElement: DirectoryServerPreview?,
        val eventPreviewElement: GuildScheduledEventPreview?,
        val blockUserElement: BlockUserElement?,
        val breadcrumbsElement: List<NodeResult>?,
        val successElement: Boolean,
        val submitState: SubmitState?,
        val bottomButton: ReportNodeBottomButton?,
    )

    data class StoreState(
        val message: Message?,
        val channel: Channel?,
        val guild: Guild?,
        val directoryEntry: DirectoryEntryGuild?,
        val stageInstance: StageInstance?,
        val blockedUsers: Map<Long, Int>,
        val event: GuildScheduledEvent?,
    )

    sealed class SubmitState {
        object Error : SubmitState()
        object Loading : SubmitState()
        object None : SubmitState()
    }

    sealed class ViewState {
        object Invalid : ViewState()

        data class Menu(
            val menu: MenuAPIResponse,
            val messagePreview: MessagePreview?,
            val channelPreview: ChannelPreview?,
            val directoryServerPreview: DirectoryServerPreview?,
            val eventPreview: GuildScheduledEventPreview?,
            val nodeNavigationType: NodeNavigationType,
            val submitState: SubmitState = SubmitState.None,
            val history: List<NodeResult>,
            val blockUserElement: BlockUserElement?,
        ) : ViewState() {
            fun genNodeState(): NodeState {
                val node = nodeNavigationType.node
                fun has(type: String) = node.elements.any { it.type == type }
                val checkboxElement = node.elements.firstOrNull { it.type == "checkbox" }
                    ?.let { element ->
                        element.data?.let { CheckboxElement(element.name, it, HashSet()) }
                    }
                return NodeState(
                    node = node,
                    checkboxElement = checkboxElement,
                    messagePreviewElement = messagePreview.takeIf { has("message_preview") },
                    channelPreviewElement = channelPreview.takeIf { has("channel_preview") },
                    directoryServerPreviewElement = directoryServerPreview
                        .takeIf { has("guild_directory_entry_preview") },
                    eventPreviewElement = eventPreview.takeIf { has("guild_scheduled_event_preview") },
                    blockUserElement = blockUserElement.takeIf { has("block_users") },
                    breadcrumbsElement = history.takeIf { has("breadcrumbs") },
                    successElement = has("success"),
                    submitState = submitState,
                    bottomButton = node.button,
                )
            }

            fun shouldHideBackArrow(): Boolean =
                submitState is SubmitState.Loading ||
                    nodeNavigationType.node.id == menu.successNodeId ||
                    nodeNavigationType.node.id == menu.rootNodeId
        }
    }

    init {
        Observable.combineLatest(
            storeStateObservable.computationLatest(),
            restAPI.getReportMenu(args.reportType.pathValue).restSubscribeOn(),
        ) { storeState, menu -> storeState to menu }
            .ui(this)
            .appSubscribe(
                errorClass = MobileReportsViewModel::class.java,
                errorHandler = {
                    AppLog.e("Can't parse report message? Closing report screen.")
                    updateViewState(ViewState.Invalid)
                },
            ) { (storeState, menu) ->
                val rootNode = menu.nodes[menu.rootNodeId]
                if (rootNode == null) {
                    updateViewState(ViewState.Invalid)
                } else {
                    val messagePreview = parseMessagePreview(storeState)
                    updateViewState(
                        viewState ?: ViewState.Menu(
                            menu = menu,
                            messagePreview = messagePreview,
                            channelPreview = parseChannelPreview(storeState),
                            directoryServerPreview = parseDirectoryServerPreview(storeState),
                            eventPreview = parseEventPreview(storeState),
                            nodeNavigationType = NodeNavigationType.Initial(rootNode),
                            history = emptyList(),
                            blockUserElement = messagePreview?.let {
                                BlockUserElement(it.author.id in storeState.blockedUsers, it.author)
                            },
                        ),
                    )
                }
            }
    }

    fun handleBack(): Boolean {
        val current = viewState as? ViewState.Menu ?: return false
        val node = current.nodeNavigationType.node
        if (node.id == current.menu.successNodeId || current.submitState is SubmitState.Loading) return true
        val previousNode = current.history.lastOrNull()?.node
        if (previousNode == null || node.id == current.menu.rootNodeId) return false
        updateViewState(
            current.copy(
                nodeNavigationType = NodeNavigationType.Back(previousNode),
                history = current.history.dropLast(1),
            ),
        )
        return true
    }

    fun handleBlockUser() {
        val current = viewState as? ViewState.Menu ?: return
        val block = current.blockUserElement ?: return
        updateViewState(current.copy(blockUserElement = block.copy(isBlocked = true)))
        restAPI.addRelationship(
            block.user.id,
            RestAPIParams.UserRelationship(type = 2),
            relationshipContext("REPORT_MENU_NODE_${current.nodeNavigationType.node}.id"),
        ).restSubscribeOn().ui(this).appSubscribe(MobileReportsViewModel::class.java) { }
    }

    fun handleNext(destination: ReportNodeChild, elementResult: NodeElementResult?) {
        val current = viewState as? ViewState.Menu ?: return
        val nextNode = current.menu.nodes[destination.ref] ?: return
        updateViewState(
            current.copy(
                nodeNavigationType = NodeNavigationType.Next(nextNode),
                submitState = SubmitState.None,
                history = current.history + NodeResult(current.nodeNavigationType.node, destination, elementResult),
            ),
        )
    }

    fun handleSubmit() {
        val current = viewState as? ViewState.Menu ?: return
        if (current.submitState is SubmitState.Loading) return
        updateViewState(current.copy(submitState = SubmitState.Loading))

        val id = SnowflakeUtils.fromTimestamp(clock.currentTimeMillis())
        val (breadcrumbs, elements) = ReportSubmissionBody.from(current.history)
        val menu = current.menu
        val common = CommonSubmission(id, menu, breadcrumbs, elements)
        val body = when (val reportArgs = args) {
            is MobileReportArgs.Message -> common.body(
                messageId = reportArgs.messageId,
                channelId = reportArgs.channelId,
            )
            is MobileReportArgs.StageChannel -> {
                val guildId = current.channelPreview?.guild?.id ?: run {
                    AppLog.e("Tried to send report for stage channel, but without guild id?")
                    return
                }
                common.body(channelId = reportArgs.channelId, guildId = guildId)
            }
            is MobileReportArgs.DirectoryServer -> common.body(
                channelId = reportArgs.channelId,
                guildId = reportArgs.guildId,
                hubId = reportArgs.hubId,
            )
            is MobileReportArgs.GuildScheduledEvent -> common.body(
                guildId = reportArgs.guildId,
                guildScheduledEventId = reportArgs.eventId,
            )
        }

        restAPI.submitReport(args.reportType.pathValue, body)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = MobileReportsViewModel::class.java,
                errorHandler = { updateViewState(current.copy(submitState = SubmitState.Error)) },
            ) {
                handleNext(ReportNodeChild("", current.menu.successNodeId), null)
            }
    }

    private fun parseMessagePreview(storeState: StoreState): MessagePreview? {
        val context = context.get() ?: return null
        val channel = storeState.channel ?: return null
        val message = storeState.message ?: return null
        val author = message.author?.let { StoreStream.getUsers().getUsers()[it.id] } ?: return null
        if (viewState is ViewState.Invalid) return null
        val myId = StoreStream.getUsers().getMe().id
        val members = StoreStream.getGuilds().getMembers()[channel.guildId].orEmpty()
        val roles = StoreStream.getGuilds().getRoles()[channel.guildId].orEmpty()
        val names = MessageUtils.getNickOrUsernames(message, channel, members, channel.nicks)
        val rendered = DiscordParser.parseChannelMessage(
            context,
            message.content?.replace("\n", " "),
            MessageRenderContext(
                context = context,
                myId = myId,
                isAnimationEnabled = false,
                userNames = names,
                channelNames = StoreStream.getChannels().getChannelNames(),
                roles = roles,
            ),
            MessagePreprocessor(
                myUserId = myId,
                visibleSpoilerNodeIndices = null,
                embeds = null,
                shouldJumboify = false,
                maxNodes = 50,
            ),
            DiscordParser.ParserOptions.REPLY,
            false,
        )
        val authorName = names[author.id] ?: message.author?.username.orEmpty()
        return MessagePreview(
            text = rendered,
            authorName = authorName,
            authorNameColor = GuildMember.getColor(
                members[author.id],
                ColorCompat.getThemedColor(context, R.attr.colorHeaderPrimary),
            ),
            author = author,
            hasEmbeds = message.hasEmbeds() || message.hasAttachments(),
        )
    }

    private fun parseChannelPreview(state: StoreState) =
        state.guild?.let { guild -> state.stageInstance?.let { ChannelPreview(guild, it) } }

    private fun parseDirectoryServerPreview(state: StoreState) =
        state.guild?.let { guild -> state.directoryEntry?.let { DirectoryServerPreview(guild, it) } }

    private fun parseEventPreview(state: StoreState) =
        state.event?.let { event -> state.guild?.let { GuildScheduledEventPreview(it, event) } }

    private data class CommonSubmission(
        val id: Long,
        val menu: MenuAPIResponse,
        val breadcrumbs: List<Int>,
        val elements: Map<String, List<String>>,
    ) {
        fun body(
            messageId: Long? = null,
            channelId: Long? = null,
            guildId: Long? = null,
            hubId: Long? = null,
            guildScheduledEventId: Long? = null,
        ) = ReportSubmissionBody(
            id = id,
            messageId = messageId,
            channelId = channelId,
            guildId = guildId,
            hubId = hubId,
            guildScheduledEventId = guildScheduledEventId,
            language = menu.language ?: "en",
            variant = menu.variant,
            name = menu.name,
            version = menu.version,
            breadcrumbs = breadcrumbs,
            elements = elements,
        )
    }

    companion object {
        private fun relationshipContext(location: String): String =
            contextPropertiesOf("location" to location)

        private fun getStoreState(
            args: MobileReportArgs,
            restAPI: RestAPIInterface,
        ): Observable<StoreState> {
            val eventArgs = args as? MobileReportArgs.GuildScheduledEvent
            val directoryArgs = args as? MobileReportArgs.DirectoryServer
            val directories: Observable<RestCallState<List<DirectoryEntryGuild>>?> = when {
                directoryArgs == null -> Observable.just(null)
                DirectoryStores.isStoreInstalled ->
                    (DirectoryStores.directories as StoreDirectories)
                        .observeDirectoriesForChannel(args.channelId)
                else -> restAPI.getDirectoryEntries(args.channelId)
                    .map<RestCallState<List<DirectoryEntryGuild>>> { Success(it) }
                    .map { it as RestCallState<List<DirectoryEntryGuild>>? }
            }
            return Observable.combineLatest(
                StoreStream.getMessages().observeMessagesForChannel(
                    args.channelId,
                    (args as? MobileReportArgs.Message)?.messageId ?: -1L,
                ),
                StoreStream.getChannels().observeChannel(args.channelId),
                StoreStream.getGuilds().observeFromChannelId(args.channelId),
                StoreStream.getStageInstances().observeStageInstanceForChannel(args.channelId),
                StoreStream.getUserRelationships().observeForType(2),
                directories,
                StoreStream.getGuildScheduledEvents().observeGuildScheduledEvent(
                    eventArgs?.eventId,
                    eventArgs?.guildId,
                ),
                StoreStream.getGuilds().observeGuild(eventArgs?.guildId ?: -1L),
            ) { message, channel, channelGuild, stageInstance, blockedUsers, directoryState, event, eventGuild ->
                StoreState(
                    message = message,
                    channel = channel,
                    guild = channelGuild ?: eventGuild,
                    directoryEntry = directoryState?.invoke()?.firstOrNull {
                        it.guild.id == directoryArgs?.guildId
                    },
                    stageInstance = stageInstance,
                    blockedUsers = blockedUsers,
                    event = event,
                )
            }
        }
    }
}
