package com.discord.widgets.guilds.invite

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.models.domain.ModelInvite
import com.discord.views.CheckedSetting
import com.google.android.material.button.MaterialButton

class ViewInviteSettingsSheet @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : NestedScrollView(context, attrs, defStyleAttr) {
    private val channelSpinner: Spinner
    private val expiresAfterGroup: RadioGroup
    private val maxUsesGroup: RadioGroup
    private val temporaryMembership: CheckedSetting
    private val generateLink: MaterialButton
    private val channelsAdapter = ChannelsSpinnerAdapter(context)
    private var pendingInviteSettings: ModelInvite.Settings? = null

    var onChannelSelected: (Long) -> Unit = {}
    var onSettingsChanged: (ModelInvite.Settings) -> Unit = {}
    var onGenerateLinkListener: () -> Unit = {}

    init {
        LayoutInflater.from(context).inflate(R.layout.view_guild_invite_bottom_sheet, this, true)
        channelSpinner = requireNotNull(findViewById(R.id.guild_invite_channel_spinner))
        expiresAfterGroup = requireNotNull(findViewById(R.id.guild_invite_expires_after_radiogroup))
        maxUsesGroup = requireNotNull(findViewById(R.id.guild_invite_max_uses_radiogroup))
        temporaryMembership = requireNotNull(findViewById(R.id.guild_invite_temporary_membership))
        generateLink = requireNotNull(findViewById(R.id.guild_invite_generate_link))

        isFocusable = true
        contentDescription = context.getString(R.string.invite_settings_title)
        channelSpinner.adapter = channelsAdapter
        addOptions(expiresAfterGroup, ModelInvite.Settings.EXPIRES_AFTER_ARRAY, ::expirationText)
        addOptions(maxUsesGroup, ModelInvite.Settings.MAX_USES_ARRAY) { if (it == 0) "∞" else it.toString() }
        channelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                channelsAdapter.getItem(position)?.id?.let(onChannelSelected)
            }
        }
    }

    fun configureUi(data: WidgetInviteModel) {
        channelsAdapter.setData(data.invitableChannels)
        val selected = data.invitableChannels.indexOfFirst { it.id == data.targetChannel?.id }.coerceAtLeast(0)
        channelSpinner.setSelection(selected, false)

        val settings = data.settings ?: return
        pendingInviteSettings = settings
        expiresAfterGroup.setOnCheckedChangeListener(null)
        maxUsesGroup.setOnCheckedChangeListener(null)
        expiresAfterGroup.check(settings.maxAge)
        maxUsesGroup.check(settings.maxUses)
        temporaryMembership.setChecked(settings.temporary, false)

        expiresAfterGroup.setOnCheckedChangeListener { _, value ->
            pendingInviteSettings = pendingInviteSettings?.mergeMaxAge(value)
        }
        maxUsesGroup.setOnCheckedChangeListener { _, value ->
            pendingInviteSettings = pendingInviteSettings?.mergeMaxUses(value)
        }
        temporaryMembership.setOnCheckedListener { checked ->
            pendingInviteSettings = pendingInviteSettings?.mergeTemporary(checked)
        }
        generateLink.setOnClickListener {
            pendingInviteSettings?.let(onSettingsChanged)
            onGenerateLinkListener()
        }
    }

    private fun addOptions(group: RadioGroup, values: IntArray, text: (Int) -> CharSequence) {
        if (group.childCount > 0) return
        values.forEachIndexed { index, value ->
            val button = LayoutInflater.from(context)
                .inflate(R.layout.view_radio_button, group, false) as RadioButton
            button.id = value
            button.text = text(value)
            if (index == 0) {
                (button.layoutParams as RadioGroup.LayoutParams).marginStart =
                    (16 * resources.displayMetrics.density).toInt()
            }
            group.addView(button)
        }
    }

    private fun expirationText(seconds: Int): CharSequence = when (seconds) {
        ModelInvite.Settings.NEVER -> context.getText(R.string.max_age_never)
        ModelInvite.Settings.HALF_HOUR -> resources.getQuantityString(R.plurals.duration_mins_mins, 30, 30)
        ModelInvite.Settings.ONE_HOUR -> resources.getQuantityString(R.plurals.duration_hours_hours, 1, 1)
        ModelInvite.Settings.SIX_HOURS -> resources.getQuantityString(R.plurals.duration_hours_hours, 6, 6)
        ModelInvite.Settings.TWELVE_HOURS -> resources.getQuantityString(R.plurals.duration_hours_hours, 12, 12)
        ModelInvite.Settings.ONE_DAY -> resources.getQuantityString(R.plurals.duration_days_days, 1, 1)
        ModelInvite.Settings.SEVEN_DAYS -> resources.getQuantityString(R.plurals.duration_days_days, 7, 7)
        else -> throw IllegalArgumentException("Invalid expiration duration $seconds")
    }

    private class ChannelsSpinnerAdapter(context: Context) :
        ArrayAdapter<Channel>(context, R.layout.view_invite_settings_channel_spinner_item) {
        private var channels: List<Channel> = emptyList()

        fun setData(data: List<Channel>) {
            channels = data
            notifyDataSetChanged()
        }

        override fun getCount() = channels.size
        override fun getItem(position: Int) = channels.getOrNull(position)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup) = itemView(
            position,
            convertView,
            parent,
            R.layout.view_invite_settings_channel_spinner_item,
            R.id.channel_spinner_item_textview,
        )

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup) = itemView(
            position,
            convertView,
            parent,
            R.layout.view_invite_settngs_channel_spinner_item_open,
            R.id.channel_spinner_dropdown_item_textview,
        )

        private fun itemView(position: Int, convertView: View?, parent: ViewGroup, layout: Int, labelId: Int): View {
            val view = convertView ?: LayoutInflater.from(parent.context).inflate(layout, parent, false)
            requireNotNull<TextView>(view.findViewById(labelId)).text = "#${getItem(position)?.name.orEmpty()}"
            return view
        }
    }
}
