package com.discord.stores

import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.query.node.QueryNode
import com.discord.utilities.search.query.node.answer.ChannelNode
import com.discord.utilities.search.query.node.answer.AnswerNode
import com.discord.utilities.search.query.node.answer.AuthorTypeAnswerOption
import com.discord.utilities.search.query.node.answer.AuthorTypeNode
import com.discord.utilities.search.query.node.answer.DateNode
import com.discord.utilities.search.query.node.answer.HasAnswerOption
import com.discord.utilities.search.query.node.answer.HasNode
import com.discord.utilities.search.query.node.answer.UserNode
import com.discord.utilities.search.query.node.content.ContentNode
import com.discord.utilities.search.query.node.filter.FilterNode
import com.discord.utilities.search.query.parsing.QueryParser
import com.discord.utilities.search.strings.SearchStringProvider
import com.discord.utilities.search.suggestion.entries.ChannelSuggestion
import com.discord.utilities.search.suggestion.entries.UserSuggestion
import com.discord.widgets.search.SearchInputStore
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Subscription
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject
import rx.subjects.SerializedSubject

class StoreSearchInput : SearchInputStore {
    private val astSubject = SerializedSubject(BehaviorSubject.create<List<QueryNode>>())
    private val inputSubject = SerializedSubject(BehaviorSubject.create(""))
    private val isInputValidSubject = SerializedSubject(BehaviorSubject.create(false))
    private val forcedInputSubject = PublishSubject.create<List<QueryNode>>()
    private var inputSubscription: Subscription? = null

    override val currentInput: Observable<String> = inputSubject
    override val isInputValid: Observable<Boolean> = isInputValidSubject
    override val currentParsedInput: Observable<List<QueryNode>> = astSubject
    override val forcedInput: Observable<List<QueryNode>> = forcedInputSubject

    init {
        astSubject
            .leadingEdgeThrottle(VALIDATION_THROTTLE_MS, TimeUnit.MILLISECONDS)
            .map { nodes -> nodes.any { it !is FilterNode } }
            .observeOn(Schedulers.computation())
            .appSubscribe(errorTag = "validateSearchInput", onNext = isInputValidSubject::onNext)
    }

    override fun init(searchStringProvider: SearchStringProvider) {
        val parser = QueryParser(searchStringProvider)
        inputSubject
            .leadingEdgeThrottle(PARSE_THROTTLE_MS, TimeUnit.MILLISECONDS)
            .distinctUntilChanged()
            .map { parser.parse(it, Unit) }
            .observeOn(Schedulers.computation())
            .appSubscribe(
                errorClass = StoreSearchInput::class.java,
                subscriptionHandler = ::handleInputSubscription,
                onNext = astSubject::onNext,
            )
    }

    override fun updateInput(input: String) = inputSubject.onNext(input)

    override fun clear() {
        handleInputSubscription(null)
        inputSubject.onNext("")
    }

    override fun onFilterClicked(
        filterType: FilterType,
        searchStringProvider: SearchStringProvider,
        query: MutableList<QueryNode>,
    ) {
        val filter = FilterNode(
            filterType,
            when (filterType) {
                FilterType.FROM -> searchStringProvider.fromFilterString
                FilterType.MENTIONS -> searchStringProvider.mentionsFilterString
                FilterType.HAS -> searchStringProvider.hasFilterString
                FilterType.IN -> searchStringProvider.inFilterString
                FilterType.DURING -> searchStringProvider.duringFilterString
                FilterType.BEFORE -> searchStringProvider.beforeFilterString
                FilterType.AFTER -> searchStringProvider.afterFilterString
                FilterType.AUTHOR_TYPE -> searchStringProvider.authorTypeFilterString
                FilterType.LINK_FROM -> searchStringProvider.linkFromFilterString
                FilterType.FILE_TYPE -> searchStringProvider.fileTypeFilterString
                FilterType.FILE_NAME -> searchStringProvider.fileNameFilterString
                FilterType.PINNED -> searchStringProvider.pinnedFilterString
            },
        )
        replaceAndPublish(query.size, listOf(filter), query)
    }

    override fun onFromUserClicked(
        userSuggestion: UserSuggestion,
        fromFilterString: CharSequence,
        query: MutableList<QueryNode>,
    ) = replaceAndPublish(
        getAnswerReplacementStart(query),
        listOf(FilterNode(FilterType.FROM, fromFilterString), UserNode(userSuggestion.userName, userSuggestion.discriminator)),
        query,
    )

    override fun onMentionsUserClicked(
        userSuggestion: UserSuggestion,
        mentionsFilterString: CharSequence,
        query: MutableList<QueryNode>,
    ) = replaceAndPublish(
        getAnswerReplacementStart(query),
        listOf(FilterNode(FilterType.MENTIONS, mentionsFilterString), UserNode(userSuggestion.userName, userSuggestion.discriminator)),
        query,
    )

    override fun onInChannelClicked(
        channelSuggestion: ChannelSuggestion,
        inFilterString: CharSequence,
        query: MutableList<QueryNode>,
    ) = replaceAndPublish(
        getAnswerReplacementStart(query),
        listOf(FilterNode(FilterType.IN, inFilterString), ChannelNode(channelSuggestion.channelName)),
        query,
    )

    override fun onHasClicked(
        hasAnswerOption: HasAnswerOption,
        hasFilterString: CharSequence,
        hasAnswerString: CharSequence,
        query: MutableList<QueryNode>,
    ) = replaceAndPublish(
        getAnswerReplacementStart(query),
        listOf(FilterNode(FilterType.HAS, hasFilterString), HasNode(hasAnswerOption, hasAnswerString)),
        query,
    )

    override fun onAuthorTypeClicked(
        option: AuthorTypeAnswerOption,
        negated: Boolean,
        filterString: CharSequence,
        answerString: CharSequence,
        query: MutableList<QueryNode>,
    ) = replaceAndPublish(
        getAnswerReplacementStart(query),
        listOf(
            FilterNode(FilterType.AUTHOR_TYPE, filterString),
            AuthorTypeNode(option, negated, answerString),
        ),
        query,
    )

    override fun onDateClicked(
        filterType: FilterType,
        filterString: CharSequence,
        dateText: CharSequence,
        query: MutableList<QueryNode>,
    ) = replaceAndPublish(
        getAnswerReplacementStart(query),
        listOf(FilterNode(filterType, filterString), DateNode(dateText)),
        query,
    )

    override fun onQueryClicked(query: List<QueryNode>) = forcedInputSubject.onNext(query)

    private fun getAnswerReplacementStart(query: List<QueryNode>): Int {
        if (query.size <= 1) return 0
        val last = query.lastIndex
        return when {
            query[last] is FilterNode -> last
            (query[last] is ContentNode || query[last] is AnswerNode) && query[last - 1] is FilterNode -> last - 1
            else -> -1
        }
    }

    private fun replaceAndPublish(index: Int, replacement: List<QueryNode>, query: MutableList<QueryNode>) {
        if (index !in 0..query.size) return
        query.subList(index, query.size).clear()
        query.addAll(index, replacement)
        forcedInputSubject.onNext(query)
    }

    @Synchronized
    private fun handleInputSubscription(subscription: Subscription?) {
        inputSubscription?.unsubscribe()
        inputSubscription = subscription
    }

    private companion object {
        const val PARSE_THROTTLE_MS = 100L
        const val VALIDATION_THROTTLE_MS = 50L
    }
}
