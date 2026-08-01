package com.discord.widgets.guilds.invite

import com.discord.api.channel.Channel
import com.discord.api.guildjoinrequest.ApplicationStatus
import com.discord.api.guildjoinrequest.GuildJoinRequest
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import rx.observers.TestSubscriber
import rx.subjects.PublishSubject

class InviteJoinHelperTest {
    @Test
    fun currentUsersJoinRequestWinsTheInviteRace() {
        val channels = PublishSubject.create<Channel?>()
        val requests = PublishSubject.create<GuildJoinRequest?>()
        val results = TestSubscriber<Pair<Channel?, GuildJoinRequest?>>()
        InviteJoinHelper.raceJoinOutcome(channels, requests, ME).subscribe(results)

        val request = request(ME)
        requests.onNext(request)
        channels.onNext(CHANNEL)

        assertEquals(1, results.onNextEvents.size)
        assertSame(request, results.onNextEvents.single().second)
    }

    @Test
    fun hydratedChannelWinsTheInviteRace() {
        val channels = PublishSubject.create<Channel?>()
        val requests = PublishSubject.create<GuildJoinRequest?>()
        val results = TestSubscriber<Pair<Channel?, GuildJoinRequest?>>()
        InviteJoinHelper.raceJoinOutcome(channels, requests, ME).subscribe(results)

        channels.onNext(CHANNEL)
        requests.onNext(request(ME))

        assertEquals(1, results.onNextEvents.size)
        assertSame(CHANNEL, results.onNextEvents.single().first)
    }

    @Test
    fun anotherUsersJoinRequestCannotWinTheInviteRace() {
        val channels = PublishSubject.create<Channel?>()
        val requests = PublishSubject.create<GuildJoinRequest?>()
        val results = TestSubscriber<Pair<Channel?, GuildJoinRequest?>>()
        InviteJoinHelper.raceJoinOutcome(channels, requests, ME).subscribe(results)

        requests.onNext(request(ME + 1))
        channels.onNext(CHANNEL)

        assertEquals(1, results.onNextEvents.size)
        assertSame(CHANNEL, results.onNextEvents.single().first)
    }

    private fun request(userId: Long) = GuildJoinRequest(
        userId = userId,
        user = null,
        guildId = GUILD_ID,
        createdAt = "",
        applicationStatus = ApplicationStatus.STARTED,
        rejectionReason = null,
        inviteCode = null,
        lastSeen = null,
        actionedAt = null,
    )

    companion object {
        private const val ME = 1L
        private const val GUILD_ID = 20L
        private val CHANNEL = Gson().fromJson(
            """{"id":10,"guild_id":$GUILD_ID,"type":0}""",
            Channel::class.java,
        )
    }
}
