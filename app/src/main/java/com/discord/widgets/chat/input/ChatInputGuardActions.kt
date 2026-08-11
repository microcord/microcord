package com.discord.widgets.chat.input

import android.content.Context
import androidx.fragment.app.FragmentManager
import com.discord.api.guild.GuildVerificationLevel

/**
 * The four things a chat-input guard's button can do, none of which has a destination in this
 * project yet.
 *
 * Each guard explains *why* the composer is hidden — that part is real and works. What is missing
 * is the screen the button would open:
 *
 * | Action | Reference destination | Status here |
 * | --- | --- | --- |
 * | [joinGuildAsMember] | `StoreLurking.postJoinGuildAsMember` | the store exists but `StoreStream` never constructs it |
 * | [showFollowSheet] | `WidgetChannelFollowSheet` | not reconstructed |
 * | [launchAccountVerification] | `WidgetUserEmailVerify` / `WidgetUserPhoneManage` | not reconstructed |
 * | [showMemberVerificationGate] | `MemberVerificationUtils.maybeShowVerificationGate` | reconstructed; `WidgetChatInput` routes the guard itself |
 *
 * They are nullable rather than no-op lambdas on purpose: the guard **hides the button** when the
 * action is unset, instead of showing a control that does nothing when pressed. Install one and the
 * button appears with no other change.
 */
object ChatInputGuardActions {

    /** Converts a lurking membership into a real one, then re-enters the channel. */
    @JvmStatic
    var joinGuildAsMember: ((guildId: Long, onSuccess: () -> Unit, onFailure: () -> Unit) -> Unit)? = null

    /** Opens the "follow this announcement channel into a server you own" sheet. */
    @JvmStatic
    var showFollowSheet: ((FragmentManager, channelId: Long, guildId: Long) -> Unit)? = null

    /**
     * Sends the user to whichever verification flow [GuildVerificationLevel] demands:
     * e-mail for `LOW`, phone for `HIGHEST`. `MEDIUM` and `HIGH` are waiting periods with nothing
     * to launch, and the reference shows no button for them either.
     */
    @JvmStatic
    var launchAccountVerification: ((Context, GuildVerificationLevel) -> Unit)? = null

}
