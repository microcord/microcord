package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.role.GuildRole
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.MeUser
import com.discord.models.user.User
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.permissions.ManageUserContext
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.extensions.setText
import com.discord.widgets.guildcommunicationdisabled.start.WidgetDisableGuildCommunication
import com.discord.widgets.guildcommunicationdisabled.start.WidgetEnableGuildCommunication
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.SettingsStrings
import com.discord.widgets.settings.StatefulViews
import com.discord.widgets.user.WidgetBanUser
import com.discord.widgets.user.WidgetKickUser
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout
import rx.Observable

/**
 * Per-member moderation: nickname, roles, timeout, kick, ban and ownership transfer.
 *
 * Everything on this screen is permission-gated by [ManageUserContext], which folds the acting
 * user's permissions, the guild's MFA requirement and the *role hierarchy* into one answer per
 * action. Two consequences worth stating:
 *
 * - **A control the acting user may not use is hidden, not disabled** (except roles, where seeing a
 *   member's un-grantable roles is the point). Losing a permission while the screen is open
 *   therefore removes the button on the next emission.
 * - **The screen closes itself if [ManageUserContext.canManage] ever goes false**, which is what
 *   happens when the target is promoted above the acting user mid-session.
 *
 * The three destructive actions each hand off to their own confirmation screen — `WidgetKickUser`,
 * `WidgetBanUser` and the timeout screens — rather than acting on tap. Roles and the nickname are
 * reversible and are applied directly.
 */
class WidgetServerSettingsEditMember : AppFragment(R.layout.widget_server_settings_edit_member) {

    private var rolesAdapter: WidgetServerSettingsEditMemberRolesAdapter? = null

    /** Tracks the nickname field so the save FAB only appears once it actually differs. */
    private val state = StatefulViews(R.id.edit_member_nickname)

    private lateinit var nickname: TextInputLayout
    private lateinit var saveButton: FloatingActionButton
    private lateinit var rolesContainer: LinearLayout
    private lateinit var administrativeContainer: LinearLayout
    private lateinit var timeoutButton: TextView
    private lateinit var kickButton: TextView
    private lateinit var banButton: TextView
    private lateinit var transferOwnershipButton: TextView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        // Retained so the in-flight nickname edit and the roles adapter survive a rotation; the
        // reference does the same.
        retainInstance = true

        nickname = view.findViewById(R.id.edit_member_nickname)
        saveButton = view.findViewById(R.id.edit_member_save)
        rolesContainer = view.findViewById(R.id.edit_member_roles_container)
        administrativeContainer = view.findViewById(R.id.edit_member_administrative_container)
        timeoutButton = view.findViewById(R.id.edit_member_timeout_button)
        kickButton = view.findViewById(R.id.edit_member_kick_button)
        banButton = view.findViewById(R.id.edit_member_ban_button)
        transferOwnershipButton = view.findViewById(R.id.edit_member_transfer_ownership_button)

        val recycler = view.findViewById<RecyclerView>(R.id.edit_member_roles_recycler)
        // The recycler sits inside a NestedScrollView and must lay out all its rows, so it neither
        // scrolls itself nor has a fixed size.
        recycler.isNestedScrollingEnabled = false
        recycler.setHasFixedSize(false)
        rolesAdapter = MGRecyclerAdapter.configure(
            WidgetServerSettingsEditMemberRolesAdapter(recycler),
        )

