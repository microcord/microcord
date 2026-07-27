package com.discord.widgets.guilds.invite

import android.os.Parcel
import android.os.Parcelable
import com.discord.api.channel.Channel
import com.discord.api.channel.getSortByNameAndType
import com.discord.api.channel.isGuildTextyChannel
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.permission.Permission
import com.discord.models.domain.ModelInvite
import com.discord.models.experiments.domain.Experiment
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreChannels
import com.discord.stores.StoreExperiments
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * **Stand-in for the reference's `com.discord.stores.StoreInviteSettings`.**
 *
 * That store does not exist in this project and `StoreStream` has no `getInviteSettings()`
 * accessor, so it cannot be added from `widgets/`. Every semantic field of the reference store is
 * reproduced here — the session-scoped [ModelInvite.Settings], the pending [InviteCode] subject,
 * invitable-channel derivation and the expiration experiment — so that moving this file to
 * `stores/StoreInviteSettings.kt` and publishing it on `StoreStream` is a rename, not a rewrite.
 *
 * Two reference behaviours are preserved deliberately:
 *
 * * [setInviteSettings] and [generateInvite] are `synchronized` on the instance, exactly as the
 *   reference is — `generateInvite` reads the current settings without a snapshot otherwise.
 * * [getInviteSettings] (the per-guild overload) is a *pure* function of the expiration
 *   experiment. It never reads the mutable session value; the reference uses it only to seed the
 *   session value from [handleGuildSelected].
 *
 * TODO(stores wave): move to `com.discord.stores.StoreInviteSettings`, construct it in
 *  `StoreStream`, call [handleGuildSelected] from the guild-selection handler, and delete
 *  [Companion.instance].
 */
