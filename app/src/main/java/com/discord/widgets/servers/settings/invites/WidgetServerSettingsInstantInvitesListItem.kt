package com.discord.widgets.servers.settings.invites

import android.os.CountDownTimer
import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.models.domain.ModelInvite
import com.discord.models.user.CoreUser
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.views.user.SettingsMemberView
import java.util.Locale

class WidgetServerSettingsInstantInvitesListItem(
    adapter: WidgetServerSettingsInstantInvites.Adapter,
) : MGRecyclerViewHolder<
    WidgetServerSettingsInstantInvites.Adapter,
    WidgetServerSettingsInstantInvites.Model.InviteItem,
>(R.layout.widget_server_settings_instant_invite_list_item, adapter) {

    private val code: TextView = itemView.findViewById(R.id.invite_code)
    private val channelName: TextView = itemView.findViewById(R.id.invite_channel)
    private val expirationTime: TextView = itemView.findViewById(R.id.invite_expiration_time)
    private val uses: TextView = itemView.findViewById(R.id.invite_uses)
    private val member: SettingsMemberView = itemView.findViewById(R.id.settings_member)
    private var countdownTimer: CountDownTimer? = null
    private var currentInviteCode: String? = null

    override fun onConfigure(
        position: Int,
        data: WidgetServerSettingsInstantInvites.Model.InviteItem,
    ) {
        super.onConfigure(position, data)
        val invite = data.invite
        val inviter = invite.getInviter()
        itemView.setOnClickListener { adapter.onInviteSelected(invite) }
        code.text = invite.code
        uses.text = getUsesText(invite)
        channelName.setTextAndVisibilityBy(
            invite.channel?.getDisplayNameOrDefault(channelName.context, includePrefix = true),
        )
        member.visibility = if (inviter == null) View.GONE else View.VISIBLE
        member.showIdentitySubtitle = false
        if (inviter != null) member.configure(CoreUser(inviter), data.guildMember)
        setupExpirationTime(invite)
    }

    private fun getUsesText(invite: ModelInvite): String = if (invite.getMaxUses() > 0) {
        "${invite.getUses()} / ${invite.getMaxUses()}"
    } else {
        invite.getUses().toString()
    }

    private fun setupExpirationTime(invite: ModelInvite) {
        when {
            invite.getMaxAge() == 0 -> {
                expirationTime.setText(R.string.max_age_never)
                expirationTime.setTextColor(ColorCompat.getColor(expirationTime, R.color.grey_1))
                cancelTimer()
            }
            invite.getTimeToExpirationMillis() <= 0L -> {
                setCountdownText(0L)
                cancelTimer()
            }
            invite.code != currentInviteCode || currentInviteCode == null -> {
                val timeToExpiration = invite.getTimeToExpirationMillis()
                setCountdownText(timeToExpiration)
                cancelTimer()
                currentInviteCode = invite.code
                countdownTimer = object : CountDownTimer(timeToExpiration, 1_000L) {
                    override fun onTick(millisUntilFinished: Long) =
                        setCountdownText(millisUntilFinished)

                    override fun onFinish() {
                        setCountdownText(0L)
                        adapter.onInviteExpired(invite)
                    }
                }.also { it.start() }
            }
        }
    }

    private fun setCountdownText(timeToExpiration: Long) {
        expirationTime.text = String.format(
            Locale.getDefault(),
            "%02d:%02d:%02d:%02d",
            timeToExpiration / 86_400_000L % 24,
            timeToExpiration / 3_600_000L % 24,
            timeToExpiration / 60_000L % 60,
            timeToExpiration / 1_000L % 60,
        )
        expirationTime.setTextColor(
            ColorCompat.getColor(expirationTime, R.color.status_green_500_dark),
        )
    }

    private fun cancelTimer() {
        countdownTimer?.cancel()
        countdownTimer = null
        currentInviteCode = null
    }
}
