package com.discord.widgets.channels.permissions

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.discord.R
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.isCategory
import com.discord.api.channel.isGuildTextChannel
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.permission.PermissionOverwrite
import com.discord.app.AppFragment
import com.discord.restapi.RestAPIParams
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.views.TernaryCheckBox
import com.discord.views.user.SettingsMemberView
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.DiscardChangesDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton

class WidgetChannelSettingsEditPermissions : AppFragment(R.layout.widget_channel_settings_edit_permissions) {
    private val guildId get() = extra(EXTRA_GUILD_ID)
    private val channelId get() = extra(EXTRA_CHANNEL_ID)
    private val targetId get() = extra(EXTRA_TARGET_ID)
    private val type get() = (arguments?.getInt(EXTRA_TYPE) ?: mostRecentIntent.getIntExtra(EXTRA_TYPE, -1))
    private val boxes = LinkedHashMap<Long, TernaryCheckBox>()
    private var baseline = emptyMap<Long, Int>()
    private lateinit var save: FloatingActionButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        PermissionEditor.permissionByViewId.forEach { (id, permission) -> boxes[permission] = view.findViewById(id) }
        save = view.findViewById(R.id.channel_permissions_save)
        parentFragmentManager.setFragmentResultListener(DISCARD_REQUEST_KEY, this) { _, _ ->
            baseline = currentStatuses()
            requireActivity().onBackPressed()
        }
        setOnBackPressed({
            if (currentStatuses() == baseline) false else {
                DiscardChangesDialog.show(parentFragmentManager, DISCARD_REQUEST_KEY)
                true
            }
        })
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        WidgetChannelSettingsEditPermissionsModel.get(guildId, channelId, targetId, type)
            .ui(this).subscribe(::render)
    }

    private fun render(model: WidgetChannelSettingsEditPermissionsModel?) {
        if (model == null) {
            requireActivity().onBackPressed()
            return
        }
        setActionBarTitle(if (model.channel.isCategory()) R.string.category_permissions else R.string.channel_permissions)
        setActionBarSubtitle(model.channel.getDisplayNameOrDefault(requireContext()))
        val overwrite = model.channel.permissionOverwrites.orEmpty().firstOrNull { it.id == targetId && it.type == overwriteType() }
        if (overwrite != null) setActionBarOptionsMenu(R.menu.menu_edit_permission_overwrite, onSelected = {
            if (it.itemId == R.id.menu_edit_overwrite_delete) delete()
        })
        else appActivity?.toolbar?.menu?.clear()
        bindOwner(model)
        view?.findViewById<TextView>(R.id.channel_permissions_channel_name)?.text = model.channel.getDisplayNameOrDefault(requireContext())
        view?.findViewById<LinearLayout>(R.id.channel_permissions_text_container)?.visibility =
            if (model.channel.isGuildTextChannel() || model.channel.isCategory()) View.VISIBLE else View.GONE
        view?.findViewById<LinearLayout>(R.id.channel_permissions_voice_container)?.visibility =
            if (model.channel.isGuildVocalChannel() || model.channel.isCategory()) View.VISIBLE else View.GONE
        view?.findViewById<LinearLayout>(R.id.channel_permissions_stage_container)?.visibility =
            if (model.channel.isStageVoiceChannel() || model.channel.isCategory()) View.VISIBLE else View.GONE
        baseline = boxes.mapValues { (permission, _) -> PermissionEditor.status(overwrite, permission) }
        boxes.forEach { (permission, box) ->
            box.onSwitchStatusChangedListener = null
            when (baseline.getValue(permission)) { 1 -> box.setOn(); -1 -> box.setOff(); else -> box.setNeutral() }
            configureEnabled(box, permission, model)
            box.onSwitchStatusChangedListener = TernaryCheckBox.OnSwitchStatusChangedListener { updateSave() }
        }
        save.setOnClickListener { save() }
        updateSave()
    }

    private fun bindOwner(model: WidgetChannelSettingsEditPermissionsModel) {
        val member = requireView().findViewById<SettingsMemberView>(R.id.member_view)
        val role = requireView().findViewById<TextView>(R.id.channel_permissions_target_name)
        if (model is WidgetChannelSettingsEditPermissionsModel.ModelForUser) {
            member.visibility = View.VISIBLE
            role.visibility = View.GONE
            member.configure(model.user, model.guildMember)
        } else {
            val value = model as WidgetChannelSettingsEditPermissionsModel.ModelForRole
            member.visibility = View.GONE
            role.visibility = View.VISIBLE
            role.text = value.guildRole.name
            role.setTextColor(RoleUtils.getRoleColor(value.guildRole, requireContext()))
        }
    }

    private fun configureEnabled(box: TernaryCheckBox, permission: Long, model: WidgetChannelSettingsEditPermissionsModel) {
        box.clearDisabled()
        if (model.channel.isStageVoiceChannel() && permission in STAGE_DISABLED) {
            box.setDisabled(R.string.stage_channel_cannot_overwrite_permission)
            return
        }
        when (model) {
            is WidgetChannelSettingsEditPermissionsModel.ModelForUser -> when {
                model.isMe && box.isOn() -> box.setDisabled(R.string.help_singular_permission)
                model.isMe -> box.setOffDisabled(R.string.help_singular_permission)
                model.myPermissionsForChannel and permission != permission -> box.setDisabled(R.string.help_missing_permission)
            }
            is WidgetChannelSettingsEditPermissionsModel.ModelForRole -> {
                if (!model.meHasRole && model.myPermissionsForChannel and permission != permission) {
                    box.setDisabled(R.string.help_missing_permission)
                } else if (box.isOn() && !model.canNeutralizeRolePermission(permission)) {
                    box.setDisabled(R.string.help_singular_permission)
                } else if (!box.isOn() && !model.canDenyRolePermission(permission)) {
                    box.setOffDisabled(R.string.cannot_deny_missing_permission)
                }
            }
        }
    }

    private fun currentStatuses() = boxes.mapValues { it.value.switchStatus }
    private fun updateSave() { if (currentStatuses() == baseline) save.hide() else save.show() }

    private fun save() {
        val (allow, deny) = PermissionEditor.bits(currentStatuses())
        val body = if (type == PermissionEditor.TYPE_ROLE) RestAPIParams.ChannelPermissionOverwrites.createForRole(targetId, allow, deny)
        else RestAPIParams.ChannelPermissionOverwrites.createForMember(targetId, allow, deny)
        RestAPI.apiOrNull?.updatePermissionOverwrites(channelId, targetId, body)
            ?.restSubscribeOn()?.ui(this)?.appSubscribe(
                javaClass,
                requireContext(),
                onCompleted = { baseline = currentStatuses(); updateSave() },
            ) {}
    }

    private fun delete() {
        RestAPI.apiOrNull?.deletePermissionOverwrites(channelId, targetId)
            ?.restSubscribeOn()?.ui(this)?.appSubscribe(
                javaClass,
                requireContext(),
                onCompleted = { baseline = emptyMap(); requireActivity().onBackPressed() },
            ) {}
    }

    private fun overwriteType() = if (type == PermissionEditor.TYPE_ROLE) PermissionOverwrite.Type.ROLE else PermissionOverwrite.Type.MEMBER
    private fun extra(key: String) = arguments?.getLong(key) ?: mostRecentIntent.getLongExtra(key, -1L)

    companion object {
        private const val EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val EXTRA_CHANNEL_ID = "INTENT_EXTRA_CHANNEL_ID"
        private const val EXTRA_TARGET_ID = "INTENT_EXTRA_TARGET_ID"
        private const val EXTRA_TYPE = "INTENT_EXTRA_TYPE"
        private const val DISCARD_REQUEST_KEY = "CHANNEL_PERMISSION_DISCARD_RESULT"
        private val STAGE_DISABLED = setOf(com.discord.api.permission.Permission.MUTE_MEMBERS, com.discord.api.permission.Permission.DEAFEN_MEMBERS, com.discord.api.permission.Permission.MOVE_MEMBERS)

        private fun create(context: Context, guildId: Long, channelId: Long, targetId: Long, type: Int) = SettingsNavigation.launch(
            context,
            WidgetChannelSettingsEditPermissions::class.java,
            Intent().putExtra(EXTRA_GUILD_ID, guildId).putExtra(EXTRA_CHANNEL_ID, channelId)
                .putExtra(EXTRA_TARGET_ID, targetId).putExtra(EXTRA_TYPE, type),
        )
        fun createForRole(context: Context, guildId: Long, channelId: Long, targetId: Long) = create(context, guildId, channelId, targetId, PermissionEditor.TYPE_ROLE)
        fun createForUser(context: Context, guildId: Long, channelId: Long, targetId: Long) = create(context, guildId, channelId, targetId, PermissionEditor.TYPE_USER)
    }
}
