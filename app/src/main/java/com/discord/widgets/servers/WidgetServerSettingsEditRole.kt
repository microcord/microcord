package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.api.guild.GuildFeature
import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import com.discord.app.AppFragment
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.views.CheckedSetting
import com.discord.widgets.roles.RoleIconView
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.StatefulViews
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout
import rx.Observable

class WidgetServerSettingsEditRole : AppFragment(R.layout.widget_edit_role) {
    private val guildId get() = argument(INTENT_EXTRA_GUILD_ID)
    private val roleId get() = argument(INTENT_EXTRA_ROLE_ID)
    private val state = StatefulViews(R.id.edit_role_name)
    private lateinit var roleName: TextInputLayout
    private lateinit var save: FloatingActionButton
    private var currentModel: Model? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        retainInstance = true
        setActionBarDisplayHomeAsUpEnabled()
        roleName = view.findViewById(R.id.edit_role_name)
        save = view.findViewById(R.id.edit_role_save)
        state.setupUnsavedChangesConfirmation(this)
        state.setupTextWatcherWithSaveAction(this, save, roleName)
        save.hide()
        parentFragmentManager.setFragmentResultListener(
            RoleColorPickerDialog.REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, result ->
            currentModel?.let { model ->
                patchRole(
                    model,
                    RestAPIParams.Role.createWithRole(model.role).apply {
                        color = result.getInt(RoleColorPickerDialog.RESULT_COLOR)
                    },
                )
            }
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        Model.observe(guildId, roleId).computationLatest().ui(this).appSubscribe(javaClass) { configureUI(it) }
        parentFragmentManager.findFragmentByTag(DIALOG_TAG_COLOR_PICKER)?.let {
            parentFragmentManager.beginTransaction().remove(it).commit()
        }
    }

    override fun onDestroyView() {
        currentModel = null
        super.onDestroyView()
    }

    private fun configureUI(model: Model?) {
        currentModel = model
        if (model == null) {
            appActivity?.finish()
            return
        }
        setupMenu(model)
        setupActionBar(model)
        setupName(model)
        setupColor(model)
        setupRoleIcon(model)
        setupBooleanSettings(model)
        setupPermissions(model)
        state.configureSaveActionView(save)
    }

    private fun setupActionBar(model: Model) {
        val toolbar = appActivity?.toolbar ?: return
        val roleColor = RoleUtils.getRoleColor(
            model.role,
            requireContext(),
            ColorCompat.getThemedColor(requireContext(), R.attr.color_brand),
        )
        val statusBarColor = if (RoleUtils.isDefaultColor(model.role)) {
            ColorCompat.getThemedColor(this, R.attr.color_brand_460)
        } else {
            val hsv = FloatArray(3)
            Color.colorToHSV(roleColor, hsv)
            hsv[2] *= 0.5f
            Color.HSVToColor(hsv)
        }
        val isDark = ColorCompat.isColorDark(roleColor)
        val foreground = ColorCompat.getColor(
            requireContext(),
            if (isDark) R.color.primary_100 else R.color.primary_500,
        )
        ColorCompat.setStatusBarColor(this, statusBarColor, isDark)
        setActionBarDisplayHomeAsUpEnabled()
        fun colored(text: String) = SpannableStringBuilder(text).apply {
            setSpan(ForegroundColorSpan(foreground), 0, length, 0)
        }
        setActionBarTitle(colored(getString(R.string.form_label_role_settings)))
        setActionBarSubtitle(colored(model.role.name))
        toolbar.setBackgroundColor(roleColor)
        toolbar.navigationIcon?.colorFilter = PorterDuffColorFilter(foreground, PorterDuff.Mode.SRC_ATOP)
        toolbar.overflowIcon?.colorFilter = PorterDuffColorFilter(foreground, PorterDuff.Mode.SRC_ATOP)
    }

    private fun setupMenu(model: Model) {
        setActionBarOptionsMenu(
            if (model.canManage() && !model.isEveryoneRole && !model.role.managed) {
                R.menu.menu_edit_role
            } else {
                R.menu.menu_empty
            },
            onSelected = { item ->
                if (item.itemId == R.id.menu_edit_role_delete) deleteRole(model)
            },
        )
    }

