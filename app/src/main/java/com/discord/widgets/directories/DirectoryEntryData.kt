package com.discord.widgets.directories

import com.discord.api.directory.DirectoryEntryGuild

/**
 * One server listed in a hub directory, joined with the two facts the row needs about the viewer.
 *
 * The API returns [entry] alone; whether the viewer has already joined the server and whether they
 * may edit its listing come from `StoreGuilds`/`StorePermissions` and are folded in by the view
 * model so the row itself stays a pure render.
 */
data class DirectoryEntryData(
    val entry: DirectoryEntryGuild,
    val hasJoinedGuild: Boolean,
    val hasEditPermissions: Boolean,
)
