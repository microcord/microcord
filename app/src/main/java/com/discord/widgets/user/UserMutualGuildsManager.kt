package com.discord.widgets.user

import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreGuildsSorted
import com.discord.stores.StoreStream
import rx.Observable

class UserMutualGuildsManager @JvmOverloads constructor(
    private val storeGuildsSorted: StoreGuildsSorted = StoreStream.getGuildsSorted(),
    private val storeGuilds: StoreGuilds = StoreStream.getGuilds(),
) {
    fun observeMutualGuilds(userIds: Collection<Long>): Observable<Map<Long, List<Guild>>> =
        Observable.combineLatest(
            storeGuildsSorted.observeOrderedGuilds(),
            storeGuilds.observeComputed(),
        ) { guilds, members -> resolve(userIds, guilds.values, members) }
            .distinctUntilChanged()

    companion object {
        internal fun resolve(
            userIds: Collection<Long>,
            orderedGuilds: Collection<Guild>,
            guildMembers: Map<Long, Map<Long, GuildMember>>,
        ): Map<Long, List<Guild>> = userIds.associateWithTo(LinkedHashMap<Long, List<Guild>>()) { userId ->
            orderedGuilds.filter { guildMembers[it.id]?.containsKey(userId) == true }
        }
    }
}