    private fun setupName(model: Model) {
        val displayed = state.get(R.id.edit_role_name, model.role.name)
        if (roleName.editText?.text?.toString() != displayed) roleName.editText?.setText(displayed)
        val overlay = requireView().findViewById<View>(R.id.role_settings_edit_name_disabled_overlay)
        if (model.canManage() && !model.isEveryoneRole) {
            overlay.visibility = View.GONE
            overlay.setOnClickListener(null)
        } else {
            overlay.visibility = View.VISIBLE
            overlay.setOnClickListener { showLockMessage(model, everyoneLocked = true) }
        }
        save.setOnClickListener {
            val name = roleName.editText?.text?.toString().orEmpty().trim()
            if (name.isEmpty()) {
                Toast.makeText(requireContext(), R.string.form_label_role_enter_name, Toast.LENGTH_SHORT).show()
            } else {
                patchRole(model, RestAPIParams.Role.createWithRole(model.role).apply { this.name = name })
                hideKeyboard()
            }
        }
    }

    private fun setupColor(model: Model) {
        val swatch = requireView().findViewById<View>(R.id.role_settings_current_color_display)
        swatch.background = ContextCompat.getDrawable(requireContext(), R.drawable.drawable_circle_white_1)
            ?.mutate()?.apply {
                colorFilter = PorterDuffColorFilter(
                    RoleUtils.getRoleColor(model.role, requireContext()),
                    PorterDuff.Mode.SRC_ATOP,
                )
            }
        val container = requireView().findViewById<View>(R.id.role_settings_color_selector_container)
        val overlay = requireView().findViewById<View>(R.id.role_settings_color_disabled_overlay)
        if (model.canManage() && !model.isEveryoneRole) {
            overlay.visibility = View.GONE
            overlay.setOnClickListener(null)
            container.setOnClickListener { launchColorPicker(model) }
        } else {
            overlay.visibility = View.VISIBLE
            overlay.setOnClickListener { showLockMessage(model, everyoneLocked = true) }
            container.setOnClickListener(null)
        }
    }

    private fun launchColorPicker(model: Model) {
        RoleColorPickerDialog.newInstance(RoleUtils.getRoleColor(model.role, requireContext()))
            .show(parentFragmentManager, DIALOG_TAG_COLOR_PICKER)
        hideKeyboard()
    }

    private fun setupRoleIcon(model: Model) {
        val visible = !model.role.icon.isNullOrEmpty()
        requireView().findViewById<TextView>(R.id.role_settings_role_icon_header).visibility = visible.visibility
        requireView().findViewById<View>(R.id.role_settings_role_icon_divider).visibility = visible.visibility
        requireView().findViewById<View>(R.id.role_settings_icon_container).visibility = visible.visibility
        if (visible) requireView().findViewById<RoleIconView>(R.id.role_settings_role_icon)
            .setRole(model.role, model.guildId)
    }

    private fun setupBooleanSettings(model: Model) {
        bindBooleanSetting(
            R.id.role_settings_hoist_checkedsetting,
            model.role.hoist,
            model,
        ) { role, value -> role.hoist = value }
        bindBooleanSetting(
            R.id.role_settings_mentionable_checkedsetting,
            model.role.mentionable,
            model,
        ) { role, value -> role.mentionable = value }
    }

    private fun bindBooleanSetting(
        viewId: Int,
        checked: Boolean,
        model: Model,
        mutate: (RestAPIParams.Role, Boolean) -> Unit,
    ) {
        val setting = requireView().findViewById<CheckedSetting>(viewId)
        setting.setChecked(checked, false)
        if (model.canManage() && !model.isEveryoneRole) {
            setting.setContainerClickListener {
                roleName.clearFocus()
                patchRole(model, RestAPIParams.Role.createWithRole(model.role).apply { mutate(this, !checked) })
            }
        } else {
            setting.setDisabledTooltip(lockMessage(model, everyoneLocked = true))
        }
    }

