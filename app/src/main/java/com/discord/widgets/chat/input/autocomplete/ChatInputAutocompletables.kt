package com.discord.widgets.chat.input.autocomplete

import com.discord.api.channel.Channel
import com.discord.api.channel.isPrivate
import com.discord.stores.StoreStream
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.filterNull
import com.discord.widgets.chat.input.ChatInputStoreBindings
import com.discord.widgets.chat.input.autocomplete.sources.ApplicationCommandsAutocompletableSource
import com.discord.widgets.chat.input.autocomplete.sources.ChannelAutocompletableSource
import com.discord.widgets.chat.input.autocomplete.sources.EmojiAutocompletableSource
import com.discord.widgets.chat.input.autocomplete.sources.UserAutocompletableSource
import java.util.TreeSet
import rx.Observable

/**
 * The single place the chat input asks "what can be autocompleted in this channel?".
 *
 * Sources are lazy singletons because they are stateless views over stores; building one per
 * composer would multiply the store subscriptions by the number of open chat surfaces.
 */
object ChatInputAutocompletables {

    val USERS_SOURCE: UserAutocompletableSource by lazy {
        UserAutocompletableSource(
            storeUsers = StoreStream.getUsers(),
            storeGuilds = StoreStream.getGuilds(),
            storePresences = StoreStream.getPresences(),
            storePermissions = StoreStream.getPermissions(),
            storeChannels = StoreStream.getChannels(),
        )
    }

    val CHANNEL_SOURCE: ChannelAutocompletableSource by lazy {
        ChannelAutocompletableSource(
            storeChannels = StoreStream.getChannels(),
            observeJoinedThreads = { guildId -> ChatInputStoreBindings.joinedThreads(guildId) },
            storePermissions = StoreStream.getPermissions(),
        )
    }

    val APPLICATION_COMMANDS_SOURCE: ApplicationCommandsAutocompletableSource by lazy {
        ApplicationCommandsAutocompletableSource(
            storeUsers = StoreStream.getUsers(),
            storeGuilds = StoreStream.getGuilds(),
            storeApplicationCommands = StoreStream.getApplicationCommands(),
            storePermissions = StoreStream.getPermissions(),
            storeChannels = StoreStream.getChannels(),
        )
    }

    val EMOJI_SOURCE: EmojiAutocompletableSource by lazy {
        EmojiAutocompletableSource(
            userSettingsStore = StoreStream.getUserSettings(),
            emojiSetSource = { guildId, channelId ->
                // Usability is filtered again below via isUsable()/isAvailable(), so include every
                // emoji here — otherwise Fake-Nitro emojis never reach the autocomplete candidates.
                StoreStream.getEmojis().getEmojiSet(
                    guildId,
                    channelId,
                    includeUnusableEmojis = true,
                    includeUnavailableEmojis = true,
                )
            },
        )
    }

    /**
     * Every candidate for [channelId], keyed by the character that opens it.
     *
     * A private channel contributes no `#channel` candidates: there is no guild to enumerate, and
     * the reference does not offer cross-guild channel mentions from a DM.
     */
    fun observeChannelAutocompletables(
        channelId: Long,
    ): Observable<Map<LeadingIdentifier, Set<Autocompletable>>> =
        StoreStream.getChannels().observeChannel(channelId)
            .filterNull()
            .switchMap { channel -> observeForChannel(channel) }
            .computationLatest()

    private fun observeForChannel(
        channel: Channel,
    ): Observable<Map<LeadingIdentifier, Set<Autocompletable>>> {
        val users = USERS_SOURCE.observeAutocompletables(channel)
        val emoji = EMOJI_SOURCE.observeEmojiAutocompletables(channel)
        val commands = APPLICATION_COMMANDS_SOURCE.observeApplicationCommandAutocompletables(channel)
        val channels = if (channel.isPrivate()) {
            Observable.just(emptyMap<LeadingIdentifier, TreeSet<Autocompletable>>())
        } else {
            CHANNEL_SOURCE.observeChannelAutocompletables(channel.guildId)
        }
        return Observable.combineLatest(users, emoji, channels, commands) { a, b, c, d ->
            val merged = LinkedHashMap<LeadingIdentifier, Set<Autocompletable>>()
            merged.putAll(a)
            merged.putAll(b)
            merged.putAll(c)
            merged.putAll(d)
            merged
        }
    }
}
