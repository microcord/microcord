package com.discord.stores

import android.content.SharedPreferences
import com.discord.api.channel.Channel
import com.discord.rtcconnection.mediaengine.MediaEngineConnection
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.cache.getStringEntrySetAsMap
import com.discord.utilities.cache.putStringEntrySetAsMap
import com.discord.utilities.rx.computationLatest
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.SerializedSubject

/**
 * Every local voice/video preference, and the only writer of them.
 *
 * The whole surface is one immutable [VoiceConfiguration] behind a single subject: a setter builds
 * the next value, publishes it and writes it through to [VoiceConfigurationCache] in one step, so a
 * preference can never be observed in a state it was not persisted in. All mutators are
 * `@Synchronized` because they are called from UI callbacks, the media engine thread and the store
 * dispatcher alike — this store predates `StoreV2` and does not run on the dispatcher.
 *
 * [forceSelfMuteReason] is the one piece of derived state: when the user has no permission to use
 * voice activity in the selected channel and their input mode *is* voice activity, they are forced
 * muted and cannot unmute until they switch to push-to-talk.
 */
class StoreMediaSettings(
    /** `StoreChannels.findChannelByIdInternal`; only used to detect a private call. */
    private val findChannel: (channelId: Long) -> Channel? = { null },
    private val voiceConfigurationCache: VoiceConfigurationCache =
        VoiceConfigurationCache(SharedPreferencesProvider.get()),
    /**
     * `AppSoundManager.play(...)`. Injected because `utilities/media` does not exist yet; muting is
     * silent until it does.
     */
    private val playSound: (VoiceSound) -> Unit = {},
) : Store() {

    /** Which local mute/deafen feedback sound a toggle should play. */
    enum class VoiceSound { MUTE, UNMUTE, DEAFEN, UNDEAFEN }

    enum class NoiseProcessing {
        None,
        Suppression,
        Cancellation,
        CancellationTemporarilyDisabled,
    }

    enum class SelfMuteFailure { CANNOT_USE_VAD }

    enum class VadUseKrisp {
        Disabled,
        Enabled,
        TemporarilyDisabled,
    }

    data class VoiceConfiguration(
        private val isSelfMuted: Boolean = false,
        val isSelfDeafened: Boolean = false,
        val automaticVad: Boolean = true,
        val vadUseKrisp: VadUseKrisp = VadUseKrisp.Enabled,
        val automaticGainControl: Boolean = true,
        val echoCancellation: Boolean = true,
        val noiseProcessing: NoiseProcessing = NoiseProcessing.Cancellation,
        val sensitivity: Float = DEFAULT_SENSITIVITY,
        val inputMode: MediaEngineConnection.InputMode = MediaEngineConnection.InputMode.VOICE_ACTIVITY,
        val outputVolume: Float = DEFAULT_OUTPUT_VOLUME,
        val mutedUsers: Map<Long, Boolean> = emptyMap(),
        val userOutputVolumes: Map<Long, Float> = emptyMap(),
        val offScreenUsers: Map<Long, Boolean> = emptyMap(),
        val enableVideoHardwareScaling: Boolean = false,
        val voiceParticipantsHidden: Boolean = false,
    ) {
        /**
         * Deafened implies muted: nobody should be able to hear you while you cannot hear them.
         * The raw flag stays private so no caller can read the un-implied value by accident.
         */
        fun isSelfMuted(): Boolean = isSelfMuted || isSelfDeafened

        /** The raw toggle position, used only to decide what a mute toggle should do next. */
        internal val isSelfMutedRaw: Boolean get() = isSelfMuted

        companion object {
            const val DEFAULT_OUTPUT_VOLUME = 100f
            const val DEFAULT_SENSITIVITY = -50f
            val DEFAULT_VOICE_CONFIG = VoiceConfiguration()
            val DEFAULT_NOISE_PROCESSING = DEFAULT_VOICE_CONFIG.noiseProcessing.ordinal
        }
    }

    private var canUseVad: Boolean = true
    private var forceSelfMuteReason: SelfMuteFailure? = null

    private val voiceConfigurationSubject =
        SerializedSubject<VoiceConfiguration, VoiceConfiguration>(
            BehaviorSubject.create(VoiceConfiguration.DEFAULT_VOICE_CONFIG),
        )

    private var voiceConfiguration: VoiceConfiguration = VoiceConfiguration.DEFAULT_VOICE_CONFIG
        set(value) {
            field = value
            voiceConfigurationSubject.onNext(value)
            voiceConfigurationCache.write(value)
        }

    /** Loads the persisted configuration. Must run before anything observes the store. */
    fun init() {
        voiceConfiguration = voiceConfigurationCache.read()
    }

    /**
     * Reports whether voice activity is usable in the selected channel.
     *
     * Wired from `StoreVoiceChannelSelected.observeSelectedVoiceChannelId()` ->
     * `StoreChannels.observeChannel` -> `StorePermissions.observePermissionsForChannel`, which the
     * store graph owner supplies; private and stage channels are always allowed.
     */
    @Synchronized
    fun handleCanUseVad(canUseVad: Boolean) {
        this.canUseVad = canUseVad
        updateForceMute()
    }

    @Synchronized
    private fun updateForceMute() {
        forceSelfMuteReason =
            if (!canUseVad && voiceConfiguration.inputMode == MediaEngineConnection.InputMode.VOICE_ACTIVITY) {
                SelfMuteFailure.CANNOT_USE_VAD
            } else {
                null
            }
        if (forceSelfMuteReason != null) {
            voiceConfiguration = voiceConfiguration.copy(isSelfMuted = true)
        }
    }

    /** Joining a private call always undeafens: there is no moderation there to hide from. */
    @StoreThread
    fun handleVoiceChannelSelected(channelId: Long) {
        val channel = findChannel(channelId) ?: return
        if (channel.type == Channel.DM || channel.type == Channel.GROUP_DM) setSelfDeafen(false)
    }

    // region Observation

    fun getVoiceConfig(): Observable<VoiceConfiguration> =
        voiceConfigurationSubject.computationLatest().distinctUntilChanged()

    fun getVoiceConfiguration(): Observable<VoiceConfiguration> = voiceConfigurationSubject

    @Synchronized
    fun getVoiceConfigurationBlocking(): VoiceConfiguration = voiceConfiguration

    fun getInputMode(): Observable<MediaEngineConnection.InputMode> =
        voiceConfigurationSubject.map { it.inputMode }.computationLatest().distinctUntilChanged()

    fun getUsersMuted(): Observable<Map<Long, Boolean>> =
        voiceConfigurationSubject.map { it.mutedUsers }.computationLatest().distinctUntilChanged()

    fun getUsersOffScreen(): Observable<Map<Long, Boolean>> =
        voiceConfigurationSubject.map { it.offScreenUsers }.computationLatest().distinctUntilChanged()

    fun getUsersVolume(): Observable<Map<Long, Float>> =
        voiceConfigurationSubject.map { it.userOutputVolumes }.computationLatest().distinctUntilChanged()

    fun isSelfMuted(): Observable<Boolean> =
        voiceConfigurationSubject.map { it.isSelfMuted() }.computationLatest().distinctUntilChanged()

    fun isSelfDeafened(): Observable<Boolean> =
        voiceConfigurationSubject.map { it.isSelfDeafened }.computationLatest().distinctUntilChanged()

    @Synchronized
    fun getMutedUsers(): Map<Long, Boolean> = voiceConfiguration.mutedUsers

    @Synchronized
    fun getVideoHardwareScalingBlocking(): Boolean = voiceConfiguration.enableVideoHardwareScaling

    // endregion

    // region Mutation

    /**
     * @return the reason the request was refused, or `null` on success. A forced mute cannot be
     *   lifted by the user, so the caller can surface why the button did nothing.
     */
    @Synchronized
    fun setSelfMuted(isMuted: Boolean): SelfMuteFailure? {
        if (voiceConfiguration.isSelfMutedRaw == isMuted) return null
        forceSelfMuteReason?.let { return it }
        // Unmuting while deafened must also undeafen, otherwise `isSelfMuted()` would immediately
        // re-imply mute and the button would appear stuck.
        voiceConfiguration = voiceConfiguration.copy(
            isSelfMuted = isMuted,
            isSelfDeafened = voiceConfiguration.isSelfDeafened && isMuted,
        )
        playSound(if (isMuted) VoiceSound.MUTE else VoiceSound.UNMUTE)
        return null
    }

    @Synchronized
    fun toggleSelfMuted(): SelfMuteFailure? = setSelfMuted(!voiceConfiguration.isSelfMutedRaw)

    @Synchronized
    fun setSelfDeafen(isDeafened: Boolean) {
        if (voiceConfiguration.isSelfDeafened == isDeafened) return
        voiceConfiguration = voiceConfiguration.copy(isSelfDeafened = isDeafened)
        playSound(if (isDeafened) VoiceSound.DEAFEN else VoiceSound.UNDEAFEN)
    }

    @Synchronized
    fun toggleSelfDeafened() = setSelfDeafen(!voiceConfiguration.isSelfDeafened)

    @Synchronized
    fun toggleUserMuted(userId: Long) {
        val mutedUsers = HashMap(voiceConfiguration.mutedUsers)
        mutedUsers[userId] = !(mutedUsers[userId] ?: false)
        voiceConfiguration = voiceConfiguration.copy(mutedUsers = mutedUsers)
    }

    @Synchronized
    fun setUserOutputVolume(userId: Long, volume: Float) {
        val volumes = HashMap(voiceConfiguration.userOutputVolumes)
        volumes[userId] = volume
        voiceConfiguration = voiceConfiguration.copy(userOutputVolumes = volumes)
    }

    @Synchronized
    fun setUserOffScreen(userId: Long, offScreen: Boolean) {
        val offScreenUsers = HashMap(voiceConfiguration.offScreenUsers)
        offScreenUsers[userId] = offScreen
        voiceConfiguration = voiceConfiguration.copy(offScreenUsers = offScreenUsers)
    }

    @Synchronized
    fun setOutputVolume(outputVolume: Float) {
        voiceConfiguration = voiceConfiguration.copy(outputVolume = outputVolume)
    }

    @Synchronized
    fun setSensitivity(sensitivity: Float) {
        voiceConfiguration = voiceConfiguration.copy(sensitivity = sensitivity)
    }

    @Synchronized
    fun setVoiceInputMode(inputMode: MediaEngineConnection.InputMode) {
        voiceConfiguration = voiceConfiguration.copy(inputMode = inputMode)
        // Switching to push-to-talk can lift a VAD-permission forced mute, and switching back can
        // re-apply it.
        updateForceMute()
    }

    @Synchronized
    fun setNoiseProcessing(noiseProcessing: NoiseProcessing) {
        if (noiseProcessing == voiceConfiguration.noiseProcessing) return
        voiceConfiguration = voiceConfiguration.copy(noiseProcessing = noiseProcessing)
    }

    @Synchronized
    fun toggleNoiseCancellation() = setNoiseProcessing(
        if (voiceConfiguration.noiseProcessing == NoiseProcessing.Cancellation) {
            NoiseProcessing.Suppression
        } else {
            NoiseProcessing.Cancellation
        },
    )

    @Synchronized
    fun toggleNoiseSuppression() = setNoiseProcessing(
        if (voiceConfiguration.noiseProcessing == NoiseProcessing.Suppression) {
            NoiseProcessing.None
        } else {
            NoiseProcessing.Suppression
        },
    )

    @Synchronized
    fun setVADUseKrisp(status: VadUseKrisp) {
        if (status == voiceConfiguration.vadUseKrisp) return
        voiceConfiguration = voiceConfiguration.copy(vadUseKrisp = status)
    }

    @Synchronized
    fun toggleVADUseKrisp() = setVADUseKrisp(
        if (voiceConfiguration.vadUseKrisp == VadUseKrisp.Enabled) {
            VadUseKrisp.Disabled
        } else {
            VadUseKrisp.Enabled
        },
    )

    /**
     * Restores Krisp after it was switched off to survive an overuse spike.
     *
     * Only the *temporary* variants are reverted; a user who genuinely disabled Krisp keeps their
     * choice.
     */
    @Synchronized
    fun revertTemporaryDisableKrisp() {
        val vadUseKrisp =
            if (voiceConfiguration.vadUseKrisp == VadUseKrisp.TemporarilyDisabled) {
                VadUseKrisp.Enabled
            } else {
                voiceConfiguration.vadUseKrisp
            }
        val noiseProcessing =
            if (voiceConfiguration.noiseProcessing == NoiseProcessing.CancellationTemporarilyDisabled) {
                NoiseProcessing.Cancellation
            } else {
                voiceConfiguration.noiseProcessing
            }
        if (vadUseKrisp == voiceConfiguration.vadUseKrisp &&
            noiseProcessing == voiceConfiguration.noiseProcessing
        ) {
            return
        }
        voiceConfiguration = voiceConfiguration.copy(
            vadUseKrisp = vadUseKrisp,
            noiseProcessing = noiseProcessing,
        )
    }

    @Synchronized
    fun toggleAutomaticVAD() {
        voiceConfiguration = voiceConfiguration.copy(automaticVad = !voiceConfiguration.automaticVad)
    }

    @Synchronized
    fun toggleAutomaticGainControl() {
        voiceConfiguration =
            voiceConfiguration.copy(automaticGainControl = !voiceConfiguration.automaticGainControl)
    }

    @Synchronized
    fun toggleEchoCancellation() {
        voiceConfiguration =
            voiceConfiguration.copy(echoCancellation = !voiceConfiguration.echoCancellation)
    }

    @Synchronized
    fun toggleEnableVideoHardwareScaling() {
        voiceConfiguration = voiceConfiguration.copy(
            enableVideoHardwareScaling = !voiceConfiguration.enableVideoHardwareScaling,
        )
    }

    @Synchronized
    fun updateVoiceParticipantsHidden(hidden: Boolean) {
        if (voiceConfiguration.voiceParticipantsHidden == hidden) return
        voiceConfiguration = voiceConfiguration.copy(voiceParticipantsHidden = hidden)
    }

    // endregion
}