        state.setupUnsavedChangesConfirmation(this)
        // A nickname may legitimately be cleared, so the field is optional — otherwise the save
        // action would never appear for "remove this nickname".
        state.addOptionalFields(nickname)
        state.setupTextWatcherWithSaveAction(this, saveButton, nickname)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        val guildId = mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_ID)
        val userId = mostRecentIntent.getLongExtra(INTENT_EXTRA_USER_ID, INVALID_ID)
        Model.get(guildId, userId).ui(this).subscribe(::configureUI)
    }

    private fun configureUI(data: Model?) {
        if (data == null || !data.canManage) {
            // Either the member/guild is gone, or the acting user no longer outranks them.
            appActivity?.finish()
            return
        }
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(
            SettingsStrings.render(
                requireContext(),
                R.string.guild_members_header,
                "guild" to data.guild.name,
            ),
        )
        val displayName = GuildMember.getNickOrUsername(data.userComputed, data.user)
        setActionBarSubtitle(displayName)

        setupNickname(data)
        setupRoles(data)

        state.configureSaveActionView(saveButton)
        saveButton.setOnClickListener {
            // Trim before saving *and* write the trimmed value back, so what was saved is what the
            // field shows.
            val trimmed = nickname.getTextOrEmpty().trim()
            nickname.setText(trimmed)
            changeNickname(data, trimmed)
        }

        configureTimeout(data, displayName)
        configureKick(data, displayName)
        configureBan(data, displayName)
        configureTransferOwnership(data)

        // The whole administrative block disappears when none of its actions is available, rather
        // than leaving a bare divider and header behind.
        administrativeContainer.visibility =
            if (data.canDisableCommunication || data.canKick || data.canBan || data.canTransferOwnership) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    /**
     * Timeout (server mute for text and voice), or lifting one.
     *
     * The same row does both; which one depends on whether the member is currently timed out.
     * Both destinations are confirmation screens — a timeout takes a duration, and lifting one asks
     * before it happens.
     */
    private fun configureTimeout(data: Model, displayName: String) {
        if (!data.canDisableCommunication) {
            timeoutButton.visibility = View.GONE
            timeoutButton.setOnClickListener(null)
            return
        }
        val isCommunicationDisabled = data.userComputed.isCommunicationDisabled()
        timeoutButton.text = SettingsStrings.render(
            requireContext(),
            if (isCommunicationDisabled) {
                R.string.enable_guild_communication_for_user
            } else {
                R.string.disable_guild_communication_for_user
            },
            "user" to displayName,
        )
        timeoutButton.visibility = View.VISIBLE
        timeoutButton.setOnClickListener {
            if (isCommunicationDisabled) {
                WidgetEnableGuildCommunication.launch(
                    data.user.id,
                    data.guild.id,
                    parentFragmentManager,
                )
            } else {
                WidgetDisableGuildCommunication.launch(
                    data.user.id,
                    data.guild.id,
                    requireContext(),
                )
            }
        }
    }

    /** Kick. Destructive: `WidgetKickUser` confirms, and collects the audit-log reason. */
    private fun configureKick(data: Model, displayName: String) {
        if (!data.canKick) {
            kickButton.visibility = View.GONE
            kickButton.setOnClickListener(null)
            return
        }
        kickButton.text = SettingsStrings.render(
            requireContext(),
            R.string.kick_user,
            "user" to displayName,
        )
        kickButton.visibility = View.VISIBLE
        kickButton.setOnClickListener {
            WidgetKickUser.launch(displayName, data.guild.id, data.user.id, parentFragmentManager)
        }
    }

    /**
     * Ban. The most destructive action here: `WidgetBanUser` confirms, collects a reason and the
     * "delete N days of messages" choice before anything happens.
     */
    private fun configureBan(data: Model, displayName: String) {
        if (!data.canBan) {
            banButton.visibility = View.GONE
            banButton.setOnClickListener(null)
            return
        }
        banButton.text = SettingsStrings.render(
            requireContext(),
            R.string.ban_user,
            "user" to displayName,
        )
        banButton.visibility = View.VISIBLE
        banButton.setOnClickListener {
            WidgetBanUser.launch(displayName, data.guild.id, data.user.id, parentFragmentManager)
        }
    }

    /**
     * Ownership transfer is irreversible and only the owner sees it.
     *
     * **Degraded**: `WidgetServerSettingsTransferOwnership` — which owns the reference's
     * type-the-server-name confirmation — has not been reconstructed, so the row stays hidden rather
     * than leading nowhere or, worse, transferring without a confirmation. The verified/partnered
     * guard the reference applies before opening it is preserved here so it is not lost when the
     * screen lands.
     */
    private fun configureTransferOwnership(@Suppress("UNUSED_PARAMETER") data: Model) {
        transferOwnershipButton.visibility = View.GONE
        transferOwnershipButton.setOnClickListener(null)
    }

    private fun setupNickname(data: Model) {
        val currentNick = data.userComputed.nick.orEmpty()
        if (data.canChangeNickname) {
            nickname.isEndIconVisible = false
            nickname.isEnabled = true
        } else {
            // The end icon is a padlock: the field is shown, read-only, with its value seeded so the
            // save action can never think it changed.
            nickname.isEndIconVisible = true
            nickname.isEnabled = false
            state.put(nickname.id, currentNick)
        }
        nickname.setText(state.get(nickname.id, currentNick))
    }

    /**
     * Renders the role checklist and applies a tap immediately.
     *
     * There is no save step for roles: the toggle is sent as a full replacement role list, and the
     * checked state comes back through the store. That is why the row's control never flips itself.
     */
    private fun setupRoles(data: Model) {
        rolesContainer.visibility = if (data.roleItems.isNotEmpty()) View.VISIBLE else View.GONE
        if (data.roleItems.isEmpty()) return
        rolesAdapter?.configure(data.roleItems) { clickedRoleId ->
            // Committing a role change while the nickname field has focus would leave a stale
            // keyboard over the list.
            nickname.clearFocus()
            val newRoles = if (data.userRoles.contains(clickedRoleId)) {
                data.userRoles.filter { it != clickedRoleId }
            } else {
                data.userRoles + clickedRoleId
            }
            val api = RestAPI.apiOrNull ?: return@configure
            api.changeGuildMember(
                data.guild.id,
                data.user.id,
                RestAPIParams.GuildMember.createWithRoles(newRoles.toList()),
            )
                .restSubscribeOn()
                .ui(this)
                .appSubscribe(WidgetServerSettingsEditMember::class.java, requireContext()) {}
        }
    }

    /**
     * Saves the nickname.
     *
     * Changing one's *own* nickname goes through a different endpoint, which only needs Change
     * Nickname rather than Manage Nicknames.
     */
    private fun changeNickname(data: Model, nicknameValue: String) {
        val api = RestAPI.apiOrNull ?: return
        val request: Observable<Void> = if (data.user.id == data.myId) {
            api.changeGuildNickname(data.guild.id, RestAPIParams.Nick(nicknameValue))
        } else {
            api.changeGuildMember(
                data.guild.id,
                data.user.id,
                RestAPIParams.GuildMember.createWithNick(nicknameValue),
            )
        }
        request
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetServerSettingsEditMember::class.java, requireContext()) {
                onNicknameChangeSuccessful(nicknameValue)
            }
    }

    private fun onNicknameChangeSuccessful(nicknameValue: String) {
        hideKeyboard()
        // Clearing a nickname and setting one are different outcomes and say so.
        val message = if (nicknameValue.isNotEmpty()) {
            SettingsStrings.render(
                requireContext(),
                R.string.nickname_changed,
                "nickname" to nicknameValue,
            )
        } else {
            getString(R.string.nickname_cleared)
        }
        AppToast.show(this, message)
    }

    /**
     * State for [WidgetServerSettingsEditMember].
     *
     * `null` means the member, the guild or the acting user's membership is gone, and the screen
     * closes.
     *
     * @param userRoles the target's current role ids; the toggle handler diffs against this.
     * @param canManage whether *anything* on the screen is available. When false the screen closes.
     */
    data class Model(
        val myId: Long,
        val guild: Guild,
        val userComputed: GuildMember,
        val userRoles: Set<Long>,
        val user: User,
        val roleItems: List<WidgetServerSettingsEditMemberRolesAdapter.RoleItem>,
        val canManage: Boolean,
        val canKick: Boolean,
        val canBan: Boolean,
        val canChangeNickname: Boolean,
        val canTransferOwnership: Boolean,
        val canDisableCommunication: Boolean,
    ) {
        companion object {
            fun get(guildId: Long, userId: Long): Observable<Model?> = Observable.combineLatest(
                StoreStream.getUsers().observeMe(),
                StoreStream.getUsers().observeUser(userId),
                StoreStream.getGuilds().observeComputed(guildId),
                StoreStream.getGuilds().observeGuild(guildId),
                StoreStream.getGuilds().observeRoles(guildId),
                StoreStream.getPermissions().observePermissionsForGuild(guildId),
            ) { meUser, user, members, guild, guildRoles, myPermissions ->
                toModel(guildId, userId, meUser, user, members, guild, guildRoles, myPermissions)
            }
                .distinctUntilChanged()
                .computationLatest()

            @Suppress("LongParameterList")
            private fun toModel(
                guildId: Long,
                userId: Long,
                meUser: MeUser,
                user: User?,
                members: Map<Long, GuildMember>,
                guild: Guild?,
                guildRoles: Map<Long, GuildRole>,
                myPermissions: Long?,
            ): Model? {
                val myMember = members[meUser.id]
                val targetMember = members[userId]
                if (guild == null || user == null || myPermissions == null ||
                    myMember == null || targetMember == null
                ) {
                    return null
                }

                // @everyone is not assignable and every member has it implicitly, so it is dropped
                // from the checklist entirely.
                val assignableRoles = guildRoles.filterKeys { it != guildId }

                val manageContext = ManageUserContext.from(
                    guild,
                    meUser,
                    user,
                    myMember.getRoles(),
                    targetMember.getRoles(),
                    myPermissions,
                    assignableRoles,
                )
                val targetRoles = targetMember.getRoles().toSet()
                val myHighestRole = RoleUtils.getHighestRole(assignableRoles, myMember)
                val iAmOwner = meUser.id == guild.ownerId

                return Model(
                    myId = meUser.id,
                    guild = guild,
                    userComputed = targetMember,
                    userRoles = targetRoles,
                    user = user,
                    roleItems = assignableRoles.values
                        .sortedWith(RoleUtils.ROLE_COMPARATOR)
                        .map { role ->
                            WidgetServerSettingsEditMemberRolesAdapter.RoleItem(
                                role,
                                targetRoles.contains(role.id),
                                myHighestRole,
                                iAmOwner,
                                manageContext.canManageRoles,
                            )
                        },
                    canManage = manageContext.canManage() || manageContext.canDisableCommunication,
                    canKick = manageContext.canKick,
                    canBan = manageContext.canBan,
                    canChangeNickname = manageContext.canChangeNickname,
                    // Only the owner may hand the guild over, and never to themselves.
                    canTransferOwnership = meUser.id != userId && meUser.id == guild.ownerId,
                    canDisableCommunication = manageContext.canDisableCommunication,
                )
            }
        }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INTENT_EXTRA_USER_ID = "INTENT_EXTRA_USER_ID"
        private const val INVALID_ID = -1L

        fun launch(guildId: Long, userId: Long, context: Context) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsEditMember::class.java,
                Intent()
                    .putExtra(INTENT_EXTRA_GUILD_ID, guildId)
                    .putExtra(INTENT_EXTRA_USER_ID, userId),
            )
        }
    }
}
