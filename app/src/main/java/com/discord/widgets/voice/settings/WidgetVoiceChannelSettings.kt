package com.discord.widgets.voice.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.isVisible
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDeletedMessage
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.guild.GuildFeature
import com.discord.api.permission.Permission
import com.discord.app.AppFragment
import com.discord.app.AppDialog
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.dialogs.SelectorDialog
import com.discord.models.domain.ModelVoiceRegion
import com.discord.models.guild.Guild
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.views.CheckedSetting
import com.discord.widgets.channels.permissions.WidgetChannelSettingsPermissionsOverview
import com.discord.widgets.integrations.WidgetChannelSettingsIntegrations
import com.discord.widgets.settings.SettingsStrings
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout
import rx.Observable

/** Edits a guild voice or stage channel. */
class WidgetVoiceChannelSettings : AppFragment(R.layout.widget_voice_channel_settings) {

    private lateinit var name: TextInputLayout
    private lateinit var bitrate: SeekBar
    private lateinit var bitrateDisplay: TextView
    private lateinit var userLimit: SeekBar
    private lateinit var userLimitDisplay: TextView
    private lateinit var region: TextView
    private lateinit var nsfw: CheckedSetting
    private lateinit var save: FloatingActionButton
    private var model: Model? = null
    private var selectedRegion: String? = null
    private var savedVideoQuality = VIDEO_QUALITY_AUTO
    private var draftVideoQuality = VIDEO_QUALITY_AUTO
    private var bindingState = false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        name = view.findViewById(R.id.channel_settings_edit_name)
        bitrate = view.findViewById(R.id.bitrate_seekbar)
        bitrateDisplay = view.findViewById(R.id.current_bitrate_display)
        userLimit = view.findViewById(R.id.user_limit_seekbar)
        userLimitDisplay = view.findViewById(R.id.current_user_limit_display)
        region = view.findViewById(R.id.channel_settings_region_override)
        nsfw = view.findViewById(R.id.channel_settings_nsfw)
        save = view.findViewById(R.id.channel_settings_save)

