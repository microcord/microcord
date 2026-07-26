package com.discord.widgets.user.usersheet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import com.discord.app.AppBottomSheet
import com.discord.R
import com.discord.api.user.UserProfile
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.member.GuildMember
import com.discord.models.presence.Presence
import com.discord.models.user.User
import com.discord.widgets.user.profile.UserProfileAdminView
import com.discord.widgets.user.profile.UserProfileConnectionsView
import com.discord.widgets.user.profile.UserProfileHeaderView
import com.discord.widgets.user.profile.UserProfileHeaderViewModel
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import rx.Observable
import rx.Subscription

class WidgetUserSheet : AppBottomSheet() {
    sealed class State {
        object Loading : State()
        data class Error(val message: String? = null) : State()
        data class Loaded(
            val user: User,
            val profile: UserProfile,
            val presence: Presence? = null,
            val note: NoteState = NoteState.Loading,
            val relationshipType: Int = ModelUserRelationship.TYPE_NONE,
            val guildMember: GuildMember? = null,
            val guildMembersForAka: List<GuildMember> = emptyList(),
            val connections: UserProfileConnectionsView.ViewState = UserProfileConnectionsView.ViewState(false, false, emptyList()),
            val admin: UserProfileAdminView.ViewState? = null,
            val isMe: Boolean = false,
            val showPresence: Boolean = true,
        ) : State()
    }

    sealed class NoteState {
        object Loading : NoteState()
        data class Loaded(val value: String) : NoteState()
        data class Error(val value: String = "") : NoteState()
    }

    enum class RelationshipAction { ADD, ACCEPT, IGNORE, REMOVE, BLOCK, UNBLOCK }
    enum class AdminAction { EDIT, KICK, BAN, TIMEOUT, MUTE, DEAFEN, MOVE, DISCONNECT }

    private var subscription: Subscription? = null
    private var loaded: State.Loaded? = null
    private var noteDirty = false
    private var settingNote = false
    private var errorText: TextView? = null

