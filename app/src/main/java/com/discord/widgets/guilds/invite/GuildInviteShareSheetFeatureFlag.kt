package com.discord.widgets.guilds.invite

import com.discord.stores.StoreExperiments
import com.discord.stores.StoreStream

/**
 * Whether the invite flow opens as a bottom sheet instead of a full screen.
 *
 * Exposure is tracked on every read, which is deliberate: the flag is only read at the moment the
 * user actually asks for the invite UI, so a read *is* an exposure.
 */
class GuildInviteShareSheetFeatureFlag(
    private val storeExperiments: StoreExperiments = StoreStream.getExperiments(),
) {

    fun isEnabled(): Boolean =
        storeExperiments.getUserExperiment(EXPERIMENT_NAME, trackExposure = true)?.bucket == 1

    companion object {
        private const val EXPERIMENT_NAME = "2020-12_android_guild_channel_invite_sheet"

        val INSTANCE: GuildInviteShareSheetFeatureFlag by lazy { GuildInviteShareSheetFeatureFlag() }
    }
}
