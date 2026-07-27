package com.discord.widgets.channels.invite

import com.discord.stores.StoreExperiments
import com.discord.stores.StoreStream

class GroupInviteFriendsSheetFeatureFlag @JvmOverloads constructor(
    private val storeExperiments: StoreExperiments = StoreStream.getExperiments(),
) {
    fun isEnabled(): Boolean =
        storeExperiments.getUserExperiment(EXPERIMENT_NAME, true)?.bucket == ENABLED_BUCKET

    companion object {
        private const val EXPERIMENT_NAME = "2020-12_invite_to_gdm"
        private const val ENABLED_BUCKET = 1

        val INSTANCE: GroupInviteFriendsSheetFeatureFlag by lazy(::GroupInviteFriendsSheetFeatureFlag)
    }
}
