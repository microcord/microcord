package com.discord.widgets.chat.input

import com.discord.api.channel.Channel
import com.discord.stores.StoreChat
import com.discord.stores.StoreSlowMode
import com.discord.stores.StoreThreadDraft
import rx.Observable

/**
 * Temporary bridge for the three stores the chat input needs that `StoreStream` does not construct
 * yet.
 *
 * `StoreChat`, `StoreSlowMode` and `StoreThreadsActiveJoined` all exist as classes in
 * `com.discord.stores`, but nothing owns an instance: `StoreStream` has no `getChat()`,
 * `getSlowMode()` or `getThreadsActiveJoined()`. Constructing our own instance here would be worse
 * than not having one — two `StoreChat`s means two mutually invisible states — so instead this
 * object holds the *observables* the input consumes, with defaults that are honestly empty.
 *
 * The consequences of leaving them unset are specific and small, and they degrade to "feature
 * absent", never to "screen inert":
 *
 * * [chatEvents] unset → "insert @mention into the composer" from the member list does nothing.
 * * [slowModeCooldown] unset → the send button never shows a slow-mode countdown.
 * * [joinedThreads] unset → `#thread` names are missing from `#` autocomplete; guild channels
 *   still appear.
 *
 * **Delete this file** the moment `StoreStream` constructs those three stores, and replace each
 * call site with the direct `StoreStream.getXxx()` accessor.
 */
object ChatInputStoreBindings {

    /** [StoreChat.observeEvents]. */
    @JvmStatic
    var chatEvents: () -> Observable<StoreChat.Event> = { Observable.empty() }

    /** [StoreSlowMode.observeCooldownSecs] for one channel. */
    @JvmStatic
    var slowModeCooldown: (channelId: Long) -> Observable<Int> = { Observable.just(0) }

    /** `StoreThreadsActiveJoined.observeActiveJoinedThreadsChannelsForGuild`. */
    @JvmStatic
    var joinedThreads: (guildId: Long) -> Observable<Map<Long, Channel>> = { Observable.just(emptyMap()) }

    /**
     * [com.discord.stores.StoreLurking.isLurkingObs].
     *
     * Unset → the composer never shows the "join to talk" guard, so a lurked guild looks writable
     * until the send is rejected by the API.
     */
    @JvmStatic
    var isLurking: (guildId: Long) -> Observable<Boolean> = { Observable.just(false) }

    /**
     * [com.discord.stores.StoreThreadDraft.observeDraftState].
     *
     * Unset → the thread-creation composer reports an empty draft; harmless while thread creation
     * itself is unreconstructed.
     */
    @JvmStatic
    var threadDraftState: () -> Observable<StoreThreadDraft.ThreadDraftState> =
        { Observable.just(StoreThreadDraft.ThreadDraftState()) }
}
