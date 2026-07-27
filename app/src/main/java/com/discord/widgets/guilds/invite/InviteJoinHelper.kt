package com.discord.widgets.guilds.invite

import com.discord.api.channel.Channel
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.channel.isVoiceChannel
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.models.domain.ModelInvite
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.captcha.CaptchaHelper
import com.discord.utilities.error.RestApiError
import com.discord.utilities.guilds.MemberVerificationUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui

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
 * Two suspension points in the reference are visible only through their continuation lambdas, which
 * resume with `false` on dismissal and `true` on completion:
 *
 * * `hasCompletedMembershipGate` — the rules gate. `MemberVerificationUtils.maybeShowVerificationGate`
 *   does not exist in this project (`WidgetGuildJoinRequest` was never reconstructed), so a guild
 *   with a gate is joined and navigated to without showing it. **Marked, not guessed.**
 * * `hasMicrophonePermissions` — asked before entering a vocal channel. Reproduced through
 *   [AppFragment.requestMicrophone].
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
                completeJoin(fragment, joinedInvite, onInviteFlowFinished)
            }
    }

    private fun completeJoin(
        fragment: AppFragment,
        invite: ModelInvite,
        onInviteFlowFinished: (ModelInvite) -> Unit,
    ) {
        // The reference suspends here on `hasCompletedMembershipGate` and abandons the flow when
        // the gate is dismissed. `MemberVerificationUtils.maybeShowVerificationGate` and
        // `WidgetGuildJoinRequest` are unreconstructed, so a gated guild currently proceeds
        // straight through. When the gate screen lands, this becomes:
        //     if (MemberVerificationUtils.hasVerificationGate(guild)) { showGate(...); return }
        val guild = invite.guild?.id?.let { StoreStream.getGuilds().getGuild(it) }
        if (MemberVerificationUtils.hasVerificationGate(guild)) {
            AppLog.i("Invite guild ${guild?.id} has a membership gate; no gate screen exists yet.")
        }

        navigateToGuild(invite)

        val channel = invite.channel
        if (channel != null && (channel.isVoiceChannel() || channel.isStageVoiceChannel())) {
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
        invite.guild?.id?.let { StoreStream.getGuildSelected().set(it) }
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