    private fun setupPermissions(model: Model) {
        PERMISSIONS.forEach { (viewId, permission) ->
            val setting = requireView().findViewById<CheckedSetting>(viewId)
            val checked = PermissionUtils.can(permission, model.role.permissions)
            setting.setChecked(checked, false)
            val ownsPermission = PermissionUtils.can(permission, model.myPermissions)
            when (model.manageStatus) {
                ManageStatus.CAN_MANAGE_CONDITIONAL -> if (!ownsPermission || model.isSingular(permission) && checked) {
                    setting.setDisabledTooltip(
                        if (ownsPermission) R.string.help_role_locked_mine
                        else R.string.help_missing_manage_roles_permission,
                    )
                } else enablePermission(setting, model, permission)

                ManageStatus.CAN_MANAGE_ADMIN -> if (!model.owner && permission == Permission.ADMINISTRATOR && model.isSingular(permission)) {
                    setting.setDisabledTooltip(R.string.help_role_locked_mine)
                } else enablePermission(setting, model, permission)

                else -> setting.setDisabledTooltip(lockMessage(model, everyoneLocked = false))
            }
            if (permission == Permission.VIEW_GUILD_ANALYTICS) {
                setting.visibility = model.isCommunityServer.visibility
            }
        }
    }

    private fun enablePermission(setting: CheckedSetting, model: Model, permission: Long) {
        setting.setContainerClickListener {
            roleName.clearFocus()
            patchRole(
                model,
                RestAPIParams.Role.createWithRole(model.role).apply {
                    permissions = model.role.permissions xor permission
                },
            )
        }
    }

    private fun patchRole(model: Model, body: RestAPIParams.Role) {
        RestAPI.api.updateRole(model.guildId, model.role.id, body)
            .restSubscribeOn(retry = false)
            .ui(this)
            .appSubscribe(javaClass, requireContext()) { }
    }

    private fun deleteRole(model: Model) {
        RestAPI.api.deleteRole(model.guildId, model.role.id)
            .restSubscribeOn(retry = false)
            .ui(this)
            .appSubscribe(javaClass, requireContext(), onCompleted = { appActivity?.onBackPressed() }) { }
    }

    private fun showLockMessage(model: Model, everyoneLocked: Boolean) {
        Toast.makeText(requireContext(), lockMessage(model, everyoneLocked), Toast.LENGTH_SHORT).show()
    }

    private fun lockMessage(model: Model, everyoneLocked: Boolean): String = when {
        everyoneLocked && model.isEveryoneRole -> getString(R.string.form_label_disabled_for_everyone)
        model.manageStatus == ManageStatus.NO_MANAGE_ROLES_PERMISSION ->
            getString(R.string.help_missing_manage_roles_permission)
        model.manageStatus == ManageStatus.LOCKED_HIGHER -> getString(R.string.help_role_locked)
        model.manageStatus == ManageStatus.LOCKED_HIGHEST -> getString(R.string.help_role_locked_mine)
        model.manageStatus == ManageStatus.USER_NOT_ELEVATED -> getString(R.string.two_fa_guild_mfa_warning_ios)
        else -> ""
    }

    private fun argument(key: String): Long = arguments?.getLong(key, INVALID_ID)
        ?.takeIf { it != INVALID_ID }
        ?: mostRecentIntent.getLongExtra(key, INVALID_ID)

    private val Boolean.visibility get() = if (this) View.VISIBLE else View.GONE

