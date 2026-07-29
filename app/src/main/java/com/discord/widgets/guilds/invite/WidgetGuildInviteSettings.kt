package com.discord.widgets.guilds.invite

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.fragment.app.DialogFragment
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.models.domain.ModelInvite
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.views.CheckedSetting
import com.google.android.material.button.MaterialButton

class WidgetGuildInviteSettings : AppFragment(R.layout.widget_guild_invite_settings) {

    private val viewModel by appViewModels {
        val channelId = (arguments?.getLong(EXTRA_CHANNEL_ID)
            ?: mostRecentIntent.getLongExtra(EXTRA_CHANNEL_ID, 0L)).takeIf { it != 0L }
        val guildId = arguments?.getLong(EXTRA_GUILD_ID)
            ?: mostRecentIntent.getLongExtra(EXTRA_GUILD_ID, 0L)
        GuildInviteSettingsViewModel(channelId, guildId)
    }
    private lateinit var channelsAdapter: ChannelsSpinnerAdapter
    private lateinit var spinner: Spinner
    private lateinit var expires: RadioGroup
    private lateinit var maxUses: RadioGroup
    private lateinit var temporary: CheckedSetting

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        spinner = view.findViewById(R.id.guild_invite_channel_spinner)
        expires = view.findViewById(R.id.guild_invite_expires_after_radiogroup)
        maxUses = view.findViewById(R.id.guild_invite_max_uses_radiogroup)
        temporary = view.findViewById(R.id.guild_invite_temporary_membership)
        channelsAdapter = ChannelsSpinnerAdapter(requireContext())
        spinner.adapter = channelsAdapter
        createButtons(expires, ModelInvite.Settings.EXPIRES_AFTER_ARRAY) { formatInviteExpireAfterString(requireContext(), it) }
        createButtons(maxUses, ModelInvite.Settings.MAX_USES_ARRAY) { if (it == 0) "∞" else it.toString() }
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                viewModel.selectChannel(channelsAdapter.getItem(position))
            }
        }
        view.findViewById<MaterialButton>(R.id.guild_invite_generate_link).setOnClickListener {
            viewModel.saveInviteSettings()
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().distinctUntilChanged().bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildInviteSettings::class.java, onNext = ::configureUi)
        viewModel.observeEvents().bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildInviteSettings::class.java, onNext = ::handleEvent)
    }

    private fun configureUi(state: GuildInviteSettingsViewModel.ViewState) {
        channelsAdapter.setData(state.invitableChannels)
        spinner.setSelection(state.invitableChannels.indexOfFirst { it.id == state.targetChannel?.id }.coerceAtLeast(0), false)
        expires.setOnCheckedChangeListener(null)
        expires.check(state.inviteSettings.maxAge)
        expires.setOnCheckedChangeListener { _, id ->
            viewModel.updatePendingInviteSettings(state.inviteSettings.mergeMaxAge(id))
        }
        maxUses.setOnCheckedChangeListener(null)
        maxUses.check(state.inviteSettings.maxUses)
        maxUses.setOnCheckedChangeListener { _, id ->
            viewModel.updatePendingInviteSettings(state.inviteSettings.mergeMaxUses(id))
        }
        temporary.setChecked(state.inviteSettings.temporary, false)
        temporary.setOnCheckedListener { checked ->
            viewModel.updatePendingInviteSettings(state.inviteSettings.mergeTemporary(checked))
        }
    }

    private fun handleEvent(event: GuildInviteSettingsViewModel.Event) {
        when (event) {
            GuildInviteSettingsViewModel.Event.InviteCreationFailure ->
                AppToast.show(requireContext(), R.string.default_failure_to_perform_action_message)
            is GuildInviteSettingsViewModel.Event.InviteCreationSuccess -> {
                requireActivity().setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_CREATED_INVITE, event.invite))
                requireActivity().finish()
            }
        }
    }

    private fun createButtons(group: RadioGroup, values: IntArray, text: (Int) -> CharSequence) {
        if (group.childCount != 0) return
        values.forEach { value ->
            (LayoutInflater.from(group.context).inflate(R.layout.invite_settings_radio_button, group, false) as AppCompatRadioButton)
                .apply {
                    id = value
                    this.text = text(value)
                    group.addView(this)
                }
        }
    }

    private class ChannelsSpinnerAdapter(context: Context) : ArrayAdapter<Channel>(context, 0) {
        private var channels: List<Channel> = emptyList()
        fun setData(data: List<Channel>) {
            channels = data
            notifyDataSetChanged()
        }
        override fun getCount() = channels.size
        override fun getItem(position: Int) = channels[position]
        override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
            itemView(position, convertView, parent, R.layout.view_invite_settings_channel_spinner_item, R.id.channel_spinner_item_textview)
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup) =
            itemView(position, convertView, parent, R.layout.view_invite_settngs_channel_spinner_item_open, R.id.channel_spinner_dropdown_item_textview)
        private fun itemView(position: Int, recycled: View?, parent: ViewGroup, layout: Int, textId: Int): View =
            (recycled ?: LayoutInflater.from(context).inflate(layout, parent, false)).also {
                it.findViewById<TextView>(textId).text = "#${getItem(position).getDisplayName()}"
            }
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val EXTRA_CREATED_INVITE = "EXTRA_CREATED_INVITE"

        fun launch(
            context: Context,
            launcher: ActivityResultLauncher<Intent>,
            channelId: Long?,
            guildId: Long,
            source: String,
        ) = AppScreenRouter.launchForResult(
            context,
            launcher,
            WidgetGuildInviteSettings::class.java,
            Intent().apply {
                channelId?.let { putExtra(EXTRA_CHANNEL_ID, it) }
                putExtra(EXTRA_GUILD_ID, guildId)
                putExtra(EXTRA_ORIGIN_SOURCE, source)
            },
        )

        fun registerForResult(
            fragment: DialogFragment,
            callback: (GuildInvite) -> Unit,
        ): ActivityResultLauncher<Intent> = fragment.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                (result.data?.getSerializableExtra(EXTRA_CREATED_INVITE) as? GuildInvite)?.let(callback)
            }
        }
    }
}
