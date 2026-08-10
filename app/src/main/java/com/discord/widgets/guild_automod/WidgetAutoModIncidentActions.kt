package com.discord.widgets.guild_automod

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppToast
import com.discord.databinding.WidgetAutomodIncidentActionsBinding
import com.discord.nullserializable.NullSerializable
import com.discord.restapi.RestAPIParams
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.time.TimeUtils

class WidgetAutoModIncidentActions : AppBottomSheet() {
    private var _binding: WidgetAutomodIncidentActionsBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun getContentViewResId() = R.layout.widget_automod_incident_actions

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = WidgetAutomodIncidentActionsBinding.bind(view)
        binding.pauseInvites.setOnClickListener {
            update("pause_invites", RestAPIParams.GuildIncidentActions(invitesDisabledUntil = untilTomorrow()))
        }
        binding.pauseDms.setOnClickListener {
            update("pause_dms", RestAPIParams.GuildIncidentActions(dmsDisabledUntil = untilTomorrow()))
        }
        binding.resolveAlert.setOnClickListener {
            update(
                "resolve",
                RestAPIParams.GuildIncidentActions(
                    invitesDisabledUntil = NullSerializable.Null(),
                    dmsDisabledUntil = NullSerializable.Null(),
                ),
            )
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun update(action: String, actions: RestAPIParams.GuildIncidentActions) {
        binding.pauseInvites.isEnabled = false
        binding.pauseDms.isEnabled = false
        binding.resolveAlert.isEnabled = false
        RestAPI.api.setGuildIncidentActions(requireArguments().getLong(ARG_GUILD_ID), actions)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                javaClass,
                errorHandler = { setEnabled(true) },
            ) {
                AnalyticsTracker.guildAutoModIncidentAction(
                    requireArguments().getLong(ARG_GUILD_ID),
                    requireArguments().getLong(ARG_MESSAGE_ID),
                    action,
                )
                AppToast.show(requireContext(), R.string.guild_automod_incident_action_applied)
                dismiss()
            }
    }

    private fun setEnabled(enabled: Boolean) {
        if (_binding == null) return
        binding.pauseInvites.isEnabled = enabled
        binding.pauseDms.isEnabled = enabled
        binding.resolveAlert.isEnabled = enabled
    }

    private fun untilTomorrow(): NullSerializable<String> = NullSerializable.Value(
        requireNotNull(TimeUtils.toUTCDateTime(System.currentTimeMillis() + DAY_MS)),
    )

    companion object {
        private const val ARG_GUILD_ID = "ARG_GUILD_ID"
        private const val ARG_MESSAGE_ID = "ARG_MESSAGE_ID"
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        fun show(fragmentManager: FragmentManager, guildId: Long, messageId: Long) {
            WidgetAutoModIncidentActions().apply {
                arguments = Bundle().apply {
                    putLong(ARG_GUILD_ID, guildId)
                    putLong(ARG_MESSAGE_ID, messageId)
                }
            }.show(fragmentManager, WidgetAutoModIncidentActions::class.java.name)
        }
    }
}