/**
 * SharedPreferences read/write for [StoreMediaSettings.VoiceConfiguration].
 *
 * Two fields are stored lossily on purpose, matching the reference:
 * - the "temporarily disabled" Krisp/noise variants are written as their *enabled* form, so a crash
 *   during an overuse spike does not permanently disable noise cancellation;
 * - `offScreenUsers` is never persisted — hiding a participant's video is per-session.
 *
 * Reference: `com.discord.stores.VoiceConfigurationCache`. It lives in this file rather than its own
 * because it is the only consumer's persistence handle and has no independent lifetime.
 */
class VoiceConfigurationCache(private val sharedPreferences: SharedPreferences) {

    private fun booleanFromVadUseKrisp(value: StoreMediaSettings.VadUseKrisp): Boolean =
        value != StoreMediaSettings.VadUseKrisp.Disabled

    private fun intFromNoiseProcessing(value: StoreMediaSettings.NoiseProcessing): Int =
        if (value == StoreMediaSettings.NoiseProcessing.CancellationTemporarilyDisabled) {
            StoreMediaSettings.NoiseProcessing.Cancellation.ordinal
        } else {
            value.ordinal
        }

    private fun readNoiseProcessing(): StoreMediaSettings.NoiseProcessing {
        val ordinal = sharedPreferences.getInt(
            KEY_NOISE_PROCESSING,
            StoreMediaSettings.VoiceConfiguration.DEFAULT_NOISE_PROCESSING,
        )
        return StoreMediaSettings.NoiseProcessing.entries.getOrNull(ordinal)
            ?: StoreMediaSettings.NoiseProcessing.Cancellation
    }

