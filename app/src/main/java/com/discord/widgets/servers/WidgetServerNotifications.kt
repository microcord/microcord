package com.discord.widgets.servers

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import com.discord.app.AppFragment
import com.discord.R
import com.discord.models.domain.ModelNotificationSettings
import com.discord.views.CheckedSetting
import com.discord.widgets.settings.OptimisticMutation
import rx.Observable
import rx.Subscription
import rx.functions.Action1

class WidgetServerNotifications : AppFragment(R.layout.widget_server_notifications) {
    data class State(
        val guildId: Long,
        val guildName: String,
        val muted: Boolean = false,
        val muteEndTime: String? = null,
        val messageNotifications: Int = ModelNotificationSettings.FREQUENCY_UNSET,
        val mobilePush: Boolean = true,
        val suppressEveryone: Boolean = false,
        val suppressRoles: Boolean = false,
    )

    private var subscription: Subscription? = null
    private var optimistic: OptimisticMutation<State>? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<Toolbar>(R.id.action_bar_toolbar)?.apply {
            setTitle(R.string.notification_settings)
            setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        }
        view.findViewById<View>(R.id.add_override).setOnClickListener { onAddOverride?.invoke(requireGuildId()) }
    }

    override fun onStart() {
        super.onStart()
        subscription = stateSource?.invoke(requireGuildId())?.distinctUntilChanged()?.subscribe { state ->
            val mutation = optimistic ?: OptimisticMutation(state, ::render, ::showError).also { optimistic = it }
            mutation.accept(state)
        }
    }

    override fun onStop() { subscription?.unsubscribe(); subscription = null; super.onStop() }

    private fun render(state: State) {
        val root = view ?: return
        root.findViewById<Toolbar>(R.id.action_bar_toolbar)?.subtitle = state.guildName
        root.findViewById<NotificationMuteSettingsView>(R.id.server_notifications_mute_settings_view).updateView(
            NotificationMuteSettingsView.ViewState(
                state.muted, state.muteEndTime, "Mute ${state.guildName}", "Unmute ${state.guildName}",
                getString(R.string.form_label_mobile_server_muted), R.string.form_label_mobile_server_muted_until,
                getString(R.string.form_label_mute_server_description),
            ),
            { update(state.copy(muted = true)) }, { update(state.copy(muted = false, muteEndTime = null)) },
        )
        bind(root, R.id.server_notifications_frequency_0_radio, state.messageNotifications == 0) { state.copy(messageNotifications = 0) }
        bind(root, R.id.server_notifications_frequency_1_radio, state.messageNotifications == 1) { state.copy(messageNotifications = 1) }
        bind(root, R.id.server_notifications_frequency_2_radio, state.messageNotifications == 2) { state.copy(messageNotifications = 2) }
        bind(root, R.id.server_notifications_everyone_switch, state.suppressEveryone) { state.copy(suppressEveryone = it) }
        bind(root, R.id.server_notifications_roles_switch, state.suppressRoles) { state.copy(suppressRoles = it) }
        root.findViewById<CheckedSetting>(R.id.server_notifications_push_switch).apply {
            setOnCheckedListener(null)
            isChecked = state.mobilePush && !state.muted
            if (state.muted) setDisabledTooltip(null)
            else setContainerClickListener { update(state.copy(mobilePush = !state.mobilePush)) }
        }
    }

    private fun bind(root: View, id: Int, checked: Boolean, next: (Boolean) -> State) {
        root.findViewById<CheckedSetting>(id).apply {
            setOnCheckedListener(null); isChecked = checked
            setOnCheckedListener(Action1 { update(next(it)) })
        }
    }

    private fun update(next: State) = optimistic?.submit(next) { value, done ->
        onMutation?.invoke(value.guildId, value) { result ->
            view?.post { done(result) } ?: done(result)
        } ?: done(Result.success(Unit))
    }

    private fun showError(error: Throwable) {
        view?.post { onError?.invoke(error) ?: Toast.makeText(requireContext(), R.string.notification_settings_update_failed, Toast.LENGTH_SHORT).show() }
    }

    private fun requireGuildId() = requireArguments().getLong(ARG_GUILD_ID)

    companion object {
        const val ROUTE = "guild/{guildId}/notifications"
        private const val ARG_GUILD_ID = "guild_id"
        @JvmField var stateSource: ((Long) -> Observable<State>)? = null
        @JvmField var onMutation: ((Long, State, (Result<Unit>) -> Unit) -> Unit)? = null
        @JvmField var onAddOverride: ((Long) -> Unit)? = null
        @JvmField var onError: ((Throwable) -> Unit)? = null
        fun newInstance(guildId: Long) = WidgetServerNotifications().apply {
            arguments = Bundle().apply { putLong(ARG_GUILD_ID, guildId) }
        }
    }
}
