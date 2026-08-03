package com.discord.activities

import android.content.Context
import android.content.Intent

object EmbeddedActivityLauncher {
    @JvmStatic
    fun launch(
        context: Context,
        applicationId: Long,
        channelId: Long? = null,
        instanceId: String? = null,
        customId: String? = null,
        referrerId: String? = null,
    ) {
        context.startActivity(
            Intent(context, EmbeddedActivityActivity::class.java)
                .putExtra(EmbeddedActivityActivity.EXTRA_APPLICATION_ID, applicationId)
                .putExtra(EmbeddedActivityActivity.EXTRA_CHANNEL_ID, channelId ?: 0L)
                .putExtra(EmbeddedActivityActivity.EXTRA_INSTANCE_ID, instanceId)
                .putExtra(EmbeddedActivityActivity.EXTRA_CUSTOM_ID, customId)
                .putExtra(EmbeddedActivityActivity.EXTRA_REFERRER_ID, referrerId),
        )
    }
}
