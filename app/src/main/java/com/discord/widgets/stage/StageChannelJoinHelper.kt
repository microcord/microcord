package com.discord.widgets.stage

import android.content.Context
import androidx.annotation.MainThread
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppLog
import com.discord.app.AppToast
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreLurking
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserRelationships
import com.discord.stores.StoreVoiceChannelSelected
import com.discord.stores.StoreVoiceStates
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.voice.VoiceChannelJoinability
import com.discord.utilities.voice.VoiceChannelJoinabilityUtils
import com.discord.widgets.stage.sheet.WidgetStageAudienceBlockedBottomSheet
import com.discord.widgets.voice.fullscreen.WidgetCallFullscreen
import rx.Subscription

/**
 * The single path into a stage channel.
 *
 * Joining is gated three times, in order: the channel must be joinable at all
 * ([verifyStageJoinability]), the user must not be walking into a room containing someone they
 * have blocked (they get a confirmation sheet first), and only then is the voice connection made.
 */
object StageChannelJoinHelper {

    /** Wire value of the "blocked" relationship type. */
    private const val RELATIONSHIP_TYPE_BLOCKED = 2

    private var lurkSubscription: Subscription? = null

    /**
     * Shown when the guild has hit its video-participant cap.
     *
     * The reference opens a dedicated notice dialog here (an obfuscated `b.a.a.m.k` in the
     * decompile, so its class is not recoverable by name). Defaults to the same toast the other
     * un-joinable cases use so the press is never silently swallowed.
     */
    @JvmField
    var onGuildVideoAtCapacity: ((Context, FragmentManager) -> Unit)? = null

    @Suppress("LongParameterList")
    @MainThread
    @JvmOverloads
    fun connectToStage(
        context: Context,
        fragmentManager: FragmentManager,
        channelId: Long,
        warnedAboutBlockedUsers: Boolean = false,
        launchFullscreen: Boolean = true,
        voiceChannelSelectedStore: StoreVoiceChannelSelected = StoreStream.getVoiceChannelSelected(),
        channelsStore: StoreChannels = StoreStream.getChannels(),
        voiceStatesStore: StoreVoiceStates = StoreStream.getVoiceStates(),
        userRelationshipsStore: StoreUserRelationships = StoreStream.getUserRelationships(),
        onCompleted: () -> Unit = {},
    ) {
        verifyStageJoinability(context, fragmentManager, channelId) {
            val channel = channelsStore.getChannel(channelId) ?: return@verifyStageJoinability

            val relationships = userRelationshipsStore.getRelationships()
            val occupantIds = voiceStatesStore.get()[channel.guildId].orEmpty()
                .values
                .filter { it.channelId == channel.id }
                .map { it.userId }
            // Already being connected here means the user has seen who is in the room, so the
            // warning would only get in the way of rejoining.
            val alreadyConnected =
                voiceChannelSelectedStore.getSelectedVoiceChannelId() == channelId
            val shouldWarn = !warnedAboutBlockedUsers &&
                !alreadyConnected &&
                occupantIds.any { relationships[it] == RELATIONSHIP_TYPE_BLOCKED }

            if (shouldWarn) {
                WidgetStageAudienceBlockedBottomSheet.show(
                    fragmentManager,
                    channelId,
                    launchFullscreen,
                )
                onCompleted()
                return@verifyStageJoinability
            }

            voiceChannelSelectedStore.selectVoiceChannel(channelId)
                .appSubscribe(StageChannelJoinHelper::class.java, context) {
                    if (launchFullscreen) {
                        WidgetCallFullscreen.launch(
                            context,
                            channelId,
                            connectOnLaunch = true,
                            animate = true,
                        )
                    }
                    onCompleted()
                }
        }
    }

    /**
     * Joins [guildId] as a lurker, waits for the guild and the previous voice connection to settle,
     * then connects to [channelId].
     *
     * The reference implements this as a suspend function; the sequencing is identical here, done
     * with rx because this project has no `RxCoroutineUtils`. A second call cancels the first, so
     * tapping two stage cards in a row does not race two lurk joins against each other.
     *
     * `StoreStream` does not publish a [StoreLurking] yet, so [lurkingStore] is `null` by default
     * and the whole flow reports "you must join this server first" rather than half-joining. See
     * [StageStoreAccess].
     */
    @Suppress("LongParameterList")
    @MainThread
    @JvmOverloads
    fun lurkAndJoinStage(
        context: Context,
        fragmentManager: FragmentManager,
        guildId: Long,
        channelId: Long,
        launchFullscreen: Boolean = false,
        guildsStore: StoreGuilds = StoreStream.getGuilds(),
        lurkingStore: StoreLurking? = StageStoreAccess.lurkingOrNull(),
        selectedVoiceChannelStore: StoreVoiceChannelSelected =
            StoreStream.getVoiceChannelSelected(),
    ) {
        if (lurkingStore == null) {
            // No toast: the recovered strings have no "could not lurk" message and inventing one —
            // or borrowing a differently-meaning one — would be worse than doing nothing visible.
            AppLog.w(
                "StageChannelJoinHelper",
                "lurkAndJoinStage ignored: StoreStream publishes no StoreLurking",
            )
            return
        }

        lurkSubscription?.unsubscribe()
        lurkSubscription = null

        selectedVoiceChannelStore.clear()
        lurkingStore.startLurking(
            guildId = guildId,
            channelId = channelId,
            navigateToGuild = false,
            onGuildLurked = { _, _, _ ->
                // The guild arrives over the gateway a moment after the REST join returns, and the
                // old voice connection tears down asynchronously; joining before either settles
                // picks up stale permissions.
                guildsStore.observeGuildIds()
                    .filter { guildId in it }
                    .take(1)
                    .switchMap {
                        selectedVoiceChannelStore.observeSelectedChannel()
                            .filter { it == null }
                            .take(1)
                    }
                    .appSubscribe(
                        errorClass = StageChannelJoinHelper::class.java,
                        context = context,
                        subscriptionHandler = { lurkSubscription = it },
                    ) {
                        connectToStage(
                            context = context,
                            fragmentManager = fragmentManager,
                            channelId = channelId,
                            launchFullscreen = launchFullscreen,
                        ) {
                            lurkingStore.removeOldLurkingGuilds(guildId)
                        }
                    }
            },
        )
    }

    /** Runs [onJoinVerified] when the channel can be entered, otherwise explains why it cannot. */
    @MainThread
    fun verifyStageJoinability(
        context: Context,
        fragmentManager: FragmentManager,
        channelId: Long,
        onJoinVerified: () -> Unit,
    ) {
        when (VoiceChannelJoinabilityUtils.getJoinability(channelId)) {
            VoiceChannelJoinability.CAN_JOIN -> onJoinVerified()
            VoiceChannelJoinability.PERMISSIONS_MISSING ->
                AppToast.show(context, R.string.channel_locked)
            VoiceChannelJoinability.GUILD_VIDEO_AT_CAPACITY ->
                onGuildVideoAtCapacity?.invoke(context, fragmentManager)
                    ?: AppToast.show(context, R.string.unable_to_join_channel_full)
            VoiceChannelJoinability.CHANNEL_FULL ->
                AppToast.show(context, R.string.unable_to_join_channel_full)
            VoiceChannelJoinability.CHANNEL_DOES_NOT_EXIST ->
                AppToast.show(context, R.string.guild_settings_public_welcome_invalid_channel)
        }
    }
}
