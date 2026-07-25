package com.discord.widgets.channels.settings

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import com.discord.R
import com.discord.models.domain.ModelNotificationSettings
import com.discord.views.CheckedSetting
import com.discord.widgets.servers.NotificationMuteSettingsView
import com.discord.widgets.settings.OptimisticMutation
import rx.Observable
import rx.Subscription
import rx.functions.Action1

class WidgetChannelNotificationSettings : Fragment(R.layout.widget_channel_notification_settings) {
    data class State(
        val channelId: Long,
        val channelName: String,
        val muted: Boolean = false,
        val muteEndTime: String? = null,
        val messageNotifications: Int = ModelNotificationSettings.FREQUENCY_UNSET,
        val showSystemSettings: Boolean = false,
    )

    private var subscription: Subscription? = null
    private var optimistic: OptimisticMutation<State>? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<Toolbar>(R.id.action_bar_toolbar)?.apply {
            setTitle(R.string.notification_settings)
            setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        }
        view.findViewById<View>(R.id.channel_notification_settings_system).setOnClickListener {
            onOpenSystemSettings?.invoke(requireChannelId())
        }
    }

    override fun onStart() {
        super.onStart()
        subscription = stateSource?.invoke(requireChannelId())?.distinctUntilChanged()?.subscribe { state ->
            val mutation = optimistic ?: OptimisticMutation(state, ::render, ::showError).also { optimistic = it }
            mutation.accept(state)
        }
    }

    override fun onStop() { subscription?.unsubscribe(); subscription = null; super.onStop() }

    private fun render(state: State) {
        val root = view ?: return
        root.findViewById<Toolbar>(R.id.action_bar_toolbar)?.subtitle = state.channelName
        val showSystemSettings = state.showSystemSettings || requireArguments().getBoolean(ARG_SHOW_SYSTEM)
        root.findViewById<View>(R.id.channel_notification_settings_system).visibility = if (showSystemSettings) View.VISIBLE else View.GONE
        root.findViewById<NotificationMuteSettingsView>(R.id.mute_settings).updateView(
            NotificationMuteSettingsView.ViewState(
                state.muted, state.muteEndTime, "Mute ${state.channelName}", "Unmute ${state.channelName}",
                getString(R.string.form_label_mobile_channel_muted), R.string.form_label_mobile_channel_muted_until,
                getString(R.string.form_label_mobile_channel_override_mute),
            ),
            { update(state.copy(muted = true)) }, { update(state.copy(muted = false, muteEndTime = null)) },
        )
        bind(root, R.id.frequency_radio_all, state.messageNotifications == 0, 0)
        bind(root, R.id.frequency_radio_mentions, state.messageNotifications == 1, 1)
        bind(root, R.id.frequency_radio_nothing, state.messageNotifications == 2, 2)
    }

    private fun bind(root: View, id: Int, checked: Boolean, frequency: Int) {
        root.findViewById<CheckedSetting>(id).apply {
            setOnCheckedListener(null); isChecked = checked
            setOnCheckedListener(Action1 { if (it) update(requireNotNull(optimistic).state.copy(messageNotifications = frequency)) })
        }
    }

    private fun update(next: State) = optimistic?.submit(next) { value, done ->
        onMutation?.invoke(value.channelId, value) { result ->
            view?.post { done(result) } ?: done(result)
        } ?: done(Result.success(Unit))
    }

    private fun showError(error: Throwable) {
        view?.post { onError?.invoke(error) ?: Toast.makeText(requireContext(), R.string.notification_settings_update_failed, Toast.LENGTH_SHORT).show() }
    }

    private fun requireChannelId() = requireArguments().getLong(ARG_CHANNEL_ID)

    companion object {
        const val ROUTE = "channel/{channelId}/notifications"
        private const val ARG_CHANNEL_ID = "channel_id"
        @JvmField var stateSource: ((Long) -> Observable<State>)? = null
        @JvmField var onMutation: ((Long, State, (Result<Unit>) -> Unit) -> Unit)? = null
        @JvmField var onOpenSystemSettings: ((Long) -> Unit)? = null
        @JvmField var onError: ((Throwable) -> Unit)? = null
        fun newInstance(channelId: Long, showSystemSettings: Boolean = false) = WidgetChannelNotificationSettings().apply {
            arguments = Bundle().apply { putLong(ARG_CHANNEL_ID, channelId); putBoolean(ARG_SHOW_SYSTEM, showSystemSettings) }
        }
        private const val ARG_SHOW_SYSTEM = "show_system_settings"
    }
}