        name.editText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateSaveVisibility()
        })
        bitrate.setOnSeekBarChangeListener(listener { progress ->
            bitrateDisplay.text = "${MIN_BITRATE_KBPS + progress} Kbps"
        })
        userLimit.setOnSeekBarChangeListener(listener { progress ->
            userLimitDisplay.text = if (progress == 0) getString(R.string.no_user_limit) else progress.toString()
        })
        nsfw.setOnCheckedListener { updateSaveVisibility() }
        view.findViewById<CheckedSetting>(R.id.channel_settings_video_quality_auto)
            .setContainerClickListener { selectVideoQuality(VIDEO_QUALITY_AUTO) }
        view.findViewById<CheckedSetting>(R.id.channel_settings_video_quality_full)
            .setContainerClickListener { selectVideoQuality(VIDEO_QUALITY_FULL) }
        save.setOnClickListener { saveChanges() }
    }

    private fun selectVideoQuality(mode: Int) {
        draftVideoQuality = mode
        renderVideoQualityRadios()
        updateSaveVisibility()
    }

    private fun renderVideoQualityRadios() {
        val root = view ?: return
        root.findViewById<CheckedSetting>(R.id.channel_settings_video_quality_auto).isChecked =
            draftVideoQuality == VIDEO_QUALITY_AUTO
        root.findViewById<CheckedSetting>(R.id.channel_settings_video_quality_full).isChecked =
            draftVideoQuality == VIDEO_QUALITY_FULL
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        Model.observe(channelId()).ui(this).appSubscribe(WidgetVoiceChannelSettings::class.java) {
            if (it == null) appActivity?.finish() else render(it)
        }
    }

    private fun render(next: Model) {
        if (!next.canManageChannel && !next.canManagePermissions) {
            appActivity?.finish()
            return
        }
        val root = view ?: return
        model = next
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.channel_settings)
        setActionBarSubtitle(next.channel.getDisplayNameOrDefault(requireContext()))
        setActionBarOptionsMenu(R.menu.menu_voice_channel_settings, onSelected = {
            if (it.itemId == R.id.menu_channel_settings_delete) confirmDelete(next.channel)
        })

        bindingState = true
        name.editText?.setText(next.channel.name.orEmpty())
        bitrate.max = (next.maxBitrateKbps - MIN_BITRATE_KBPS).coerceAtLeast(0)
        bitrate.progress = (next.channel.bitrate / 1_000 - MIN_BITRATE_KBPS).coerceIn(0, bitrate.max)
        bitrateDisplay.text = "${MIN_BITRATE_KBPS + bitrate.progress} Kbps"
        userLimit.max = if (next.channel.isStageVoiceChannel()) MAX_STAGE_USER_LIMIT else MAX_VOICE_USER_LIMIT
        userLimit.progress = next.channel.userLimit.coerceIn(0, userLimit.max)
        userLimitDisplay.text = if (userLimit.progress == 0) getString(R.string.no_user_limit) else userLimit.progress.toString()
        selectedRegion = next.channel.rtcRegion
        region.text = regionName(next.regions, selectedRegion)
        nsfw.setOnCheckedListener(null)
        nsfw.isChecked = next.channel.nsfw
        nsfw.setOnCheckedListener { updateSaveVisibility() }
        savedVideoQuality = next.channel.videoQualityMode ?: VIDEO_QUALITY_AUTO
        draftVideoQuality = savedVideoQuality
        renderVideoQualityRadios()
        bindingState = false

        val ordinaryVoice = !next.channel.isStageVoiceChannel()
        root.findViewById<TextView>(R.id.settings_user_limit_help).text = SettingsStrings.render(
            requireContext(),
            if (ordinaryVoice) R.string.form_help_user_limit else R.string.form_help_user_limit_stage,
        ).trim()

        val canEdit = next.canManageChannel
        name.isVisible = canEdit
        root.findViewById<LinearLayout>(R.id.channel_settings_section_video_quality).isVisible = canEdit
        val showRegion = canEdit && ordinaryVoice
        root.findViewById<View>(R.id.region_override_divider).isVisible = showRegion
        root.findViewById<LinearLayout>(R.id.channel_settings_region_override_container).apply {
            isVisible = showRegion
            setOnClickListener { showRegionDialog(next.regions) }
        }
        root.findViewById<TextView>(R.id.channel_settings_region_override_help).apply {
            isVisible = showRegion
            setText(R.string.form_help_region_override)
        }
        root.findViewById<TextView>(R.id.settings_bitrate_help).text = SettingsStrings.render(
            requireContext(), R.string.form_help_bitrate, "bitrate" to DEFAULT_BITRATE_KBPS,
        )
        root.findViewById<LinearLayout>(R.id.channel_settings_section_user_management).isVisible = next.canManagePermissions
        root.findViewById<TextView>(R.id.channel_settings_permissions).setOnClickListener {
            WidgetChannelSettingsPermissionsOverview.launch(requireContext(), next.channel.id)
        }
        root.findViewById<LinearLayout>(R.id.channel_settings_section_integrations).isVisible =
            next.canManageWebhooks
        root.findViewById<TextView>(R.id.channel_settings_integrations).setOnClickListener {
            WidgetChannelSettingsIntegrations.launch(requireContext(), next.channel.id)
        }
        root.findViewById<LinearLayout>(R.id.channel_settings_section_privacy_safety).isVisible =
            canEdit && ordinaryVoice
        save.isVisible = canEdit
        updateSaveVisibility()
    }

    private fun showRegionDialog(regions: List<ModelVoiceRegion>) {
        val labels = listOf(getString(R.string.automatic_region)) + regions.map { it.name.orEmpty() }
        SelectorDialog.show(
            childFragmentManager,
            getString(R.string.form_label_region_override),
            labels.toTypedArray(),
        ) { position ->
            selectedRegion = regions.getOrNull(position - 1)?.id
            region.text = labels[position]
            updateSaveVisibility()
        }
    }

    private fun saveChanges() {
        val current = model?.channel ?: return
        RestAPI.api.editVoiceChannel(
            current.id,
            RestAPIParams.VoiceChannel(
                name = name.editText?.text?.toString().orEmpty(),
                topic = current.topic,
                type = current.type,
                nsfw = nsfw.isChecked,
                userLimit = userLimit.progress,
                bitrate = (MIN_BITRATE_KBPS + bitrate.progress) * 1_000,
                rtcRegion = selectedRegion,
                videoQualityMode = draftVideoQuality,
            ),
        ).restSubscribeOn().ui(this).appSubscribe(WidgetVoiceChannelSettings::class.java, context = context) {
            AppToast.show(context, R.string.saved_settings)
            hideKeyboard()
        }
    }

    private fun confirmDelete(channel: Channel) {
        VoiceChannelDeleteDialog.show(
            childFragmentManager,
            channel.id,
            SettingsStrings.render(
                requireContext(), R.string.delete_channel_body,
                "channelName" to channel.getDisplayNameOrDefault(requireContext()),
            ),
        ) { appActivity?.finish() }
    }

    private fun listener(update: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            update(progress)
            updateSaveVisibility()
        }
        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
    }

    private fun updateSaveVisibility() {
        if (bindingState || !::save.isInitialized) return
        val channel = model?.channel ?: return
        save.isVisible = model?.canManageChannel == true && (
            name.editText?.text?.toString().orEmpty() != channel.name.orEmpty() ||
                (MIN_BITRATE_KBPS + bitrate.progress) * 1_000 != channel.bitrate ||
                userLimit.progress != channel.userLimit || selectedRegion != channel.rtcRegion ||
                nsfw.isChecked != channel.nsfw || draftVideoQuality != savedVideoQuality
            )
    }

    private fun regionName(regions: List<ModelVoiceRegion>, id: String?): String =
        regions.firstOrNull { it.id == id }?.name ?: getString(R.string.automatic_region)

    private fun channelId() = mostRecentIntent.getLongExtra(ARG_CHANNEL_ID, -1L)

    internal data class Model(
        val channel: Channel,
        val canManageChannel: Boolean,
        val canManagePermissions: Boolean,
        val canManageWebhooks: Boolean,
        val maxBitrateKbps: Int,
        val regions: List<ModelVoiceRegion>,
    ) {
        companion object {
            fun observe(channelId: Long): Observable<Model?> = StoreStream.getChannels().observeChannel(channelId)
                .switchMap { channel ->
                    if (channel == null || !channel.isGuildVocalChannel()) return@switchMap Observable.just(null)
                    Observable.combineLatest(
                        StoreStream.getGuilds().observeGuild(channel.guildId),
                        StoreStream.getUsers().observeMe(),
                        StoreStream.getPermissions().observePermissionsForChannel(channel.id),
                        RestAPI.api.getGuildVoiceRegions(channel.guildId).restSubscribeOn()
                            .onErrorReturn { emptyList() },
                    ) { guild, me, permissions, regions ->
                        guild?.let {
                            Model(
                                channel,
                                PermissionUtils.canAndIsElevated(Permission.MANAGE_CHANNELS, permissions, me.mfaEnabled, it.mfaLevel),
                                PermissionUtils.canAndIsElevated(Permission.MANAGE_ROLES, permissions, me.mfaEnabled, it.mfaLevel),
                                PermissionUtils.canAndIsElevated(Permission.MANAGE_WEBHOOKS, permissions, me.mfaEnabled, it.mfaLevel),
                                maxBitrateKbps(it, channel),
                                regions.filterNot { region -> region.isDeprecated || region.isHidden }
                                    .sortedBy { region -> region.name },
                            )
                        }
                    }
                }.computationLatest().distinctUntilChanged()
        }
    }

    companion object {
        private const val ARG_CHANNEL_ID = "INTENT_EXTRA_CHANNEL_ID"
        const val MIN_BITRATE_KBPS = 8
        const val DEFAULT_BITRATE_KBPS = 64
        private const val MAX_VOICE_USER_LIMIT = 99
        private const val MAX_STAGE_USER_LIMIT = 10_000
        private const val VIDEO_QUALITY_AUTO = 1
        private const val VIDEO_QUALITY_FULL = 2

        @JvmStatic
        fun launch(channelId: Long, context: Context) = AppScreenRouter.launch(
            context,
            WidgetVoiceChannelSettings::class.java,
            Intent().putExtra(ARG_CHANNEL_ID, channelId),
        )

        internal fun maxBitrateKbps(guild: Guild, channel: Channel): Int = when {
            channel.isStageVoiceChannel() -> DEFAULT_BITRATE_KBPS
            guild.hasFeature(GuildFeature.VIP_REGIONS) || guild.premiumTier >= 3 -> 384
            guild.premiumTier == 2 -> 256
            guild.premiumTier == 1 -> 128
            else -> 96
        }
    }
}