    data class Model(
        val owner: Boolean,
        val guildId: Long,
        val role: GuildRole,
        val manageStatus: ManageStatus?,
        val myPermissions: Long?,
        val myPermissionsFromOtherRoles: Long,
        val isCommunityServer: Boolean,
    ) {
        val isEveryoneRole get() = role.id == guildId
        fun canManage() = manageStatus == ManageStatus.CAN_MANAGE_CONDITIONAL ||
            manageStatus == ManageStatus.CAN_MANAGE_ADMIN
        fun isSingular(permission: Long) = !owner && myPermissionsFromOtherRoles and permission != permission

        enum class ManageStatus {
            NO_MANAGE_ROLES_PERMISSION,
            LOCKED_HIGHER,
            LOCKED_HIGHEST,
            USER_NOT_ELEVATED,
            CAN_MANAGE_CONDITIONAL,
            CAN_MANAGE_ADMIN,
        }

        companion object {
            fun observe(guildId: Long, roleId: Long): Observable<Model?> =
                StoreStream.getUsers().observeMe().flatMap { me ->
                    Observable.combineLatest(
                        StoreStream.getGuilds().observeGuild(guildId),
                        StoreStream.getGuilds().observeComputed(guildId),
                        StoreStream.getGuilds().observeRoles(guildId),
                        StoreStream.getPermissions().observePermissionsForGuild(guildId),
                    ) { guild, members, roles, permissions ->
                        create(
                            guildId,
                            roleId,
                            me,
                            guild,
                            members[me.id],
                            roles,
                            permissions,
                        )
                    }
                }.distinctUntilChanged()

            @Suppress("LongParameterList")
            internal fun create(
                guildId: Long,
                roleId: Long,
                me: MeUser,
                guild: Guild?,
                member: GuildMember?,
                roles: Map<Long, GuildRole>,
                permissions: Long?,
            ): Model? {
                val role = roles[roleId] ?: return null
                if (guild == null || member == null) return null
                val owner = guild.ownerId == me.id
                val elevated = PermissionUtils.isElevated(me.mfaEnabled, guild.mfaLevel)
                val highestRole = RoleUtils.getHighestRole(roles, member)
                return Model(
                    owner,
                    guildId,
                    role,
                    manageStatus(owner, elevated, permissions, highestRole, role),
                    permissions,
                    permissionsWithoutRole(member.getRoles(), roles, roleId, guildId),
                    GuildFeature.COMMUNITY in guild.features,
                )
            }

            internal fun manageStatus(
                owner: Boolean,
                elevated: Boolean,
                permissions: Long?,
                highestRole: GuildRole?,
                role: GuildRole,
            ): ManageStatus = when {
                owner || PermissionUtils.can(Permission.ADMINISTRATOR, permissions) &&
                    RoleUtils.rankIsHigher(highestRole, role) && elevated -> ManageStatus.CAN_MANAGE_ADMIN
                RoleUtils.rankIsHigher(role, highestRole) -> ManageStatus.LOCKED_HIGHER
                RoleUtils.rankEquals(highestRole, role) -> ManageStatus.LOCKED_HIGHEST
                PermissionUtils.can(Permission.MANAGE_ROLES, permissions) && elevated ->
                    ManageStatus.CAN_MANAGE_CONDITIONAL
                !elevated -> ManageStatus.USER_NOT_ELEVATED
                else -> ManageStatus.NO_MANAGE_ROLES_PERMISSION
            }

            internal fun permissionsWithoutRole(
                roleIds: Collection<Long>,
                roles: Map<Long, GuildRole>,
                ignoredRoleId: Long,
                guildId: Long,
            ): Long = roleIds.asSequence()
                .plus(guildId)
                .filter { it != ignoredRoleId }
                .mapNotNull(roles::get)
                .fold(0L) { permissions, role -> permissions or role.permissions }
        }
    }

    object ManageStatus {
        val NO_MANAGE_ROLES_PERMISSION = Model.ManageStatus.NO_MANAGE_ROLES_PERMISSION
        val LOCKED_HIGHER = Model.ManageStatus.LOCKED_HIGHER
        val LOCKED_HIGHEST = Model.ManageStatus.LOCKED_HIGHEST
        val USER_NOT_ELEVATED = Model.ManageStatus.USER_NOT_ELEVATED
        val CAN_MANAGE_CONDITIONAL = Model.ManageStatus.CAN_MANAGE_CONDITIONAL
        val CAN_MANAGE_ADMIN = Model.ManageStatus.CAN_MANAGE_ADMIN
    }

