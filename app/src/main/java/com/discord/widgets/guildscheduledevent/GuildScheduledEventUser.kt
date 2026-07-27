package com.discord.widgets.guildscheduledevent

/**
 * One person who has RSVP'd to a guild scheduled event.
 *
 * The reference declares this class in this package. It currently lives in
 * `com.discord.stores` because `StoreGuildScheduledEvents` was reconstructed before this package
 * had any store-facing model, and that file is owned by the stores wave. This alias restores the
 * reference's name and location for every consumer; **delete it and move the declaration here**
 * when the store file can be edited in the same change.
 */
typealias GuildScheduledEventUser = com.discord.stores.GuildScheduledEventUser
