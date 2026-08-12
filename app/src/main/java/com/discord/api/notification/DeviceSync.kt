package com.discord.api.notification

import com.google.gson.annotations.SerializedName

/**
 * Lets one device registration deliver to several accounts.
 *
 * The device token belongs to the install, not to whoever is signed in, so registering it again for
 * a second account would simply move it. Instead each account mints a sync token with its own
 * credentials and the active account submits the whole set, which is what allows an account sitting
 * in the background to keep receiving notifications.
 */
data class PushSyncToken(
    @SerializedName("push_sync_token")
    val pushSyncToken: String,
)

data class DeviceSyncResponse(
    /** Sync tokens the server no longer accepts; they have to be minted again. */
    @SerializedName("invalid_push_sync_tokens")
    val invalidPushSyncTokens: List<String>? = null,
)
