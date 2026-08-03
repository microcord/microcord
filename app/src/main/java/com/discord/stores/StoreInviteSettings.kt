package com.discord.stores

import android.os.Parcel
import android.os.Parcelable
import com.discord.api.channel.Channel
import com.discord.api.channel.getSortByNameAndType
import com.discord.api.channel.isGuildTextyChannel
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.permission.Permission
import com.discord.models.domain.ModelInvite
import com.discord.restapi.RestAPIParams
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.takeSingleUntilTimeout
import rx.Observable
import rx.subjects.BehaviorSubject

class StoreInviteSettings : Store() {
    private var inviteSettings = ModelInvite.Settings(ModelInvite.Settings.SEVEN_DAYS)
    private val pendingInviteCodeSubject = BehaviorSubject.create(null as InviteCode?)
    private val inviteSettingsSubject = BehaviorSubject.create(inviteSettings)

    fun clearInviteCode() = pendingInviteCodeSubject.onNext(null)

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

    fun generateInviteDefaultChannel(guildId: Long): Observable<ModelInvite?> =
        getInvitableChannels(guildId)
            .map { channels -> channels.values.sortedWith(Channel.getSortByNameAndType()).firstOrNull()?.id }
            .take(1)
            .flatMap { channelId ->
                channelId?.let { generateInvite(it).map { invite -> invite as ModelInvite? } }
                    ?: Observable.just<ModelInvite?>(null)
            }

    fun getInvitableChannels(guildId: Long): Observable<Map<Long, Channel>> = Observable.combineLatest(
        StoreStream.getChannels().observeGuildAndPrivateChannels(),
        StoreStream.getPermissions().observeAllPermissions(),
    ) { channels, permissions ->
        val result = HashMap<Long, Channel>()
        permissions[guildId].orEmpty().forEach { (channelId, channelPermissions) ->
            val channel = channels[channelId] ?: return@forEach
            if ((channel.isGuildTextyChannel() || channel.isGuildVocalChannel()) &&
                PermissionUtils.hasAccess(channel, channelPermissions) &&
                PermissionUtils.can(Permission.CREATE_INSTANT_INVITE, channelPermissions)
            ) {
                result[channelId] = channel
            }
        }
        result
    }

    fun getInvite(): Observable<ModelInvite?> = getInviteCode()
        .switchMap { inviteCode ->
            inviteCode?.let {
                StoreStream.getInstantInvites().observeInvite(
                    ModelInvite.getInviteStoreKey(it.inviteCode, it.eventId),
                )
            } ?: Observable.just<StoreInstantInvites.InviteState?>(null)
        }
        .map { state -> (state as? StoreInstantInvites.InviteState.Resolved)?.invite }

    fun getInviteCode(): Observable<InviteCode?> = pendingInviteCodeSubject.distinctUntilChanged()

    fun getInviteSettings(): Observable<ModelInvite.Settings> =
        inviteSettingsSubject.computationLatest().distinctUntilChanged()

    fun handleGuildSelected(guildId: Long) = setInviteSettings(ModelInvite.Settings(ModelInvite.Settings.SEVEN_DAYS))

    @JvmOverloads
    fun setInviteCode(inviteCode: String, source: String, eventId: Long? = null) =
        pendingInviteCodeSubject.onNext(InviteCode(inviteCode, source, eventId))

    @Synchronized
    fun setInviteSettings(settings: ModelInvite.Settings) {
        inviteSettings = settings
        inviteSettingsSubject.onNext(settings)
    }

    internal fun trackWithInvite(clazz: Class<*>, trackBlock: (ModelInvite?) -> Unit) {
        getInvite()
            .filter { it != null }
            .map { it!! }
            .takeSingleUntilTimeout(250L, false)
            .computationLatest()
            .appSubscribe(
                errorClass = clazz,
                errorHandler = { trackBlock(null) },
                onNext = trackBlock,
            )
    }

    data class InviteCode(
        val inviteCode: String,
        val source: String,
        val eventId: Long?,
    ) : Parcelable {
        private constructor(parcel: Parcel) : this(
            parcel.readString()!!,
            parcel.readString()!!,
            if (parcel.readInt() != 0) parcel.readLong() else null,
        )

        override fun describeContents() = 0

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeString(inviteCode)
            parcel.writeString(source)
            eventId?.let {
                parcel.writeInt(1)
                parcel.writeLong(it)
            } ?: parcel.writeInt(0)
        }

        companion object CREATOR : Parcelable.Creator<InviteCode> {
            override fun createFromParcel(parcel: Parcel) = InviteCode(parcel)
            override fun newArray(size: Int): Array<InviteCode?> = arrayOfNulls(size)
        }
    }

    companion object {
        const val LOCATION_DEEPLINK = "Deeplink"
        const val LOCATION_JOIN = "Join Guild Modal"
    }
}