    override fun getContentViewResId() = R.layout.widget_user_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val note = view.findViewById<TextInputEditText>(R.id.user_sheet_note_text_field)
        note.setOnFocusChangeListener { _, focused -> if (!focused) saveNote() }
        note.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { saveNote(); note.clearFocus(); true } else false
        }
        note.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!settingNote) noteDirty = true }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        view.findViewById<View>(R.id.user_sheet_message_action_button).setOnClickListener { loaded?.user?.id?.let { onMessage?.invoke(it) } }
        view.findViewById<View>(R.id.user_sheet_call_action_button).setOnClickListener { loaded?.user?.id?.let { onCall?.invoke(it, false) } }
        view.findViewById<View>(R.id.user_sheet_video_action_button).setOnClickListener { loaded?.user?.id?.let { onCall?.invoke(it, true) } }
        view.findViewById<View>(R.id.user_sheet_add_friend_action_button).setOnClickListener { relationship(RelationshipAction.ADD) }
        view.findViewById<View>(R.id.user_sheet_pending_friend_request_action_button).setOnClickListener { relationship(RelationshipAction.REMOVE) }
        view.findViewById<View>(R.id.user_sheet_friend_request_accept_button).setOnClickListener { relationship(RelationshipAction.ACCEPT) }
        view.findViewById<View>(R.id.user_sheet_friend_request_ignore_button).setOnClickListener { relationship(RelationshipAction.IGNORE) }
        view.findViewById<View>(R.id.user_sheet_more_button).setOnClickListener {
            loaded?.let { state ->
                if (onMore != null) onMore?.invoke(state.user.id, state.relationshipType) else showMore(state)
            }
        }
        bindAdmin(view.findViewById(R.id.user_sheet_admin_view))
        listOf(
            R.id.about_me_header, R.id.user_sheet_profile_voice_settings_header, R.id.user_sheet_guild_header,
            R.id.user_sheet_connections_header, R.id.user_sheet_note_header, R.id.user_sheet_developer_header,
            R.id.user_sheet_incoming_friend_request_header,
        ).forEach { ViewCompat.setAccessibilityHeading(view.findViewById(it), true) }
    }

    override fun onStart() {
        super.onStart()
        subscription = (stateSource?.invoke(requireArguments().getLong(ARG_USER_ID)) ?: Observable.just(State.Loading))
            .distinctUntilChanged().subscribe { next ->
                view?.post { if (view != null && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) show(next) }
            }
    }

    override fun onStop() {
        saveNote()
        subscription?.unsubscribe()
        subscription = null
        super.onStop()
    }

    private fun show(state: State) {
        val root = requireView()
        val loading = root.findViewById<FrameLayout>(R.id.user_sheet_loading_container)
        val content = root.findViewById<View>(R.id.user_sheet_content)
        loading.visibility = if (state is State.Loaded) View.GONE else View.VISIBLE
        content.visibility = if (state is State.Loaded) View.VISIBLE else View.INVISIBLE
        root.findViewById<ProgressBar>(R.id.loading_progress_bar).visibility = if (state is State.Loading) View.VISIBLE else View.GONE
        errorText?.let(loading::removeView)
        errorText = null
        if (state is State.Error) {
            errorText = TextView(requireContext()).apply {
                text = state.message ?: getString(R.string.user_profile_failure_to_open_message)
                gravity = android.view.Gravity.CENTER
                setPadding(32, 32, 32, 32)
                contentDescription = text
                loading.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        if (state !is State.Loaded) return
        loaded = state
        root.findViewById<View>(R.id.user_sheet_profile_stage_actions_card).visibility = View.GONE
        root.findViewById<View>(R.id.user_sheet_profile_stage_actions_view).visibility = View.GONE
        root.findViewById<View>(R.id.user_sheet_profile_voice_settings_header).visibility = View.GONE
        root.findViewById<View>(R.id.voice_settings_view_card).visibility = View.GONE
        root.findViewById<View>(R.id.rich_presence_container).visibility = View.GONE
        root.findViewById<View>(R.id.user_sheet_guild_container).visibility = View.GONE
        root.findViewById<View>(R.id.user_sheet_developer_header).visibility = View.GONE
        root.findViewById<View>(R.id.user_sheet_copy_id).visibility = View.GONE
        root.findViewById<View>(R.id.user_sheet_profile_add_to_server_container).visibility = View.GONE
        root.findViewById<View>(R.id.user_sheet_profile_edit_container).visibility = View.GONE
        root.findViewById<View>(R.id.user_sheet_profile_actions_divider).visibility = View.GONE
        showHeader(state)
        showRelationship(state)
        showNote(state.note)
        showConnections(state)
        showAdmin(state)
        root.findViewById<TextView>(R.id.about_me_text).apply {
            text = state.guildMember?.bio ?: state.user.bio
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        root.findViewById<View>(R.id.about_me_card).visibility = root.findViewById<View>(R.id.about_me_text).visibility
        root.findViewById<View>(R.id.about_me_header).visibility = root.findViewById<View>(R.id.about_me_text).visibility
    }

    private fun showHeader(state: State.Loaded) {
        requireView().findViewById<UserProfileHeaderView>(R.id.user_sheet_profile_header_view).updateViewState(
            UserProfileHeaderViewModel.ViewState.Loaded(
                state.user, state.guildMember?.bannerHash ?: state.user.banner, state.user.bannerColor,
                state.guildMember, state.guildMembersForAka, state.presence, null, state.profile,
                isMeUserPremium = false, isMeUserVerified = false, allowAnimatedEmojis = true,
                showPresence = state.showPresence, editable = state.isMe, reducedMotionEnabled = false,
                allowAnimationInReducedMotion = false, isMe = state.isMe,
            ),
        )
    }

    private fun showRelationship(state: State.Loaded) {
        val root = requireView()
        val actionable = !state.isMe && !state.user.isSystemUser
        root.findViewById<View>(R.id.user_sheet_profile_actions_container).visibility = if (actionable) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.user_sheet_message_action_button).visibility = if (actionable) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.user_sheet_call_action_button).visibility = if (actionable && onCall != null) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.user_sheet_video_action_button).visibility = if (actionable && onCall != null) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.user_sheet_add_friend_action_button).visibility =
            if (actionable && state.relationshipType == ModelUserRelationship.TYPE_NONE) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.user_sheet_pending_friend_request_action_button).visibility =
            if (state.relationshipType == ModelUserRelationship.TYPE_INVITE_OUTGOING) View.VISIBLE else View.GONE
        val incoming = state.relationshipType == ModelUserRelationship.TYPE_INVITE_INCOMING
        root.findViewById<View>(R.id.user_sheet_incoming_friend_request_header).visibility = if (incoming) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.user_sheet_friend_request_incoming_container).visibility = if (incoming) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.user_sheet_more_button).visibility = if (actionable) View.VISIBLE else View.GONE
    }

    private fun showNote(note: NoteState) {
        val wrap = requireView().findViewById<TextInputLayout>(R.id.user_sheet_note_text_field_wrap)
        val input = requireView().findViewById<TextInputEditText>(R.id.user_sheet_note_text_field)
        wrap.isEnabled = note !is NoteState.Loading
        wrap.hint = getString(if (note is NoteState.Loading) R.string.loading_note else R.string.note_placeholder_mobile)
        val value = when (note) { is NoteState.Loaded -> note.value; is NoteState.Error -> note.value; NoteState.Loading -> null }
        if (!noteDirty && value != null && input.text?.toString() != value) {
            settingNote = true
            input.setText(value)
            settingNote = false
        }
    }

    private fun showConnections(state: State.Loaded) {
        val visible = state.connections.showConnectionsSection
        requireView().findViewById<View>(R.id.user_sheet_connections_header).visibility = if (visible) View.VISIBLE else View.GONE
        requireView().findViewById<UserProfileConnectionsView>(R.id.user_sheet_connections_view).apply {
            visibility = if (visible) View.VISIBLE else View.GONE
            updateViewState(state.connections, { name, _, url -> onConnectedAccount?.invoke(name, url) },
                {
                    dismissAllowingStateLoss()
                    onMutualGuilds?.invoke(state.user.id)
                }, {
                    dismissAllowingStateLoss()
                    onMutualFriends?.invoke(state.user.id)
                })
        }
    }

    private fun showAdmin(state: State.Loaded) {
        val visible = state.admin?.isAdminSectionEnabled == true
        requireView().findViewById<View>(R.id.user_sheet_admin_card).visibility = if (visible) View.VISIBLE else View.GONE
        requireView().findViewById<UserProfileAdminView>(R.id.user_sheet_admin_view).apply {
            visibility = if (visible) View.VISIBLE else View.GONE
            state.admin?.let(::updateView)
        }
    }

    private fun bindAdmin(view: UserProfileAdminView) {
        view.setOnEditMember { admin(AdminAction.EDIT) }; view.setOnKick { admin(AdminAction.KICK) }
        view.setOnBan { admin(AdminAction.BAN) }; view.setOnDisableCommunication { admin(AdminAction.TIMEOUT) }
        view.setOnServerMute { admin(AdminAction.MUTE) }; view.setOnServerDeafen { admin(AdminAction.DEAFEN) }
        view.setOnServerMove { admin(AdminAction.MOVE) }; view.setOnDisconnect { admin(AdminAction.DISCONNECT) }
    }

    private fun saveNote() {
        if (!noteDirty) return
        loaded?.user?.id?.let { onNoteSave?.invoke(it, view?.findViewById<TextInputEditText>(R.id.user_sheet_note_text_field)?.text?.toString().orEmpty()) }
        noteDirty = false
    }
    private fun relationship(action: RelationshipAction) = loaded?.user?.id?.let { onRelationshipAction?.invoke(it, action) } ?: Unit
    private fun admin(action: AdminAction) = loaded?.user?.id?.let { onAdminAction?.invoke(it, action) } ?: Unit

    private fun showMore(state: State.Loaded) {
        val actions = when (state.relationshipType) {
            ModelUserRelationship.TYPE_BLOCKED -> listOf(R.string.unblock to RelationshipAction.UNBLOCK)
            ModelUserRelationship.TYPE_FRIEND -> listOf(
                R.string.remove_friend to RelationshipAction.REMOVE,
                R.string.block to RelationshipAction.BLOCK,
            )
            else -> listOf(R.string.block to RelationshipAction.BLOCK)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, index ->
                onRelationshipAction?.invoke(state.user.id, actions[index].second)
            }.show()
    }

    companion object {
        private const val ARG_USER_ID = "ARG_USER_ID"
        @JvmField var stateSource: ((Long) -> Observable<State>)? = null
        @JvmField var onMessage: ((Long) -> Unit)? = null
        @JvmField var onCall: ((Long, Boolean) -> Unit)? = null
        @JvmField var onRelationshipAction: ((Long, RelationshipAction) -> Unit)? = null
        @JvmField var onNoteSave: ((Long, String) -> Unit)? = null
        @JvmField var onMutualFriends: ((Long) -> Unit)? = null
        @JvmField var onMutualGuilds: ((Long) -> Unit)? = null
        @JvmField var onConnectedAccount: ((String, String?) -> Unit)? = null
        @JvmField var onAdminAction: ((Long, AdminAction) -> Unit)? = null
        @JvmField var onMore: ((Long, Int) -> Unit)? = null

        fun show(userId: Long, fragmentManager: androidx.fragment.app.FragmentManager) = WidgetUserSheet().apply {
            arguments = Bundle().apply { putLong(ARG_USER_ID, userId) }
        }.show(fragmentManager, WidgetUserSheet::class.java.name)
    }
}
