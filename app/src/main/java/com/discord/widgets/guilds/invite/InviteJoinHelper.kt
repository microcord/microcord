package com.discord.widgets.guilds.invite

import com.discord.api.channel.Channel
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.channel.isVoiceChannel
import com.discord.api.guild.GuildFeature
import com.discord.api.guildjoinrequest.GuildJoinRequest
import com.discord.app.AppFragment
import com.discord.models.domain.ModelInvite
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.captcha.CaptchaHelper
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.filterNull
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.takeSingleUntilTimeout
import com.discord.utilities.rx.ui
import com.discord.widgets.servers.member_verification.WidgetMemberVerification
import rx.Observable

/**
 * Accepting an invite: `POST /invites/{code}`, then landing the user where the invite points.
 *
 * ## Divergence from the reference — read before changing
 *
 * The reference implementation is a **single 1052-instruction `suspend` state machine**
 * (`InviteJoinHelper$joinViaInvite$4.invokeSuspend`) that **neither Vineflower nor JADX can
 * decompile**, and this project has no `AppCoroutineScopeKt` to host it. What is reproduced here is
 * the part that *is* readable — the REST call, the callback triad, and the two navigation
 * helpers, which do decompile cleanly — restated over RxJava 1 callbacks.
 *
 * The reference checks the membership gate before ordinary navigation and abandons that navigation
 * when the gate owns the flow. This target must wait for the invite POST's gateway result first, so
 * it races channel hydration against `GUILD_JOIN_REQUEST_CREATE`; whichever arrives first owns the
 * route. Microphone permission remains the final gate before entering a vocal channel.
 *
 * The *ordering* of those two relative to the navigation calls is inference from the field order in
 * the state machine, not a read fact.
 */
object InviteJoinHelper {

    /**
     * @param onInvitePostError fires before any navigation; the accept screen uses it to render the
     *   failure in place.
     * @param onInvitePostSuccess fires as soon as the server accepts, before the gate or the
     *   permission prompt.
     * @param onInviteFlowFinished fires once the user has actually been placed somewhere.
     */
    @JvmOverloads
    fun joinViaInvite(
        invite: ModelInvite,
        javaClass: Class<*>,
        fragment: AppFragment,
        location: String,
        onInvitePostError: (RestApiError?) -> Unit = {},
        onInvitePostSuccess: (ModelInvite) -> Unit = {},
        onInviteFlowFinished: (ModelInvite) -> Unit = {},
        captchaPayload: CaptchaHelper.CaptchaPayload? = null,
    ) {
        val code = invite.code
        if (code == null) {
            onInvitePostError(null)
            return
        }

        RestAPI.api
            .postInviteCode(
                code,
                RestAPIParams.InviteCode(captchaPayload?.captchaKey, captchaPayload?.captchaRqtoken),
                location,
            )
            .restSubscribeOn(false)
            .ui(fragment)
            .appSubscribe(
                javaClass,
                context = fragment.context,
                errorHandler = { error -> onInvitePostError(error) },
            ) { joinedInvite ->
                onInvitePostSuccess(joinedInvite)
                completeJoin(fragment, joinedInvite, location, onInviteFlowFinished)
            }
    }

    private fun completeJoin(
        fragment: AppFragment,
        invite: ModelInvite,
        location: String,
        onInviteFlowFinished: (ModelInvite) -> Unit,
    ) {
        val channel = invite.channel
        if (channel == null) {
            navigateToGuild(invite)
            onInviteFlowFinished(invite)
            return
        }

        val guild = invite.guild
        val joinRequests = if (
            guild != null &&
            GuildFeature.MEMBER_VERIFICATION_GATE_ENABLED in guild.features &&
            GuildFeature.COMMUNITY in guild.features
        ) {
            StoreStream.getGuildJoinRequests().observeGuildJoinRequest(guild.id)
        } else {
            Observable.never<GuildJoinRequest?>()
        }
        raceJoinOutcome(
            StoreStream.getChannels().observeChannel(channel.id),
            joinRequests,
            StoreStream.getUsers().getMe().id,
        )
            .takeSingleUntilTimeout()
            .ui(fragment)
            .appSubscribe(
                errorClass = InviteJoinHelper::class.java,
                errorHandler = { onInviteFlowFinished(invite) },
            ) { (resolvedChannel, joinRequest) ->
                if (joinRequest != null && guild != null) {
                    WidgetMemberVerification.create(fragment.requireContext(), guild.id, location, invite)
                    return@appSubscribe
                }
                if (resolvedChannel == null) {
                    onInviteFlowFinished(invite)
                    return@appSubscribe
                }
                invite.channel = resolvedChannel
                navigateToJoinedChannel(fragment, invite, onInviteFlowFinished)
            }
    }

    internal fun raceJoinOutcome(
        channels: Observable<Channel?>,
        joinRequests: Observable<GuildJoinRequest?>,
        currentUserId: Long,
    ): Observable<Pair<Channel?, GuildJoinRequest?>> = Observable.amb(
        channels.filterNull().map { Pair<Channel?, GuildJoinRequest?>(it, null) },
        joinRequests.filterNull()
            .filter { it.userId == currentUserId }
            .map { Pair<Channel?, GuildJoinRequest?>(null, it) },
    ).take(1)

    private fun navigateToJoinedChannel(
        fragment: AppFragment,
        invite: ModelInvite,
        onInviteFlowFinished: (ModelInvite) -> Unit,
    ) {
        val channel = invite.channel ?: return onInviteFlowFinished(invite)
        navigateToGuild(invite)
        if (channel.isVoiceChannel() || channel.isStageVoiceChannel()) {
            fragment.requestMicrophone(onFailure = { onInviteFlowFinished(invite) }) {
                navigateToChannel(invite)
                onInviteFlowFinished(invite)
            }
        } else {
            navigateToChannel(invite)
            onInviteFlowFinished(invite)
        }
    }

    /**
     * Selects the invited guild and queues the scheduled-event sheet when the link carried one.
     *
     * The event sheet is *enqueued* rather than shown: the guild switch has not finished laying out
     * yet, and the reference has the same ordering problem.
     */
    private fun navigateToGuild(invite: ModelInvite) {
        invite.guild?.id
            ?.takeIf { StoreStream.getGuilds().getGuild(it) != null }
            ?.let { StoreStream.getGuildSelected().set(it) }
        // Reference: WidgetGuildScheduledEventDetailsBottomSheet.enqueue(event.id). That screen is
        // not reconstructed; the invite still lands on the guild.
    }

    /**
     * Selects the invited channel and, for a vocal one, opens the corresponding call surface.
     *
     * The voice and stage surfaces (`WidgetCallPreviewFullscreen`, `WidgetVoiceBottomSheet`,
     * `StageChannelJoinHelper`) are not reconstructed, so a vocal invite currently selects the
     * channel and stops there rather than connecting.
     */
    private fun navigateToChannel(invite: ModelInvite) {
        val channel: Channel = invite.channel ?: return
        StoreStream.selectChannel(channel.guildId, channel.id)
    }
}
