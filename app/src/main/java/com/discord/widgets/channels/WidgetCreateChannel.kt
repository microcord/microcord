package com.discord.widgets.channels

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.api.guild.GuildFeature
import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.channel.guildChannelIcon
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.views.CheckedSetting
import com.discord.views.RadioManager
import com.discord.widgets.channels.permissions.WidgetCreateChannelAddMember
import com.discord.widgets.forums.ForumUtils
import com.google.android.material.textfield.TextInputLayout
import rx.Observable
import rx.functions.Func6

class WidgetCreateChannel : AppFragment(R.layout.widget_create_channel) {
    private val guildId get() = extraLong(INTENT_GUILD_ID, -1L)
    private val initialType get() = extraInt(INTENT_TYPE, Channel.GUILD_TEXT)
    private val categoryId get() = extraLongOrNull(INTENT_CATEGORY_ID)
    private val provideResultOnly get() = extraBoolean(INTENT_PROVIDE_RESULT_ONLY)
    private val shouldDefaultPrivate get() = extraBoolean(INTENT_SHOULD_CHANNEL_DEFAULT_PRIVATE)
    private val disablePrivateSwitch get() = extraBoolean(INTENT_DISABLE_PRIVATE_SWITCH)
    private val disableAnnouncementType get() = extraBoolean(INTENT_DISABLE_ANNOUNCEMENT_CHANNEL_TYPE)

    private lateinit var name: TextInputLayout
    private lateinit var privateContainer: View
    private lateinit var privateSwitch: SwitchCompat
    private lateinit var rolesHeader: View
    private lateinit var rolesDivider: View
    private lateinit var rolesRecycler: RecyclerView
    private lateinit var typeContainer: View
    private lateinit var textRadio: RadioButton
    private lateinit var voiceRadio: RadioButton
    private lateinit var announcementRadio: RadioButton
    private lateinit var stageRadio: RadioButton
    private lateinit var forumRadio: RadioButton
    private lateinit var rolesAdapter: RolesAdapter
    private var model: Model? = null

    private val channelType: Int
        get() = when {
            initialType == Channel.CATEGORY -> Channel.CATEGORY
            voiceRadio.isChecked -> Channel.GUILD_VOICE
            announcementRadio.isChecked -> Channel.GUILD_ANNOUNCEMENT
            stageRadio.isChecked -> Channel.GUILD_STAGE_VOICE
            forumRadio.isChecked -> Channel.GUILD_FORUM
            else -> Channel.GUILD_TEXT
        }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        name = view.findViewById(R.id.create_channel_name_layout)
        privateContainer = view.findViewById(R.id.create_channel_private_container)
        privateSwitch = view.findViewById(R.id.create_channel_private_switch)
        rolesHeader = view.findViewById(R.id.create_channel_role_header)
        rolesDivider = view.findViewById(R.id.create_channel_role_divider)
        rolesRecycler = view.findViewById(R.id.create_channel_role_access_recycler)
        typeContainer = view.findViewById(R.id.create_channel_type_container)
        textRadio = view.findViewById(R.id.create_channel_text_radio)
        voiceRadio = view.findViewById(R.id.create_channel_voice_radio)
        announcementRadio = view.findViewById(R.id.create_channel_announcements_radio)
        stageRadio = view.findViewById(R.id.create_channel_stage_radio)
        forumRadio = view.findViewById(R.id.create_channel_forum_radio)

        rolesAdapter = RolesAdapter()
        rolesRecycler.adapter = rolesAdapter
        rolesRecycler.setHasFixedSize(false)
        rolesRecycler.isNestedScrollingEnabled = false
        privateSwitch.isChecked = shouldDefaultPrivate || disablePrivateSwitch

