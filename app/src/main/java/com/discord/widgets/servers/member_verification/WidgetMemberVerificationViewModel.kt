package com.discord.widgets.servers.member_verification

import com.discord.api.guild.GuildVerificationLevel
import com.discord.api.guildjoinrequest.ApplicationStatus
import com.discord.api.role.GuildRole
import com.discord.app.AppViewModel
import com.discord.app.AppLog
import com.discord.models.domain.ModelMemberVerificationForm
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.parser.Parser
import com.discord.stores.StoreGuildMemberVerificationForm
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessageParseState
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.user.UserUtils.hasPhone
import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItem
import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItemApproveTerms
import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItemHeader
import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItemMultipleChoice
import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItemParagraph
import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItemTerm
import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItemTermsHeader
import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItemTextInput
import rx.Observable
import rx.subjects.PublishSubject

class WidgetMemberVerificationViewModel(
    private val guildId: Long,
    private val location: String,
    private val inviteGuild: Guild? = null,
    private val formStore: StoreGuildMemberVerificationForm = StoreStream.getGuildMemberVerificationForm(),
    private val restAPI: RestAPIInterface = RestAPI.api,
    private val rulesParser: Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> =
        DiscordParser.createParser(false, true),
    storeObservable: Observable<StoreState> = observeStores(guildId, inviteGuild),
) : AppViewModel<WidgetMemberVerificationViewModel.ViewState>(ViewState.Loading) {

    enum class VerificationType { PHONE, EMAIL }

    sealed class Event {
        data class Error(val message: String?) : Event()
        data class Success(
            val guildId: Long?,
            val guildName: String?,
            val applicationStatus: ApplicationStatus,
            val lastSeen: String?,
        ) : Event()
    }

    data class StoreState(
        val me: MeUser,
        val guild: Guild?,
        val memberVerificationFormData: StoreGuildMemberVerificationForm.MemberVerificationFormData?,
        val channels: Map<Long, String>,
        val roles: Map<Long, GuildRole>,
        val allowAnimatedEmojis: Boolean,
    )

    sealed class ViewState {
        object Invalid : ViewState()
        object Loading : ViewState()
        data class Loaded(
            val isRulesListVisible: Boolean,
            val submitting: Boolean,
            val disabled: Boolean,
            val verificationType: VerificationType?,
            val needsAdditionalVerification: Boolean,
            val formItems: List<MemberVerificationItem>,
            val form: ModelMemberVerificationForm?,
            val channelNames: Map<Long, String>,
            val roles: Map<Long, GuildRole>,
            val allowAnimatedEmojis: Boolean,
            val isFormValid: Boolean,
            val isTermsApproved: Boolean,
            val guild: Guild?,
        ) : ViewState()
    }

    private val eventSubject = PublishSubject.create<Event>()
    private val formUserInputDataMap = HashMap<Int, Any?>()

    init {
        AnalyticsTracker.openModal("Membership Gating", location, guildId)
        storeObservable.computationLatest().ui(this)
            .appSubscribe(WidgetMemberVerificationViewModel::class.java, onNext = ::handleStoreState)
        formStore.fetchMemberVerificationForm(guildId)
    }

    fun observeEvents(): Observable<Event> = eventSubject

    fun updateFormValidation(fieldIndex: Int, response: Any) {
        formUserInputDataMap[fieldIndex] = response
        val loaded = viewState as? ViewState.Loaded ?: return
        val valid = isFormValid()
        if (loaded.isFormValid == valid) return
        updateViewState(
            loaded.copy(
                disabled = !(valid && loaded.isTermsApproved && !loaded.needsAdditionalVerification),
                formItems = createFormItems(loaded.form?.formFields, loaded.isTermsApproved, loaded),
                isFormValid = valid,
            ),
        )
    }

    fun updateTermsApproval(approved: Boolean) {
        val loaded = viewState as? ViewState.Loaded ?: return
        val valid = isFormValid()
        updateViewState(
            loaded.copy(
                disabled = !(valid && approved && !loaded.needsAdditionalVerification),
                formItems = createFormItems(loaded.form?.formFields, approved, loaded),
                isFormValid = valid,
                isTermsApproved = approved,
            ),
        )
    }

    fun applyToJoinGuild() {
        val loaded = viewState as? ViewState.Loaded ?: return
        if (loaded.disabled || loaded.submitting) return
        updateViewState(loaded.copy(submitting = true))
        val fields = loaded.form?.formFields.orEmpty()
        formUserInputDataMap.forEach { (index, response) ->
            if (response != null) fields[index].response = response
        }
        fields.forEach { field ->
            if (field.memberVerificationFieldType == ModelMemberVerificationForm.MemberVerificationFieldType.TERMS) {
                field.response = loaded.isTermsApproved
            }
        }
        restAPI.createGuildJoinRequest(
            guildId,
            RestAPIParams.MemberVerificationForm(fields, loaded.form?.version),
        ).restSubscribeOn().ui(this).appSubscribe(
            errorClass = WidgetMemberVerificationViewModel::class.java,
            errorHandler = { error ->
                val member = StoreStream.getGuilds().getMember(guildId, StoreStream.getUsers().getMe().id)
                if (error.statusCode in setOf(403, 410) && member?.pending == false) {
                    error.showErrorToasts = false
                    error.shouldLog = false
                    emitSuccess(loaded, ApplicationStatus.APPROVED, null)
                } else {
                    updateViewState(loaded.copy(submitting = false))
                    val message = error.message ?: error.messages.values.firstNotNullOfOrNull(List<String>::firstOrNull)
                    AppLog.i("Member verification rejected status=${error.statusCode} code=${error.code} message=$message")
                    eventSubject.onNext(Event.Error(message))
                }
            },
        ) { response ->
            emitSuccess(loaded, response.applicationStatus, response.lastSeen)
        }
    }

    private fun emitSuccess(
        loaded: ViewState.Loaded,
        status: ApplicationStatus,
        lastSeen: String?,
    ) {
        updateViewState(loaded.copy(submitting = false))
        eventSubject.onNext(Event.Success(loaded.guild?.id, loaded.guild?.name, status, lastSeen))
    }

    override fun onCleared() {
        super.onCleared()
        AnalyticsTracker.dismissModal("Membership Gating", location, "dismissed", guildId)
    }

    private fun handleStoreState(state: StoreState) {
        when (state.memberVerificationFormData?.fetchState) {
            null -> Unit
            StoreGuildMemberVerificationForm.FetchStates.FETCHING -> updateViewState(ViewState.Loading)
            StoreGuildMemberVerificationForm.FetchStates.FAILED -> updateViewState(ViewState.Invalid)
            StoreGuildMemberVerificationForm.FetchStates.SUCCEEDED -> updateViewState(loadedState(state))
        }
    }

    private fun loadedState(state: StoreState): ViewState.Loaded {
        val fields = state.memberVerificationFormData?.form?.formFields
        val verificationType = when (state.guild?.verificationLevel) {
            GuildVerificationLevel.HIGHEST -> VerificationType.PHONE
            GuildVerificationLevel.LOW -> VerificationType.EMAIL
            else -> null
        }
        val needsVerification = when (verificationType) {
            VerificationType.PHONE -> !state.me.hasPhone
            VerificationType.EMAIL -> !state.me.isVerified
            null -> false
        }
        val previousFields = (viewState as? ViewState.Loaded)?.form?.formFields
        val hasTerms = if (fields != null && fields != previousFields) {
            formUserInputDataMap.clear()
            var terms = false
            fields.forEachIndexed { index, field ->
                if (field.memberVerificationFieldType == ModelMemberVerificationForm.MemberVerificationFieldType.TERMS) {
                    terms = true
                } else {
                    formUserInputDataMap[index] = null
                }
            }
            terms
        } else {
            false
        }
        val isTermsApproved = !hasTerms
        val items = createFormItems(fields, isTermsApproved, state)
        val fieldsEmpty = fields.isNullOrEmpty()
        val formValid = isFormValid()
        return ViewState.Loaded(
            isRulesListVisible = !fieldsEmpty,
            submitting = false,
            disabled = fieldsEmpty || !(formValid && isTermsApproved && !needsVerification),
            verificationType = verificationType,
            needsAdditionalVerification = needsVerification,
            formItems = items,
            form = state.memberVerificationFormData?.form,
            channelNames = state.channels,
            roles = state.roles,
            allowAnimatedEmojis = state.allowAnimatedEmojis,
            isFormValid = formValid,
            isTermsApproved = isTermsApproved,
            guild = state.guild,
        )
    }

    private fun isFormValid(): Boolean = areResponsesValid(formUserInputDataMap.values)

    private fun createFormItems(
        fields: List<ModelMemberVerificationForm.FormField>?,
        termsApproved: Boolean,
        state: StoreState,
    ) = createFormItems(fields, termsApproved, state.channels, state.roles, state.allowAnimatedEmojis)

    private fun createFormItems(
        fields: List<ModelMemberVerificationForm.FormField>?,
        termsApproved: Boolean,
        loaded: ViewState.Loaded,
    ) = createFormItems(fields, termsApproved, loaded.channelNames, loaded.roles, loaded.allowAnimatedEmojis)

    private fun createFormItems(
        fields: List<ModelMemberVerificationForm.FormField>?,
        termsApproved: Boolean,
        channelNames: Map<Long, String>,
        roles: Map<Long, GuildRole>,
        allowAnimatedEmojis: Boolean,
    ): List<MemberVerificationItem> = buildList {
        fields.orEmpty().forEachIndexed { fieldIndex, field ->
            val response = formUserInputDataMap[fieldIndex]
            when (field.memberVerificationFieldType) {
                ModelMemberVerificationForm.MemberVerificationFieldType.TERMS -> if (field.values.isNotEmpty()) {
                    add(MemberVerificationItemTermsHeader())
                    field.values.forEachIndexed { index, term ->
                        add(
                            MemberVerificationItemTerm(
                                index + 1,
                                term,
                                rulesParser.parse(term, MessageParseState.initialState),
                                channelNames,
                                roles,
                                allowAnimatedEmojis,
                                index == 0,
                                index == field.values.lastIndex,
                            ),
                        )
                    }
                    add(MemberVerificationItemApproveTerms(fieldIndex, termsApproved))
                }
                ModelMemberVerificationForm.MemberVerificationFieldType.TEXT_INPUT -> {
                    add(MemberVerificationItemHeader(field.label))
                    add(MemberVerificationItemTextInput(fieldIndex, response as? String))
                }
                ModelMemberVerificationForm.MemberVerificationFieldType.PARAGRAPH -> {
                    add(MemberVerificationItemHeader(field.label))
                    add(MemberVerificationItemParagraph(fieldIndex, response as? String))
                }
                ModelMemberVerificationForm.MemberVerificationFieldType.MULTIPLE_CHOICE -> {
                    add(MemberVerificationItemHeader(field.label))
                    add(MemberVerificationItemMultipleChoice(fieldIndex, field.choices, response as? Int))
                }
                ModelMemberVerificationForm.MemberVerificationFieldType.UNKNOWN -> Unit
            }
        }
    }

    companion object {
        internal fun areResponsesValid(responses: Collection<Any?>): Boolean = responses.none {
            it == null || it is String && it.isEmpty()
        }

        private fun observeStores(guildId: Long, inviteGuild: Guild?): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getUsers().observeMe(true),
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getGuildMemberVerificationForm().observeMemberVerificationFormData(guildId),
            StoreStream.getChannels().observeNames(),
            StoreStream.getGuilds().observeRoles(guildId),
            StoreStream.getUserSettings().observeIsAnimatedEmojisEnabled(false),
        ) { me, guild, formData, channels, roles, animated ->
            StoreState(me, guild ?: inviteGuild, formData, channels, roles, animated)
        }
    }
}