    private fun readVadUseKrisp(): StoreMediaSettings.VadUseKrisp {
        val default = StoreMediaSettings.VoiceConfiguration.DEFAULT_VOICE_CONFIG.vadUseKrisp ==
            StoreMediaSettings.VadUseKrisp.Enabled
        return if (sharedPreferences.getBoolean(KEY_VAD_USE_KRISP, default)) {
            StoreMediaSettings.VadUseKrisp.Enabled
        } else {
            StoreMediaSettings.VadUseKrisp.Disabled
        }
    }

    fun read(): StoreMediaSettings.VoiceConfiguration {
        val defaults = StoreMediaSettings.VoiceConfiguration.DEFAULT_VOICE_CONFIG
        val inputModeNumeral =
            sharedPreferences.getInt(KEY_INPUT_MODE, defaults.inputMode.numeral)
        return StoreMediaSettings.VoiceConfiguration(
            isSelfMuted = sharedPreferences.getBoolean(KEY_IS_MUTED, defaults.isSelfMuted()),
            isSelfDeafened = sharedPreferences.getBoolean(KEY_IS_DEAFENED, defaults.isSelfDeafened),
            automaticVad = sharedPreferences.getBoolean(KEY_AUTOMATIC_VAD, defaults.automaticVad),
            vadUseKrisp = readVadUseKrisp(),
            automaticGainControl =
            sharedPreferences.getBoolean(KEY_AUTOMATIC_GAIN_CONTROL, defaults.automaticGainControl),
            echoCancellation =
            sharedPreferences.getBoolean(KEY_ECHO_CANCELLATION, defaults.echoCancellation),
            noiseProcessing = readNoiseProcessing(),
            sensitivity = sharedPreferences.getFloat(KEY_SENSITIVITY, defaults.sensitivity),
            inputMode = MediaEngineConnection.InputMode.entries
                .firstOrNull { it.numeral == inputModeNumeral }
                ?: MediaEngineConnection.InputMode.VOICE_ACTIVITY,
            outputVolume = sharedPreferences.getFloat(KEY_OUTPUT_VOLUME, defaults.outputVolume),
            mutedUsers = sharedPreferences.getStringEntrySetAsMap(
                KEY_MUTED_USERS,
                ENTRY_DELIMITER,
            ) { key, value -> key.toLong() to value.toBoolean() },
            userOutputVolumes = sharedPreferences.getStringEntrySetAsMap(
                KEY_USER_OUTPUT_VOLUMES,
                ENTRY_DELIMITER,
            ) { key, value -> key.toLong() to value.toFloat() },
            enableVideoHardwareScaling =
            sharedPreferences.getBoolean(KEY_VIDEO_HARDWARE_SCALING, defaults.enableVideoHardwareScaling),
            voiceParticipantsHidden =
            sharedPreferences.getBoolean(KEY_HIDE_VOICE_PARTICIPANTS, defaults.voiceParticipantsHidden),
        )
    }