class InviteSettingsSource(
    private val storeChannels: StoreChannels = StoreStream.getChannels(),
    private val storePermissions: StorePermissions = StoreStream.getPermissions(),
    private val storeExperiments: StoreExperiments = StoreStream.getExperiments(),
) {

    private var inviteSettings: ModelInvite.Settings = ModelInvite.Settings(ModelInvite.Settings.ONE_DAY)
    private val inviteSettingsSubject: BehaviorSubject<ModelInvite.Settings> =
        BehaviorSubject.create(inviteSettings)

    private val pendingInviteCodeSubject: BehaviorSubject<InviteCode?> =
        BehaviorSubject.create(null as InviteCode?)

    // region invite settings

    fun observeInviteSettings(): Observable<ModelInvite.Settings> =
        inviteSettingsSubject.computationLatest().distinctUntilChanged()

    @Synchronized
    fun setInviteSettings(settings: ModelInvite.Settings) {
        inviteSettings = settings
        inviteSettingsSubject.onNext(settings)
    }

    /**
     * The *default* settings for a guild — a pure function of the expiration experiment, with no
     * dependency on the session value. Bucket 1 defaults invites to seven days instead of one.
     */
    fun getInviteSettings(guildId: Long): ModelInvite.Settings =
        if (getInviteGuildExperiment(guildId)?.bucket == 1) {
            ModelInvite.Settings(ModelInvite.Settings.SEVEN_DAYS)
        } else {
            ModelInvite.Settings(ModelInvite.Settings.ONE_DAY)
        }

    @JvmOverloads
    fun getInviteGuildExperiment(guildId: Long, trackExposure: Boolean = false): Experiment? =
        storeExperiments.getGuildExperiment(EXPIRATION_EXPERIMENT, guildId, trackExposure)

    /** Resets the session settings to the guild's default. Reference: `handleGuildSelected`. */
    fun handleGuildSelected(guildId: Long) = setInviteSettings(getInviteSettings(guildId))

    // endregion

    // region invite generation

    @Synchronized
    @JvmOverloads
    fun generateInvite(
        channelId: Long,
        settings: ModelInvite.Settings = inviteSettings,
    ): Observable<ModelInvite> = RestAPI.api
        .postChannelInvite(
            channelId,
            RestAPIParams.Invite(settings.maxAge, settings.maxUses, settings.temporary, null),
        )
        .restSubscribeOn(false)

    /**
     * Generates an invite for the guild's first invitable channel, or emits `null` when the user
     * can invite to nothing in it.
     */
    fun generateInviteDefaultChannel(guildId: Long): Observable<ModelInvite?> =
        getInvitableChannels(guildId)
            .map { channels -> channels.values.minWithOrNull(Channel.getSortByNameAndType())?.id }
            .take(1)
            .flatMap { channelId ->
                if (channelId == null) Observable.just<ModelInvite?>(null)
                else generateInvite(channelId, getInviteSettings(guildId)).map { it as ModelInvite? }
            }

    /**
     * Channels of this guild the user may create an invite to: a text-y or vocal channel that is
     * both visible and carries `CREATE_INSTANT_INVITE`.
     */
    fun getInvitableChannels(guildId: Long): Observable<Map<Long, Channel>> = Observable.combineLatest<
        Map<Long, Channel>,
        Map<Long, Map<Long, Long>>,
        Map<Long, Channel>,
        >(
        storeChannels.observeGuildAndPrivateChannels(),
        storePermissions.observeAllPermissions(),
    ) { channels, permissions ->
        val invitable = LinkedHashMap<Long, Channel>()
        val guildPermissions = permissions[guildId] ?: return@combineLatest invitable
        for ((channelId, channelPermissions) in guildPermissions) {
            val channel = channels[channelId] ?: continue
            if (!channel.isInvitable()) continue
            if (!PermissionUtils.hasAccess(channel, channelPermissions)) continue
            if (!PermissionUtils.can(Permission.CREATE_INSTANT_INVITE, channelPermissions)) continue
            invitable[channelId] = channel
        }
        invitable
    }

    // endregion

    // region pending invite code

    /**
     * The invite code the user arrived on but has not resolved yet — set by the deep-link and
     * join-guild entry points, read by the analytics layer and by the invite-accept screen.
     */
    fun observeInviteCode(): Observable<InviteCode?> = pendingInviteCodeSubject.distinctUntilChanged()

    @JvmOverloads
    fun setInviteCode(inviteCode: String, source: String, eventId: Long? = null) =
        pendingInviteCodeSubject.onNext(InviteCode(inviteCode, source, eventId))

    fun clearInviteCode() = pendingInviteCodeSubject.onNext(null)

    // endregion

    data class InviteCode(
        val inviteCode: String,
        val source: String,
        val eventId: Long?,
    ) : Parcelable {

        constructor(parcel: Parcel) : this(
            inviteCode = parcel.readString().orEmpty(),
            source = parcel.readString().orEmpty(),
            eventId = if (parcel.readInt() != 0) parcel.readLong() else null,
        )

        override fun describeContents(): Int = 0

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeString(inviteCode)
            parcel.writeString(source)
            if (eventId != null) {
                parcel.writeInt(1)
                parcel.writeLong(eventId)
            } else {
                parcel.writeInt(0)
            }
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<InviteCode> = object : Parcelable.Creator<InviteCode> {
                override fun createFromParcel(parcel: Parcel) = InviteCode(parcel)
                override fun newArray(size: Int) = arrayOfNulls<InviteCode>(size)
            }
        }
    }

    companion object {
        const val LOCATION_DEEPLINK = "Deeplink"
        const val LOCATION_JOIN = "Join Guild Modal"

        private const val EXPIRATION_EXPERIMENT = "2021-03_android_extend_invite_expiration"

        /**
         * Process-wide instance, standing in for `StoreStream.getInviteSettings()`. The reference
         * holds exactly one `StoreInviteSettings` for the same reason: the pending invite code and
         * the session settings have to survive navigation between the screens that share them.
         */
        val instance: InviteSettingsSource by lazy { InviteSettingsSource() }
    }
}

/** Reference: `Channel.isInvitableChannel()` — a text-y or vocal guild channel. */
private fun Channel.isInvitable(): Boolean = isGuildTextyChannel() || isGuildVocalChannel()
