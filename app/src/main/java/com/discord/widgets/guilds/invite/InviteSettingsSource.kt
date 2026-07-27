package com.discord.widgets.guilds.invite

import com.discord.stores.StoreInviteSettings

internal object InviteSettingsSource {
    val store: StoreInviteSettings by lazy(::StoreInviteSettings)
}