internal class VoiceChannelDeleteDialog : AppDialog(R.layout.widget_channel_settings_delete) {
    var onDeleted: (() -> Unit)? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<TextView>(R.id.channel_settings_delete_body).text =
            argumentsOrDefault.getCharSequence(ARG_BODY)
        view.findViewById<MaterialButton>(R.id.channel_settings_delete_cancel).setOnClickListener { dismiss() }
        view.findViewById<MaterialButton>(R.id.channel_settings_delete_confirm).setOnClickListener {
            RestAPI.api.deleteChannel(argumentsOrDefault.getLong(ARG_CHANNEL_ID))
                .restSubscribeOn().ui(this)
                .appSubscribe(VoiceChannelDeleteDialog::class.java, context = context) { deleted ->
                    deleted.getDeletedMessage()?.let { AppToast.show(context, it) }
                    dismiss()
                    onDeleted?.invoke()
                }
        }
    }

    companion object {
        private const val ARG_CHANNEL_ID = "channel_id"
        private const val ARG_BODY = "body"

        fun show(
            fragmentManager: androidx.fragment.app.FragmentManager,
            channelId: Long,
            body: CharSequence,
            onDeleted: () -> Unit,
        ) = VoiceChannelDeleteDialog().apply {
            arguments = Bundle().apply {
                putLong(ARG_CHANNEL_ID, channelId)
                putCharSequence(ARG_BODY, body)
            }
            this.onDeleted = onDeleted
        }.show(fragmentManager, VoiceChannelDeleteDialog::class.java.name)
    }
}