    fun write(voiceConfiguration: StoreMediaSettings.VoiceConfiguration) {
        val editor = sharedPreferences.edit()
        editor.putBoolean(KEY_IS_MUTED, voiceConfiguration.isSelfMuted())
        editor.putBoolean(KEY_IS_DEAFENED, voiceConfiguration.isSelfDeafened)
        editor.putBoolean(KEY_AUTOMATIC_VAD, voiceConfiguration.automaticVad)
        editor.putBoolean(KEY_VAD_USE_KRISP, booleanFromVadUseKrisp(voiceConfiguration.vadUseKrisp))
        editor.putBoolean(KEY_AUTOMATIC_GAIN_CONTROL, voiceConfiguration.automaticGainControl)
        editor.putInt(KEY_NOISE_PROCESSING, intFromNoiseProcessing(voiceConfiguration.noiseProcessing))
        editor.putBoolean(KEY_ECHO_CANCELLATION, voiceConfiguration.echoCancellation)
        editor.putFloat(KEY_SENSITIVITY, voiceConfiguration.sensitivity)
        editor.putInt(KEY_INPUT_MODE, voiceConfiguration.inputMode.numeral)
        editor.putFloat(KEY_OUTPUT_VOLUME, voiceConfiguration.outputVolume)
        editor.putStringEntrySetAsMap(
            KEY_MUTED_USERS,
            voiceConfiguration.mutedUsers,
            ENTRY_DELIMITER,
        ) { it.toString() }
        editor.putStringEntrySetAsMap(
            KEY_USER_OUTPUT_VOLUMES,
            voiceConfiguration.userOutputVolumes,
            ENTRY_DELIMITER,
        ) { it.toString() }
        editor.putBoolean(KEY_VIDEO_HARDWARE_SCALING, voiceConfiguration.enableVideoHardwareScaling)
        editor.putBoolean(KEY_HIDE_VOICE_PARTICIPANTS, voiceConfiguration.voiceParticipantsHidden)
        editor.apply()
    }

