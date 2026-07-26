package com.discord.widgets.stage

import com.discord.stores.StoreLurking
import com.discord.stores.StoreRequestedStageChannels
import com.discord.stores.StoreStageChannels
import com.discord.stores.StoreStream

/**
 * The stage UI's access point to the three stage-related stores `StoreStream` builds but does not
 * yet publish.
 *
 * `StoreStream` constructs [StoreStageChannels], [StoreRequestedStageChannels] and [StoreLurking],
 * but only exposes `getStageInstances()` of the stage family. `StoreStream.kt` is owned by another
 * agent this wave, so the graph installs the remaining three here instead — the same seam the voice
 * slice uses in `com.discord.widgets.voice.VoiceStoreAccess`.
 *
 * **Required wiring:** `StoreStream` must call
 * `StageStoreAccess.install(stageChannels, requestedStageChannels, lurking)` while building the
 * graph — or, better, simply add
 *
 * ```
 * @JvmStatic fun getStageChannels() = requireInstance().stageChannels
 * @JvmStatic fun getRequestedStageChannels() = requireInstance().requestedStageChannels
 * @JvmStatic fun getLurking() = requireInstance().lurking
 * ```
 *
 * and delete this file, repointing the three `require*` calls below.
 *
 * Every stage surface is gated on [isInstalled]; until the wiring lands they render their empty
 * state rather than crashing.
 */
object StageStoreAccess {

    @Volatile
    private var stageChannels: StoreStageChannels? = null

    @Volatile
    private var requestedStageChannels: StoreRequestedStageChannels? = null

    @Volatile
    private var lurking: StoreLurking? = null

    /** Whether `StoreStream` has installed the stage stores. */
    @JvmStatic
    val isInstalled: Boolean get() = stageChannels != null

    @JvmStatic
    fun install(
        stageChannels: StoreStageChannels,
        requestedStageChannels: StoreRequestedStageChannels,
        lurking: StoreLurking,
    ) {
        this.stageChannels = stageChannels
        this.requestedStageChannels = requestedStageChannels
        this.lurking = lurking
    }

    @JvmStatic
    fun getStageChannelsOrNull(): StoreStageChannels? = stageChannels

    /**
     * The stage-roles store. Only call from a surface already gated on [isInstalled] — a stage
     * screen the user could not have reached otherwise.
     */
    @JvmStatic
    fun requireStageChannels(): StoreStageChannels = checkNotNull(stageChannels) {
        "StageStoreAccess.install() must be called from StoreStream before any stage surface opens"
    }

    @JvmStatic
    fun requireRequestedStageChannels(): StoreRequestedStageChannels =
        checkNotNull(requestedStageChannels) {
            "StageStoreAccess.install() must be called from StoreStream before any stage surface opens"
        }

    @JvmStatic
    fun requireLurking(): StoreLurking = checkNotNull(lurking) {
        "StageStoreAccess.install() must be called from StoreStream before any stage surface opens"
    }

    /**
     * `StoreStream` already publishes this one; routed through here so every stage caller reads
     * from one place and the eventual cleanup is mechanical.
     */
    @JvmStatic
    fun stageInstances() = StoreStream.getStageInstances()
}
