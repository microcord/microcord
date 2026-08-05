package com.discord.widgets.chat.polls

import com.discord.api.message.MessagePoll
import com.discord.api.message.PollAnswer
import com.discord.api.message.PollAnswerCount
import com.discord.api.message.PollMedia
import com.discord.api.message.PollResults
import com.discord.api.message.PollVoteEvent
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.gateway.io.IncomingParser
import com.discord.models.message.Message
import com.discord.api.channel.Channel
import com.discord.utilities.analytics.AnalyticSuperProperties
import com.discord.restapi.RestAPIBuilder
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreMessages
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.rest.RestAPI
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.adapter.shouldAnimatePollResults
import com.discord.utilities.persister.Persister
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.CookieJar
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import rx.Observable

class PollContractsTest {
    private val server = MockWebServer()
    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()

    @Before
    fun setUpPersistence() {
        Persister.init(Files.createTempDirectory("poll-contracts").toFile(), Observable.never())
    }

    @After
    fun tearDown() {
        RestAPI.reset()
        server.shutdown()
    }

    @Test
    fun creationAndVotingUseModernPayloadsAndReactNativeHeaders() {
        server.enqueue(MockResponse().setBody("""{"id":"9","channel_id":"7"}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        RestAPI.install(RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES), emptyList())
        val body = PollCreateViewModel.State(
            question = "Best answer?",
            answers = listOf(
                PollCreateViewModel.Answer("One"),
                PollCreateViewModel.Answer("Two"),
            ),
            duration = PollDuration.FOUR_HOURS,
            multiselect = true,
        ).toBody()

        RestAPI.apiOrNull!!.sendMessage(7, body, null).toBlocking().single()
        RestAPI.apiOrNull!!.votePoll(7, 9, RestAPIParams.PollVote(listOf(1, 2)))
            .toBlocking().subscribe()

        val create = server.takeRequest()
        val vote = server.takeRequest()
        assertEquals(AnalyticSuperProperties.USER_AGENT, create.getHeader("User-Agent"))
        assertEquals(AnalyticSuperProperties.USER_AGENT, vote.getHeader("User-Agent"))
        val json = gson.fromJson(create.body.readUtf8(), com.google.gson.JsonObject::class.java)
        assertEquals(4, json["poll"].asJsonObject["duration"].asInt)
        assertTrue(json["poll"].asJsonObject["allow_multiselect"].asBoolean)
        assertFalse(json["poll"].asJsonObject["answers"].asJsonArray[0].asJsonObject.has("answer_id"))
        assertEquals("/api/v9/channels/7/polls/9/answers/@me", vote.path)
        assertEquals("[1,2]", gson.fromJson(vote.body.readUtf8(), com.google.gson.JsonObject::class.java)["answer_ids"].toString())
    }

    @Test
    fun oneAnswerIsTheSupportedCreationMinimum() {
        assertTrue(
            PollCreateViewModel.State(
                question = "Only one?",
                answers = listOf(PollCreateViewModel.Answer("Yes")),
            ).valid,
        )
        assertFalse(PollCreateViewModel.State(question = "Empty", answers = emptyList()).valid)
    }

    @Test
    fun resultAnimationRunsForNewAndTransitioningRowsOnly() {
        assertTrue(shouldAnimatePollResults(showingResults = true, previous = null, reducedMotion = false))
        assertTrue(shouldAnimatePollResults(showingResults = true, previous = false, reducedMotion = false))
        assertFalse(shouldAnimatePollResults(showingResults = true, previous = true, reducedMotion = false))
        assertFalse(shouldAnimatePollResults(showingResults = true, previous = null, reducedMotion = true))
    }

    @Test
    fun gatewayVotesParseAndUpdateCachedCountsWithoutLosingSelfState() {
        val incoming = IncomingParser(gson).parse(
            """{"op":0,"t":"MESSAGE_POLL_VOTE_ADD","s":1,"d":{"user_id":"5","channel_id":"7","message_id":"9","guild_id":"3","answer_id":1}}""",
        )
        val event = incoming.data as PollVoteEvent
        assertEquals(5L, event.userId)

        val store = StoreMessages(ObservationDeck())
        store.handleChannelSelected(7)
        store.handleMessageCreate(
            Message(
                id = 9,
                channelId = 7,
                poll = MessagePoll(
                    question = PollMedia("Best answer?"),
                    answers = listOf(PollAnswer(1, PollMedia("One")), PollAnswer(2, PollMedia("Two"))),
                    expiry = UtcDateTime(System.currentTimeMillis() + 60_000),
                    allowMultiselect = false,
                    layoutType = 1,
                    results = PollResults(false, listOf(PollAnswerCount(1, 0, false), PollAnswerCount(2, 2, false))),
                ),
            ),
        )
        store.handlePollVote(event, added = true, meUserId = 5)
        val count = store.getMessage(7, 9)!!.poll!!.results!!.answerCounts.first { it.id == 1 }
        assertEquals(1, count.count)
        assertTrue(count.meVoted)

        store.handlePollVote(event, added = false, meUserId = 5)
        val removed = store.getMessage(7, 9)!!.poll!!.results!!.answerCounts.first { it.id == 1 }
        assertEquals(0, removed.count)
        assertFalse(removed.meVoted)
    }

    @Test
    fun modernMessagePatchFieldsSurviveApiDomainRoundTrip() {
        val api = gson.fromJson(
            """{"id":"9","channel_id":"7","lobby_id":"8","mention_channels":[],"poll":{"question":{"text":"Q"},"answers":[],"expiry":null,"duration":24,"allow_multiselect":false,"layout_type":1,"results":{"is_finalized":true,"answer_counts":[]}},"changelog_id":"10","shared_client_theme":{"colors":["#fff"],"gradient_angle":45,"base_mix":10,"base_theme":2}}""",
            com.discord.api.message.Message::class.java,
        )
        val domain = Message(api)
        assertEquals(8L, domain.lobbyId)
        assertEquals(24, domain.poll?.duration)
        assertTrue(domain.poll?.results?.finalized == true)
        assertEquals(10L, domain.synthesizeApiMessage().changelogId)
        assertEquals(45, domain.synthesizeApiMessage().sharedClientTheme?.gradientAngle)
    }

    @Test
    fun pollClosureMetadataDoesNotRenderAsASecondEmbedCard() {
        val message = Message(
            gson.fromJson(
                """{"id":"9","channel_id":"7","type":46,"embeds":[{"fields":[{"name":"poll_question_text","value":"Question"}]}]}""",
                com.discord.api.message.Message::class.java,
            ),
        )
        val channel = gson.fromJson("""{"id":"7","guild_id":"3","type":0}""", Channel::class.java)

        assertTrue(
            ChatListEntry.createEmbedEntries(
                message,
                null,
                blockedChunkExpanded = false,
                allowAnimatedEmojis = true,
                autoPlayGifs = true,
                renderEmbeds = true,
                isThreadStarterMessage = false,
                channel = channel,
                author = null,
                roles = null,
                nickOrUsernames = emptyMap(),
            ).isEmpty(),
        )
    }

    @Test
    fun partialPollUpdatesRetainResultsAndSelfVoteState() {
        val poll = MessagePoll(
            question = PollMedia("Question"),
            answers = listOf(PollAnswer(1, PollMedia("Answer"))),
            expiry = null,
            allowMultiselect = false,
            layoutType = 1,
            results = PollResults(false, listOf(PollAnswerCount(1, 4, true))),
        )
        val message = Message(id = 9, channelId = 7, poll = poll)

        val missingResults = message.merge(
            com.discord.api.message.Message(id = 9, channelId = 7, poll = poll.copy(results = null)),
        )
        assertEquals(poll.results, missingResults.poll?.results)

        val omittedSelfVote = message.merge(
            com.discord.api.message.Message(
                id = 9,
                channelId = 7,
                poll = poll.copy(results = PollResults(true, listOf(PollAnswerCount(1, 4, false)))),
            ),
        )
        assertTrue(omittedSelfVote.poll!!.results!!.answerCounts.single().meVoted)
    }
}
