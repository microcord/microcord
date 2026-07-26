package com.discord.widgets.chat.input.autocomplete.sources

import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.stores.StoreChannels
import com.discord.stores.StorePermissions
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.widgets.chat.input.autocomplete.AutocompletableComparator
import com.discord.widgets.chat.input.autocomplete.Autocompletable
import com.discord.widgets.chat.input.autocomplete.ChannelAutocompletable
import com.discord.widgets.chat.input.autocomplete.LeadingIdentifier
import java.util.TreeSet
import java.util.concurrent.TimeUnit
import rx.Observable

/**
 * `#channel` candidates for a guild: its own channels plus the threads the user has joined,
 * filtered to those the user can actually view.
 *
 * The 500 ms leading-edge throttle matters: channel permissions are recomputed on every role and
 * overwrite change, and a large guild would otherwise rebuild this `TreeSet` dozens of times while
 * the user types a single word.
 */
class ChannelAutocompletableSource(
    private val storeChannels: StoreChannels,
    /**
     * `StoreThreadsActiveJoined.observeActiveJoinedThreadsChannelsForGuild`, injected because
     * `StoreStream` does not construct that store yet. See `ChatInputStoreBindings`.
     */
    private val observeJoinedThreads: (guildId: Long) -> Observable<Map<Long, Channel>>,
    private val storePermissions: StorePermissions,
) {

    fun observeChannelAutocompletables(
        guildId: Long,
    ): Observable<Map<LeadingIdentifier, TreeSet<Autocompletable>>> =
        Observable.combineLatest(
            storeChannels.observeChannelsForGuild(guildId).computationLatest(),
            observeJoinedThreads(guildId),
            storePermissions.observePermissionsForAllChannels().computationLatest(),
        ) { guildChannels, joinedThreads, permissions ->
            val channels = TreeSet<Autocompletable>(AutocompletableComparator())
            for (channel in (guildChannels + joinedThreads).values) {
                if (!PermissionUtils.can(Permission.VIEW_CHANNEL, permissions[channel.id])) continue
                channels.add(ChannelAutocompletable(channel))
            }
            mapOf(LeadingIdentifier.CHANNELS to channels)
        }
            .computationLatest()
            .distinctUntilChanged()
            .leadingEdgeThrottle(THROTTLE_MS, TimeUnit.MILLISECONDS)

    private companion object {
        private const val THROTTLE_MS = 500L
    }
}