    private companion object {
        private const val ENTRY_DELIMITER = ","
        private const val KEY_IS_MUTED = "CACHE_KEY_VOICE_SETTINGS_IS_MUTED"
        private const val KEY_IS_DEAFENED = "CACHE_KEY_VOICE_SETTINGS_IS_DEAFENED"
        private const val KEY_AUTOMATIC_VAD = "CACHE_KEY_VOICE_SETTINGS_AUTOMATIC_VAD"
        private const val KEY_VAD_USE_KRISP = "CACHE_KEY_VOICE_SETTINGS_VAD_USE_KRISP"
        private const val KEY_AUTOMATIC_GAIN_CONTROL = "CACHE_KEY_VOICE_SETTINGS_AUTOMATIC_GAIN_CONTROL"
        private const val KEY_NOISE_PROCESSING = "CACHE_KEY_VOICE_SETTINGS_NOISE_PROCESSING"
        private const val KEY_ECHO_CANCELLATION = "CACHE_KEY_VOICE_SETTINGS_ECHO_CANCELLATION"
        private const val KEY_SENSITIVITY = "CACHE_KEY_VOICE_SETTINGS_SENSITIVITY"
        private const val KEY_INPUT_MODE = "CACHE_KEY_VOICE_SETTINGS_INPUT_MODE"
        private const val KEY_OUTPUT_VOLUME = "CACHE_KEY_VOICE_SETTINGS_OUTPUT_VOLUME"
        private const val KEY_MUTED_USERS = "MUTED_USERS_V2"
        private const val KEY_USER_OUTPUT_VOLUMES = "USER_OUTPUT_VOLUMES_V2"
        private const val KEY_VIDEO_HARDWARE_SCALING = "VIDEO_ENABLE_HARDWARE_SCALING"
        private const val KEY_HIDE_VOICE_PARTICIPANTS = "CACHE_KEY_HIDE_VOICE_PARTICIPANTS"
    }
}