        val radioManager = RadioManager(listOf(textRadio, voiceRadio, announcementRadio, stageRadio, forumRadio))
        val choices = mapOf(
            R.id.create_channel_text_container to textRadio,
            R.id.create_channel_voice_container to voiceRadio,
            R.id.create_channel_announcements_container to announcementRadio,
            R.id.create_channel_stage_container to stageRadio,
            R.id.create_channel_forum_container to forumRadio,
        )
        choices.forEach { (containerId, radio) ->
            view.findViewById<View>(containerId).setOnClickListener {
                radioManager.setChecked(radio)
                configurePrivateRoles()
            }
        }
        radioManager.setChecked(
            when (initialType) {
                Channel.GUILD_VOICE -> voiceRadio
                Channel.GUILD_ANNOUNCEMENT -> announcementRadio
                Channel.GUILD_STAGE_VOICE -> stageRadio
                Channel.GUILD_FORUM -> forumRadio
                else -> textRadio
            },
        )
        privateContainer.setOnClickListener {
            privateSwitch.isChecked = !privateSwitch.isChecked
            configurePrivateRoles()
        }
        configurePrivateRoles()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        Model.observe(guildId, categoryId).ui(this)
            .appSubscribe(WidgetCreateChannel::class.java) { configureUI(it) }
    }

    private fun configureUI(state: Model?) {
        if (state?.canCreate != true) {
            activity?.finish()
            return
        }
        model = state
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(if (initialType == Channel.CATEGORY) R.string.create_category else R.string.create_channel)
        setActionBarOptionsMenu(R.menu.menu_channel_create, onSelected = {
            if (it.itemId == R.id.menu_sort_channel) createChannel()
        })
        requireView().findViewById<View>(R.id.create_channel_announcements_container).isVisible =
            state.canCreateCommunityChannels && !disableAnnouncementType
        requireView().findViewById<View>(R.id.create_channel_stage_container).isVisible =
            state.canCreateCommunityChannels && state.canManageRoles
        requireView().findViewById<View>(R.id.create_channel_forum_container).isVisible = state.canCreateForumChannels
        typeContainer.isVisible = initialType != Channel.CATEGORY
        name.hint = getString(if (initialType == Channel.CATEGORY) R.string.category_name else R.string.form_label_channel_name)
        rolesAdapter.submit(state.roles)
    }

    private fun createChannel() {
        val state = model ?: return
        RestAPI.api.createGuildChannel(
            guildId,
            RestAPIParams.CreateGuildChannel(
                type = channelType,
                id = null,
                name = name.getTextOrEmpty(),
                parentId = categoryId,
                permissionOverwrites = permissionOverwrites(
                    guildId,
                    channelType,
                    privateSwitch.isChecked,
                    disablePrivateSwitch && shouldDefaultPrivate,
                    rolesAdapter.selectedRoleIds,
                ),
                topic = null,
            ),
        ).restSubscribeOn(retry = false).map { it.body() }.filter { it != null }.map { it!! }
            .ui(this)
            .appSubscribe(javaClass, requireContext()) { onChannelCreated(it, state) }
    }

    private fun onChannelCreated(channel: Channel, state: Model) {
        if (provideResultOnly) {
            requireActivity().setResult(
                Activity.RESULT_OK,
                Intent()
                    .putExtra(RESULT_EXTRA_CHANNEL_ID, channel.id)
                    .putExtra(RESULT_EXTRA_CHANNEL_NAME, channel.getDisplayName())
                    .putExtra(RESULT_EXTRA_CHANNEL_ICON_RES_ID, guildChannelIcon(channel)),
            )
        } else {
            if (channel.type == Channel.GUILD_TEXT) ChannelSelector.getInstance().selectChannel(channel)
            if (channel.type == Channel.GUILD_STAGE_VOICE && state.canManageRoles) {
                WidgetCreateChannelAddMember.create(requireContext(), channel.id)
            }
        }
        hideKeyboard()
        activity?.finish()
    }

    private fun configurePrivateRoles() {
        val available = channelType != Channel.GUILD_STAGE_VOICE && !disablePrivateSwitch
        privateContainer.isVisible = available
        rolesDivider.isVisible = available
        rolesHeader.isVisible = available && privateSwitch.isChecked
        rolesRecycler.isVisible = available && privateSwitch.isChecked
    }

    private fun extraLong(key: String, default: Long) =
        arguments?.takeIf { it.containsKey(key) }?.getLong(key) ?: mostRecentIntent.getLongExtra(key, default)

    private fun extraLongOrNull(key: String): Long? =
        arguments?.takeIf { it.containsKey(key) }?.getLong(key)
            ?: mostRecentIntent.takeIf { it.hasExtra(key) }?.getLongExtra(key, 0L)

    private fun extraInt(key: String, default: Int) =
        arguments?.takeIf { it.containsKey(key) }?.getInt(key) ?: mostRecentIntent.getIntExtra(key, default)

    private fun extraBoolean(key: String) =
        arguments?.takeIf { it.containsKey(key) }?.getBoolean(key) ?: mostRecentIntent.getBooleanExtra(key, false)

    internal data class Model(
        val canCreateForumChannels: Boolean,
        val canCreate: Boolean,
        val canManageRoles: Boolean,
        val canCreateCommunityChannels: Boolean,
        val roles: List<GuildRole>,
    ) {
        companion object {
            fun observe(guildId: Long, categoryId: Long?): Observable<Model?> {
                val stores = StoreStream
                return Observable.combineLatest(
                    stores.getUsers().observeMe(),
                    stores.getGuilds().observeGuild(guildId),
                    stores.getPermissions().observePermissionsForGuild(guildId),
                    stores.getPermissions().observePermissionsForChannel(categoryId ?: 0L),
                    stores.getGuilds().observeSortedRoles(guildId),
                    ForumUtils.observeCanCreateForumChannels(guildId),
                    Func6<MeUser, Guild?, Long?, Long?, List<GuildRole>, Boolean, Model?> {
                            me, guild, guildPermissions, categoryPermissions, roles, canCreateForum ->
                        create(me, guild, guildPermissions, categoryPermissions, roles, canCreateForum)
                    },
                ).computationLatest().distinctUntilChanged()
            }

            internal fun create(
                me: MeUser,
                guild: Guild?,
                guildPermissions: Long?,
                categoryPermissions: Long?,
                roles: List<GuildRole>,
                canCreateForumChannels: Boolean,
            ): Model? {
                guild ?: return null
                val effectivePermissions = categoryPermissions ?: guildPermissions
                return Model(
                    canCreateForumChannels = canCreateForumChannels,
                    canCreate = PermissionUtils.canAndIsElevated(
                        Permission.MANAGE_CHANNELS,
                        effectivePermissions,
                        me.mfaEnabled,
                        guild.mfaLevel,
                    ),
                    canManageRoles = PermissionUtils.canAndIsElevated(
                        Permission.MANAGE_ROLES,
                        effectivePermissions,
                        me.mfaEnabled,
                        guild.mfaLevel,
                    ),
                    canCreateCommunityChannels = GuildFeature.COMMUNITY in guild.features,
                    roles = roles.filterNot { it.id == guild.id },
                )
            }
        }
    }

    private class RolesAdapter : RecyclerView.Adapter<RolesAdapter.Holder>() {
        private var roles = emptyList<GuildRole>()
        val selectedRoleIds = linkedSetOf<Long>()

        fun submit(value: List<GuildRole>) {
            roles = value
            selectedRoleIds.clear()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.view_checkable_role_list_item_themed, parent, false) as CheckedSetting,
        )

        override fun getItemCount() = roles.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val role = roles[position]
            holder.setting.setText(role.name)
            holder.setting.setTextColor(RoleUtils.getRoleColor(role, holder.setting.context))
            holder.setting.setChecked(role.id in selectedRoleIds)
            holder.setting.setContainerClickListener {
                if (!selectedRoleIds.add(role.id)) selectedRoleIds.remove(role.id)
                notifyItemChanged(position)
            }
        }

        class Holder(val setting: CheckedSetting) : RecyclerView.ViewHolder(setting)
    }

    companion object {
        private const val INTENT_CATEGORY_ID = "INTENT_CATEGORY_ID"
        private const val INTENT_DISABLE_ANNOUNCEMENT_CHANNEL_TYPE = "INTENT_DISABLE_ANNOUNCEMENT_CHANNEL_TYPE"
        private const val INTENT_DISABLE_PRIVATE_SWITCH = "INTENT_DISABLE_PRIVATE_SWITCH"
        private const val INTENT_GUILD_ID = "INTENT_GUILD_ID"
        private const val INTENT_PROVIDE_RESULT_ONLY = "INTENT_PROVIDE_RESULT_ONLY"
        private const val INTENT_SHOULD_CHANNEL_DEFAULT_PRIVATE = "INTENT_SHOULD_CHANNEL_DEFAULT_PRIVATE"
        private const val INTENT_TYPE = "INTENT_TYPE"
        private const val RESULT_EXTRA_CHANNEL_ICON_RES_ID = "RESULT_EXTRA_CHANNEL_ICON_RES_ID"
        private const val RESULT_EXTRA_CHANNEL_ID = "RESULT_EXTRA_CHANNEL_ID"
        private const val RESULT_EXTRA_CHANNEL_NAME = "RESULT_EXTRA_CHANNEL_NAME"

        @JvmStatic
        @JvmOverloads
        fun show(context: Context, guildId: Long, type: Int = Channel.GUILD_TEXT, categoryId: Long? = null) =
            AppScreenRouter.launch(context, WidgetCreateChannel::class.java, extras(guildId, type, categoryId))

        @JvmStatic
        @JvmOverloads
        fun launch(
            context: Context,
            launcher: ActivityResultLauncher<Intent>,
            guildId: Long,
            type: Int = Channel.GUILD_TEXT,
            categoryId: Long? = null,
            shouldChannelDefaultPrivate: Boolean = false,
            provideResultOnly: Boolean = false,
            disablePrivateSwitch: Boolean = false,
            disableAnnouncementChannelType: Boolean = false,
        ) = AppScreenRouter.launchForResult(
            context,
            launcher,
            WidgetCreateChannel::class.java,
            extras(guildId, type, categoryId)
                .putExtra(INTENT_SHOULD_CHANNEL_DEFAULT_PRIVATE, shouldChannelDefaultPrivate)
                .putExtra(INTENT_PROVIDE_RESULT_ONLY, provideResultOnly)
                .putExtra(INTENT_DISABLE_PRIVATE_SWITCH, disablePrivateSwitch)
                .putExtra(INTENT_DISABLE_ANNOUNCEMENT_CHANNEL_TYPE, disableAnnouncementChannelType),
        )

        @JvmStatic
        fun registerForResult(
            fragment: AppFragment,
            callback: (Long, String, Int) -> Unit,
        ): ActivityResultLauncher<Intent> = fragment.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val data = result.data ?: return@registerForActivityResult
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val id = data.getLongExtra(RESULT_EXTRA_CHANNEL_ID, -1L)
            val name = data.getStringExtra(RESULT_EXTRA_CHANNEL_NAME)
            val icon = data.getIntExtra(RESULT_EXTRA_CHANNEL_ICON_RES_ID, -1)
            if (id != -1L && !name.isNullOrBlank() && icon != -1) callback(id, name, icon)
        }

        internal fun permissionOverwrites(
            guildId: Long,
            channelType: Int,
            privateSwitchChecked: Boolean,
            forcedPrivate: Boolean,
            selectedRoleIds: Set<Long>,
        ): List<RestAPIParams.ChannelPermissionOverwrites>? {
            val privateChannel = channelType != Channel.GUILD_STAGE_VOICE && privateSwitchChecked || forcedPrivate
            if (!privateChannel || guildId in selectedRoleIds) return null
            val access = if (channelType == Channel.GUILD_VOICE || channelType == Channel.GUILD_STAGE_VOICE) {
                Permission.CONNECT
            } else {
                Permission.VIEW_CHANNEL
            }
            return buildList {
                add(RestAPIParams.ChannelPermissionOverwrites.createForRole(guildId, 0L, access))
                selectedRoleIds.forEach {
                    add(RestAPIParams.ChannelPermissionOverwrites.createForRole(it, access, 0L))
                }
            }
        }

        private fun extras(guildId: Long, type: Int, categoryId: Long?) = Intent()
            .putExtra(INTENT_GUILD_ID, guildId)
            .putExtra(INTENT_TYPE, type)
            .apply { categoryId?.let { putExtra(INTENT_CATEGORY_ID, it) } }
    }
}
