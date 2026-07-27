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
import com.discord.api.channel.getDisplayName
import com.discord.models.domain.ModelInvite
import com.discord.utilities.dimen.dpToPixels
import com.discord.views.CheckedSetting
import com.google.android.material.button.MaterialButton

/**
 * The expiry / max-uses / temporary-membership sheet that slides up over the invite-share screen.
 *
 * The view owns the [WidgetGuildInviteShareViewModel] rather than exposing callbacks: the channel
 * spinner writes straight through on selection, while the radio groups and the toggle only mutate
 * a *pending* copy of the settings. Nothing is committed until "Generate a new link" is pressed,
 * so backing out of the sheet discards the edits — which is why [pendingInviteSettings] exists at
 * all.
 *
 * [onGenerateLinkListener] is the host's cue to collapse the sheet; the view does not know how it
 * is presented.
 */
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

    lateinit var viewModel: WidgetGuildInviteShareViewModel

    var onGenerateLinkListener: () -> Unit = {}

    init {
        LayoutInflater.from(context).inflate(R.layout.view_guild_invite_bottom_sheet, this, true)
        channelSpinner = findViewById(R.id.guild_invite_channel_spinner)
        expiresAfterGroup = findViewById(R.id.guild_invite_expires_after_radiogroup)
        maxUsesGroup = findViewById(R.id.guild_invite_max_uses_radiogroup)
        temporaryMembership = findViewById(R.id.guild_invite_temporary_membership)
        generateLink = findViewById(R.id.guild_invite_generate_link)

        isFocusable = true
        contentDescription = context.getString(R.string.invite_settings_title)
        channelSpinner.adapter = channelsAdapter

        createHorizontalCheckableButtons(expiresAfterGroup, ModelInvite.Settings.EXPIRES_AFTER_ARRAY) {
            formatInviteExpireAfterString(context, it)
        }
        createHorizontalCheckableButtons(maxUsesGroup, ModelInvite.Settings.MAX_USES_ARRAY) {
            getMaxUsesString(it)
        }

        channelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                channelsAdapter.getItem(position)?.id?.let(viewModel::selectChannel)
            }
        }
    }

    fun configureUi(data: WidgetInviteModel) {
        channelsAdapter.setData(data.invitableChannels)
        val selectedIndex = data.invitableChannels
            .indexOfFirst { it.id == data.targetChannel?.id }
            .coerceAtLeast(0)
        channelSpinner.setSelection(selectedIndex, false)

        // A model with no settings is one whose invite came from the store — nothing to configure.
        val settings = data.settings ?: return
        pendingInviteSettings = settings

        // Check first, install the listener afterwards: RadioGroup fires on a programmatic check,
        // and the reference relies on the same ordering to avoid a spurious pending mutation.
        expiresAfterGroup.checkedOption(settings.maxAge)
        expiresAfterGroup.setOnCheckedChangeListener { _, checkedId ->
            pendingInviteSettings = pendingInviteSettings?.mergeMaxAge(checkedId)
        }

        maxUsesGroup.checkedOption(settings.maxUses)
        maxUsesGroup.setOnCheckedChangeListener { _, checkedId ->
            pendingInviteSettings = pendingInviteSettings?.mergeMaxUses(checkedId)
        }

        temporaryMembership.setChecked(settings.temporary, false)
        temporaryMembership.setContainerClickListener(
            View.OnClickListener {
                temporaryMembership.toggle()
                pendingInviteSettings =
                    pendingInviteSettings?.mergeTemporary(temporaryMembership.isChecked())
            },
        )

        generateLink.setOnClickListener {
            pendingInviteSettings?.let(viewModel::updateInviteSettings)
            data.targetChannel?.let { channel -> viewModel.generateInviteLink(channel.id) }
            onGenerateLinkListener()
        }
    }

    /**
     * Inflates one radio button per legal value, using the value itself as the view id — that is
     * how `setOnCheckedChangeListener`'s `checkedId` becomes the setting.
     *
     * Only the first button gets a leading margin; the rest rely on the layout's own spacing.
     */
    private fun createHorizontalCheckableButtons(
        radioGroup: RadioGroup,
        valueSet: IntArray,
        textFactory: (Int) -> CharSequence,
    ) {
        if (radioGroup.childCount > 0) return
        valueSet.forEachIndexed { index, value ->
            val button = LayoutInflater.from(context)
                .inflate(R.layout.view_radio_button, radioGroup, false) as RadioButton
            button.id = value
            button.text = textFactory(value)
            if (index == 0) {
                (button.layoutParams as RadioGroup.LayoutParams).leftMargin = dpToPixels(16)
            }
            radioGroup.addView(button)
        }
    }

    /** `∞` rather than `0`, because zero reads as "no uses allowed". */
    private fun getMaxUsesString(numUses: Int): String = if (numUses != 0) numUses.toString() else "∞"

    private fun RadioGroup.checkedOption(value: Int) {
        setOnCheckedChangeListener(null)
        val button = (0 until childCount)
            .map { getChildAt(it) as RadioButton }
            .firstOrNull { it.id == value }
        button?.isChecked = true
    }

    /**
     * Backing adapter for the target-channel spinner. `ArrayAdapter`'s own storage is bypassed
     * entirely — [setData] holds the list and [getCount]/[getItem] read it — because the list is
     * replaced wholesale on every store emission.
     */
    private class ChannelsSpinnerAdapter(
        context: Context,
    ) : ArrayAdapter<Channel>(context, R.layout.view_invite_settings_channel_spinner_item) {

        private var channels: List<Channel> = emptyList()

        fun setData(data: List<Channel>) {
            channels = data
            notifyDataSetChanged()
        }

        override fun getCount(): Int = channels.size

        override fun getItem(position: Int): Channel? = channels.getOrNull(position)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = itemView(
            position,
            convertView,
            parent,
            R.layout.view_invite_settings_channel_spinner_item,
            R.id.channel_spinner_item_textview,
        )

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = itemView(
            position,
            convertView,
            parent,
            R.layout.view_invite_settngs_channel_spinner_item_open,
            R.id.channel_spinner_dropdown_item_textview,
        )

        private fun itemView(
            position: Int,
            convertView: View?,
            parent: ViewGroup,
            layout: Int,
            labelId: Int,
        ): View {
            val view = convertView
                ?: LayoutInflater.from(parent.context).inflate(layout, parent, false)
            view.findViewById<TextView>(labelId).text =
                "#${getItem(position)?.getDisplayName().orEmpty()}"
            return view
        }
    }
}
