package com.discord.dialogs.audio

import android.view.View
import android.widget.RadioGroup
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.AppViewModel
import com.discord.app.appViewModels
import com.discord.rtcconnection.audio.DiscordAudioManager
import com.discord.stores.StoreAudioManagerV2
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.color.setDrawableColor
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.google.android.material.radiobutton.MaterialRadioButton
import java.util.concurrent.TimeUnit
import rx.Observable

/**
 * Speaker / earpiece / wired / Bluetooth chooser for a call.
 *
 * Only devices the platform currently reports as available are shown, so on a phone with nothing
 * plugged in this is two rows. Picking one applies it immediately and then closes after a short
 * delay — the delay is deliberate, it lets the radio button's check animation finish so the user
 * sees what they selected rather than the dialog vanishing mid-tap.
 */
class AudioOutputSelectionDialog : AppDialog(R.layout.audio_output_selection_dialog) {

    private val viewModel: AudioOutputSelectionDialogViewModel by appViewModels {
        AudioOutputSelectionDialogViewModel()
    }

    private lateinit var radioGroup: RadioGroup
    private lateinit var bluetoothRadio: MaterialRadioButton
    private lateinit var speakerRadio: MaterialRadioButton
    private lateinit var wiredRadio: MaterialRadioButton
    private lateinit var earpieceRadio: MaterialRadioButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        radioGroup = view.findViewById(R.id.audio_output_selection_dialog_radio_group)
        bluetoothRadio = view.findViewById(R.id.audio_output_selection_bluetooth_radio)
        speakerRadio = view.findViewById(R.id.audio_output_selection_speaker_radio)
        wiredRadio = view.findViewById(R.id.audio_output_selection_wired_radio)
        earpieceRadio = view.findViewById(R.id.audio_output_selection_earpiece_radio)

        // The row icons ship untinted so they can follow whichever surface hosts the chooser.
        val tint = ColorCompat.getThemedColor(view, R.attr.colorInteractiveNormal)
        for (radio in listOf(bluetoothRadio, speakerRadio, wiredRadio, earpieceRadio)) {
            radio.setDrawableColor(tint)
        }

        bluetoothRadio.setOnClickListener { select(DiscordAudioManager.DeviceTypes.BLUETOOTH_HEADSET) }
        speakerRadio.setOnClickListener { select(DiscordAudioManager.DeviceTypes.SPEAKERPHONE) }
        wiredRadio.setOnClickListener { select(DiscordAudioManager.DeviceTypes.WIRED_HEADSET) }
        earpieceRadio.setOnClickListener { select(DiscordAudioManager.DeviceTypes.EARPIECE) }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .ui(this)
            .appSubscribe(AudioOutputSelectionDialog::class.java, context) { state ->
                if (state is AudioOutputSelectionDialogViewModel.ViewState.Loaded) configureUI(state)
            }
    }

    private fun select(device: DiscordAudioManager.DeviceTypes) {
        viewModel.selectOutputDevice(device)
        dismissAfterSelectionAnimation()
    }

    private fun dismissAfterSelectionAnimation() {
        Observable.timer(DISMISS_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            .ui(this)
            .appSubscribe(AudioOutputSelectionDialog::class.java, context) { dialog?.cancel() }
    }

    private fun configureUI(state: AudioOutputSelectionDialogViewModel.ViewState.Loaded) {
        val selectedRadio = when (state.selectedAudioOutput) {
            DiscordAudioManager.DeviceTypes.SPEAKERPHONE -> speakerRadio
            DiscordAudioManager.DeviceTypes.WIRED_HEADSET -> wiredRadio
            DiscordAudioManager.DeviceTypes.EARPIECE -> earpieceRadio
            DiscordAudioManager.DeviceTypes.BLUETOOTH_HEADSET -> bluetoothRadio
            // DEFAULT and INVALID mean "nothing routed yet"; leave the group untouched.
            else -> null
        }
        selectedRadio?.let { radioGroup.check(it.id) }

        bluetoothRadio.visibility = if (state.showBluetoothItem) View.VISIBLE else View.GONE
        bluetoothRadio.text = state.bluetoothDeviceName ?: getString(R.string.audio_devices_bluetooth)
        speakerRadio.visibility = if (state.showSpeakerItem) View.VISIBLE else View.GONE
        wiredRadio.visibility = if (state.showWiredItem) View.VISIBLE else View.GONE
        earpieceRadio.visibility = if (state.showEarpieceItem) View.VISIBLE else View.GONE
    }

    companion object {
        private const val DISMISS_DELAY_MILLIS = 300L

        fun show(fragmentManager: FragmentManager) {
            AudioOutputSelectionDialog().show(
                fragmentManager,
                AudioOutputSelectionDialog::class.java.name,
            )
        }
    }
}

/**
 * Projects [StoreAudioManagerV2.State] into the four rows the dialog can show.
 *
 * The store keeps its device list indexed by [DiscordAudioManager.DeviceTypes.value], not by device
 * type, so availability is read positionally — the same way the reference does it, and the reason
 * the enum's `value` ordering is load-bearing.
 */
class AudioOutputSelectionDialogViewModel(
    private val storeAudioManager: StoreAudioManagerV2 = StoreStream.getAudioManagerV2(),
    storeStateObservable: Observable<StoreAudioManagerV2.State> = storeAudioManager.observeAudioManagerState(),
) : AppViewModel<AudioOutputSelectionDialogViewModel.ViewState>(ViewState.Uninitialized) {

    sealed class ViewState {
        object Uninitialized : ViewState()

        data class Loaded(
            val selectedAudioOutput: DiscordAudioManager.DeviceTypes,
            val showBluetoothItem: Boolean,
            val bluetoothDeviceName: String?,
            val showSpeakerItem: Boolean,
            val showWiredItem: Boolean,
            val showEarpieceItem: Boolean,
        ) : ViewState()
    }

    init {
        storeStateObservable
            .computationLatest()
            .ui(this)
            .appSubscribe(AudioOutputSelectionDialogViewModel::class.java) { handleStoreState(it) }
    }

    fun selectOutputDevice(device: DiscordAudioManager.DeviceTypes) {
        storeAudioManager.selectOutputDevice(device)
    }

    private fun handleStoreState(storeState: StoreAudioManagerV2.State) {
        val devices = storeState.audioDevices
        val bluetooth = devices.getOrNull(DiscordAudioManager.DeviceTypes.BLUETOOTH_HEADSET.value)
        updateViewState(
            ViewState.Loaded(
                selectedAudioOutput = storeState.activeAudioDevice,
                showBluetoothItem = bluetooth?.isAvailable == true,
                bluetoothDeviceName = bluetooth?.name,
                showSpeakerItem = devices
                    .getOrNull(DiscordAudioManager.DeviceTypes.SPEAKERPHONE.value)?.isAvailable == true,
                showWiredItem = devices
                    .getOrNull(DiscordAudioManager.DeviceTypes.WIRED_HEADSET.value)?.isAvailable == true,
                showEarpieceItem = devices
                    .getOrNull(DiscordAudioManager.DeviceTypes.EARPIECE.value)?.isAvailable == true,
            ),
        )
    }

}