    companion object {
        private const val DIALOG_TAG_COLOR_PICKER = "DIALOG_TAG_COLOR_PICKER"
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INTENT_EXTRA_ROLE_ID = "INTENT_EXTRA_ROLE_ID"
        private const val INVALID_ID = -1L

        private val PERMISSIONS = mapOf(
            R.id.role_settings_create_instant_invite to Permission.CREATE_INSTANT_INVITE,
            R.id.role_settings_kick_members to Permission.KICK_MEMBERS,
            R.id.role_settings_ban_members to Permission.BAN_MEMBERS,
            R.id.role_settings_administrator to Permission.ADMINISTRATOR,
            R.id.role_settings_manage_channels to Permission.MANAGE_CHANNELS,
            R.id.role_settings_manage_server to Permission.MANAGE_GUILD,
            R.id.role_settings_add_reactions to Permission.ADD_REACTIONS,
            R.id.role_settings_view_audit_log to Permission.VIEW_AUDIT_LOG,
            R.id.role_settings_priority_speaker to Permission.PRIORITY_SPEAKER,
            R.id.role_settings_voice_video to Permission.STREAM,
            R.id.role_settings_read_messages to Permission.VIEW_CHANNEL,
            R.id.role_settings_send_messages to Permission.SEND_MESSAGES,
            R.id.role_settings_send_tts_messages to Permission.SEND_TTS_MESSAGES,
            R.id.role_settings_manage_messages to Permission.MANAGE_MESSAGES,
            R.id.role_settings_embed_links to Permission.EMBED_LINKS,
            R.id.role_settings_attach_files to Permission.ATTACH_FILES,
            R.id.role_settings_read_message_history to Permission.READ_MESSAGE_HISTORY,
            R.id.role_settings_mention_everyone to Permission.MENTION_EVERYONE,
            R.id.role_settings_use_external_emojis to Permission.USE_EXTERNAL_EMOJIS,
            R.id.role_settings_view_guild_analytics to Permission.VIEW_GUILD_ANALYTICS,
            R.id.role_settings_voice_connect to Permission.CONNECT,
            R.id.role_settings_voice_speak to Permission.SPEAK,
            R.id.role_settings_voice_mute_members to Permission.MUTE_MEMBERS,
            R.id.role_settings_voice_deafen_members to Permission.DEAFEN_MEMBERS,
            R.id.role_settings_voice_move_members to Permission.MOVE_MEMBERS,
            R.id.role_settings_voice_use_voice_activity to Permission.USE_VAD,
            R.id.role_settings_change_nickname to Permission.CHANGE_NICKNAME,
            R.id.role_settings_manage_nicknames to Permission.MANAGE_NICKNAMES,
            R.id.role_settings_manage_roles to Permission.MANAGE_ROLES,
            R.id.role_settings_manage_webhooks to Permission.MANAGE_WEBHOOKS,
            R.id.role_settings_manage_emojis_and_stickers to Permission.MANAGE_EMOJIS_AND_STICKERS,
            R.id.role_settings_use_application_commands to Permission.USE_APPLICATION_COMMANDS,
            R.id.role_settings_request_to_speak to Permission.REQUEST_TO_SPEAK,
            R.id.role_settings_manage_events to Permission.MANAGE_EVENTS,
            R.id.role_settings_manage_threads to Permission.MANAGE_THREADS,
            R.id.role_settings_create_public_threads to Permission.CREATE_PUBLIC_THREADS,
            R.id.role_settings_create_private_threads to Permission.CREATE_PRIVATE_THREADS,
            R.id.role_settings_use_external_stickers to Permission.USE_EXTERNAL_STICKERS,
            R.id.role_settings_send_messages_in_threads to Permission.SEND_MESSAGES_IN_THREADS,
            R.id.role_settings_moderate_member to Permission.MODERATE_MEMBERS,
        )

        fun launch(guildId: Long, roleId: Long, context: Context) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsEditRole::class.java,
                Intent()
                    .putExtra(INTENT_EXTRA_GUILD_ID, guildId)
                    .putExtra(INTENT_EXTRA_ROLE_ID, roleId),
            )
        }
    }
}
