package com.discord.widgets.stage

import com.discord.stores.StoreLurking
import com.discord.stores.StoreRequestedStageChannels
import com.discord.stores.StoreStageChannels
import com.discord.stores.StoreStream
import rx.Observable

/**
 * The stage UI's single access point to the stage stores.
 *
 * `StoreStream` now owns and publishes [StoreStageChannels], [StoreRequestedStageChannels] and
 * `getStageChannelSelfPresence()`, so those are read straight through and every stage surface is
 * live. The one remaining hole is [StoreLurking]: the class exists in `stores/` but `StoreStream`
 * neither constructs nor exposes it, so nothing can supply an instance.
 *
 * Rather than throw — which is what an earlier `require`-shaped version of this object did on every
 * stage screen — lurking degrades honestly: [observeIsLurking] reports `false`, meaning "this user
 * is a full member of the guild". The consequence is confined and documented at each call site:
 * the stage model never renders the lurker variant, and [StageChannelJoinHelper.lurkAndJoinStage]
 * cannot join a stage in a guild the user has not joined.
 *
 * **Required wiring:** when `StoreStream` gains `getLurking()`, delete [install] / [lurkingOrNull]
 * and point [observeIsLurking] at it. That single change makes lurking live everywhere.
 */
object StageStoreAccess {

    @Volatile
    private var lurking: StoreLurking? = null

    /** Whether guild lurking is available. `false` until `StoreStream` publishes [StoreLurking]. */
    @JvmStatic
    val isLurkingAvailable: Boolean get() = lurking != null

    /** Installs the lurking store. Delete once `StoreStream` publishes it directly. */
    @JvmStatic
    fun install(lurking: StoreLurking) {
        this.lurking = lurking
    }

    @JvmStatic
    fun stageChannels(): StoreStageChannels = StoreStream.getStageChannels()

    @JvmStatic
    fun requestedStageChannels(): StoreRequestedStageChannels =
        StoreStream.getRequestedStageChannels()

    @JvmStatic
    fun lurkingOrNull(): StoreLurking? = lurking

    /**
     * Whether the user is only lurking in [guildId]. Without a lurking store this is `false`, which
     * is the reading that grants the *most* UI — a lurker sees a reduced stage — so the degradation
     * is visible as missing "join this server" prompts rather than as a wrongly restricted screen.
     */
    @JvmStatic
    fun observeIsLurking(guildId: Long): Observable<Boolean> =
        lurking?.isLurkingObs(guildId) ?: Observable.just(false)
}
