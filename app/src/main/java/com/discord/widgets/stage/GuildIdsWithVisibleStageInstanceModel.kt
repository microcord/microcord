package com.discord.widgets.stage

import com.discord.api.permission.Permission
import com.discord.api.stageinstance.StageInstance
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStageInstances
import com.discord.stores.StoreStream
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.permissions.PermissionUtils
import rx.Observable

/**
 * The guilds whose live stages the current user is actually allowed to see.
 *
 * A guild qualifies when at least one of its stage instances sits in a channel the user may
 * [Permission.VIEW_CHANNEL]. The guild list uses this to decide which servers get a "live" badge.
 */
object GuildIdsWithVisibleStageInstanceModel {

    fun compute(
        instancesByGuild: Map<Long, Map<Long, StageInstance>>,
        permissionsByChannel: Map<Long, Long>,
    ): Set<Long> = instancesByGuild
        .filterValues { instancesByChannel ->
            instancesByChannel.keys.any { channelId ->
                PermissionUtils.can(Permission.VIEW_CHANNEL, permissionsByChannel[channelId])
            }
        }
        .keys

    @JvmOverloads
    fun observe(
        storeStageInstances: StoreStageInstances = StoreStream.getStageInstances(),
        storePermissions: StorePermissions = StoreStream.getPermissions(),
        observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    ): Observable<Set<Long>> =
        observationDeck.connectRx(storeStageInstances, storePermissions) {
            compute(
                storeStageInstances.getStageInstancesByGuild(),
                storePermissions.getPermissionsByChannel(),
            )
        }
}
