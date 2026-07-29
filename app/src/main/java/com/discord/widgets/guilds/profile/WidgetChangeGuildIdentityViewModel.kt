package com.discord.widgets.guilds.profile

import android.content.Context
import androidx.annotation.MainThread
import com.discord.api.guildmember.PatchGuildMemberBody
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.MeUser
import com.discord.nullserializable.NullSerializable
import com.discord.restapi.RestAPIInterface
import com.discord.stores.StoreStream
import com.discord.utilities.channel.GuildChannelsInfo
import com.discord.utilities.error.RestApiError
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils.isPremiumTier2
import rx.Observable
import rx.subjects.PublishSubject

class WidgetChangeGuildIdentityViewModel(
    val guildId: Long,
    val sourceSection: String,
    private val api: RestAPIInterface = RestAPI.api,
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId),
) : com.discord.app.AppViewModel<WidgetChangeGuildIdentityViewModel.ViewState>(ViewState.Loading) {

    private val eventSubject = PublishSubject.create<Event>()
    var trackedModalOpen = false
    var trackedUpsell = false

    init {
        storeStateObservable.ui(this).appSubscribe(WidgetChangeGuildIdentityViewModel::class.java) {
            handleStoreState(it)
        }
    }

    fun observeEvents(): Observable<Event> = eventSubject

    @MainThread
    private fun handleStoreState(state: StoreState) {
        val guild = state.guild ?: return
        val member = state.member ?: return
        val current = viewState as? ViewState.Loaded
        updateViewState(
            ViewState.Loaded(
                guild = guild,
                meUser = state.meUser,
                member = member,
                canChangeNickname = state.guildChannelsInfo.canChangeNickname,
                currentNickname = current?.currentNickname ?: member.nick,
                currentAvatar = current?.currentAvatar,
            ),
        )
    }

    @MainThread
    fun clearStatus() = updateLoaded { it.copy(currentNickname = "") }

    @MainThread
    fun updateNickname(nickname: String) = updateLoaded {
        if (nickname == it.currentNickname) it else it.copy(currentNickname = nickname)
    }

    @MainThread
    fun updateAvatar(avatar: String?) = updateLoaded {
        it.copy(currentAvatar = avatar?.let { value -> NullSerializable.Value(value) } ?: NullSerializable.Null())
    }

    @MainThread
    fun saveMemberChanges(context: Context) {
        val state = viewState as? ViewState.Loaded ?: return
        updateViewState(state.copy(dimmed = true))
        val originalNickname = state.member.nick.orEmpty()
        api.updateMeGuildMember(
            state.guild.id,
            PatchGuildMemberBody(
                nick = state.currentNickname.takeIf { it != originalNickname },
                avatar = state.currentAvatar,
            ),
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                WidgetChangeGuildIdentityViewModel::class.java,
                context = context,
                errorHandler = { error ->
                    eventSubject.onNext(Event.MemberUpdateFailed(error.firstMessage()))
                    updateViewState(state.copy(dimmed = false))
                },
            ) {
                eventSubject.onNext(Event.MemberUpdateSucceeded)
                updateViewState(state.copy(dimmed = false))
            }
    }

    private inline fun updateLoaded(transform: (ViewState.Loaded) -> ViewState.Loaded) {
        (viewState as? ViewState.Loaded)?.let { updateViewState(transform(it)) }
    }

    private fun RestApiError.firstMessage(): String? =
        messages.values.firstNotNullOfOrNull { it.firstOrNull() } ?: message

    sealed class Event {
        data class MemberUpdateFailed(val errorMessage: String?) : Event()
        object MemberUpdateSucceeded : Event()
    }

    data class StoreState(
        val guild: Guild?,
        val meUser: MeUser,
        val member: GuildMember?,
        val guildChannelsInfo: GuildChannelsInfo,
    )

    sealed class ViewState {
        object Loading : ViewState()

        data class Loaded(
            val guild: Guild,
            val meUser: MeUser,
            val member: GuildMember,
            val canChangeNickname: Boolean,
            val currentNickname: String? = null,
            val currentAvatar: NullSerializable<String>? = null,
            val dimmed: Boolean = false,
        ) : ViewState() {
            val isDirty = member.nick.orEmpty() != currentNickname || currentAvatar != null
            val showSaveFab = isDirty
            val displayedAvatarURL = when (currentAvatar) {
                is NullSerializable.Value -> currentAvatar.value
                is NullSerializable.Null -> IconUtils.getForUser(meUser, allowAnimation = true)
                null -> IconUtils.getForGuildMemberOrUser(meUser, member, allowAnimation = true)
            }
            val displayingGuildAvatar = when (currentAvatar) {
                is NullSerializable.Value -> true
                is NullSerializable.Null -> false
                null -> member.hasAvatar()
            }
            val shouldUpsell = !meUser.isPremiumTier2
        }
    }

    companion object {
        internal fun observeStoreState(guildId: Long): Observable<StoreState> = Observable.combineLatest(
            GuildChannelsInfo.get(guildId),
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getUsers().observeMe(),
            StoreStream.getUsers().observeMe().flatMap { me ->
                StoreStream.getGuilds().observeComputedMember(guildId, me.id)
            },
        ) { info, guild, me, member -> StoreState(guild, me, member, info) }
    }
}
