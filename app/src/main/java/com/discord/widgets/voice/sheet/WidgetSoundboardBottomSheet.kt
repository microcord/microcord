package com.discord.widgets.voice.sheet

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.soundboard.SoundboardSound
import com.discord.app.AppBottomSheet
import com.discord.app.AppToast
import com.discord.databinding.WidgetSoundboardBottomSheetBinding
import com.discord.databinding.WidgetSoundboardHeaderItemBinding
import com.discord.databinding.WidgetSoundboardSoundItemBinding
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.soundboard.SoundboardConstants.DEFAULT_SOUND_GUILD_ID
import com.discord.utilities.soundboard.SoundboardUtils
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils.isPremiumTier2
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.settings.SettingsStrings
import com.discord.widgets.settings.premium.WidgetSettingsPremium
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.audio.AudioAttributes

class WidgetSoundboardBottomSheet : AppBottomSheet() {
    private var _binding: WidgetSoundboardBottomSheetBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val channelId get() = argumentsOrDefault.getLong(ARG_CHANNEL_ID)
    private val currentGuildId get() = argumentsOrDefault.getLong(ARG_GUILD_ID)
    private var sending = false
    private var soundPlayer: ExoPlayer? = null
    private var playingSoundId: Long? = null
    private var sounds: Map<Long, List<SoundboardSound>> = emptyMap()
    private var loaded = false
    private var playingSoundIds: Set<Long> = emptySet()
    private var query = ""
    private lateinit var soundAdapter: SoundAdapter

    override fun getContentViewResId() = R.layout.widget_soundboard_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = WidgetSoundboardBottomSheetBinding.bind(view)
        soundAdapter = SoundAdapter(::onSoundSelected)
        binding.soundboardSounds.layoutManager = GridLayoutManager(requireContext(), COLUMN_COUNT).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (soundAdapter.isHeader(position)) COLUMN_COUNT else 1
            }
        }
        binding.soundboardSounds.adapter = soundAdapter
        binding.soundboardSounds.itemAnimator = null
        binding.soundboardSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty()
                showSounds()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.soundboardSearchClear.setOnClickListener { binding.soundboardSearchInput.text?.clear() }
        observeSounds()
    }

    override fun onDestroyView() {
        stopSound()
        binding.soundboardSounds.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun observeSounds() {
        val soundboard = StoreStream.getSoundboard()
        soundboard.observeSounds()
            .ui(this)
            .bindToComponentLifecycle(this)
            .subscribe(
                { state ->
                    sounds = state.sounds
                    loaded = state.loaded
                    showSounds()
                },
                {},
            )
        soundboard.observePlayingSoundIds()
            .ui(this)
            .bindToComponentLifecycle(this)
            .subscribe({ playing ->
                playingSoundIds = playing
                showSounds()
            }, {})
        soundboard.maybeFetchSounds()
    }

    private fun hasNitro() = StoreStream.getUsers().getMe().isPremiumTier2

    private fun showSounds() {
        val seen = hashSetOf<Long>()
        val hasNitro = hasNitro()
        val channel = StoreStream.getChannels().getChannel(channelId)
        val permissions = StoreStream.getPermissions().getPermissionsByChannel()[channelId]
        fun filtered(sounds: List<SoundboardSound>) = sounds
            .filter { sound ->
                val id = sound.soundId
                id != null && seen.add(id) &&
                    (query.isBlank() || sound.name.orEmpty().contains(query, ignoreCase = true))
            }
            .sortedBy { it.available == false }
            .map { Row.Sound(it, SoundboardUtils.canUseSoundboardSound(it, hasNitro, channel, permissions)) }
        val rows = buildList {
            val guilds = StoreStream.getGuilds().getGuilds()
            val orderedGuildIds = StoreStream.getGuildsSorted().getOrderedGuilds().keys
            fun addGuildSection(guildId: Long) {
                filtered(sounds[guildId].orEmpty()).takeIf(List<*>::isNotEmpty)?.let { matches ->
                    add(Row.Header(guilds[guildId]?.name ?: guildId.toString()))
                    addAll(matches)
                }
            }
            fun addDefaultSection() {
                filtered(sounds[DEFAULT_SOUND_GUILD_ID].orEmpty()).takeIf(List<*>::isNotEmpty)?.let { defaults ->
                    add(Row.Header(getString(R.string.soundboard_default_sounds)))
                    addAll(defaults)
                }
            }
            val guildsWithSounds = sounds.keys.filter { it != DEFAULT_SOUND_GUILD_ID }
            if (currentGuildId in guildsWithSounds) addGuildSection(currentGuildId)
            // Without Nitro every other guild's section is locked, so the sounds that do work are
            // promoted above them rather than stranded at the bottom of the list.
            if (!hasNitro) addDefaultSection()
            orderedGuildIds.filter { it != currentGuildId && it in guildsWithSounds }.forEach(::addGuildSection)
            guildsWithSounds.filter { it != currentGuildId && it !in orderedGuildIds }
                .forEach(::addGuildSection)
            if (hasNitro) addDefaultSection()
        }
        binding.soundboardEmpty.visibility = if (rows.isEmpty() && loaded) View.VISIBLE else View.GONE
        soundAdapter.submit(rows, playingSoundIds)
    }

    private fun onSoundSelected(sound: SoundboardSound, usable: Boolean) {
        if (usable) {
            send(sound)
        } else if (!hasNitro() && sound.guildId != currentGuildId) {
            showNitroUpsell()
        } else if (sound.available == false) {
            AppToast.show(this, R.string.soundboard_sound_disabled_premium_tier_lost)
        }
    }

    private fun showNitroUpsell() {
        WidgetNoticeDialog.show(
            fragmentManager = parentFragmentManager,
            headerText = getString(R.string.premium_upsell_soundboard_title_mobile),
            bodyText = SettingsStrings.render(requireContext(), R.string.premium_upsell_soundboard_description_mobile),
            okText = getString(R.string.emoji_picker_premium_upsell_cta),
            cancelText = getString(R.string.cancel),
            listenerMap = mapOf(
                R.id.notice_ok to {
                    WidgetSettingsPremium.launch(requireContext(), locationSection = NITRO_UPSELL_LOCATION)
                },
            ),
            tag = NITRO_UPSELL_LOCATION,
        )
    }

    private fun send(sound: SoundboardSound) {
        val soundId = sound.soundId ?: return
        if (sending) return
        playSound(soundId)
        sending = true
        RestAPI.api.sendSoundboardSound(
            channelId,
            RestAPIParams.SendSoundboardSound(soundId, SoundboardUtils.sourceGuildId(sound.guildId)),
        )
            .restSubscribeOn()
            .ui(this)
            .bindToComponentLifecycle(this)
            .subscribe(
                {
                    sending = false
                },
                {
                    sending = false
                    stopSound()
                    AppToast.show(this, R.string.default_failure_to_perform_action_message)
                },
            )
    }

    private fun playSound(soundId: Long) {
        stopSound()
        val player = ExoPlayer.Builder(requireContext()).build()
        soundPlayer = player
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_VOICE_COMMUNICATION)
                .build(),
            false,
        )
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && soundPlayer === player && playingSoundId == null) {
                    playingSoundId = soundId
                    StoreStream.getSoundboard().reportSoundStartedPlaying(soundId)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && soundPlayer === player) stopSound()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (soundPlayer === player) stopSound()
            }
        })
        player.setMediaItem(MediaItem.fromUri("${IconUtils.CDN_HOST}/soundboard-sounds/$soundId"))
        player.prepare()
        player.playWhenReady = true
    }

    private fun stopSound() {
        val player = soundPlayer
        soundPlayer = null
        player?.release()
        playingSoundId?.let { StoreStream.getSoundboard().reportSoundFinishedPlaying(it) }
        playingSoundId = null
    }

    private class SoundAdapter(
        private val onClick: (SoundboardSound, Boolean) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()
        private var playingSoundIds: Set<Long> = emptySet()

        fun submit(rows: List<Row>, playingSoundIds: Set<Long>) {
            this.rows = rows
            this.playingSoundIds = playingSoundIds
            notifyDataSetChanged()
        }

        fun isHeader(position: Int) = rows.getOrNull(position) is Row.Header

        override fun getItemViewType(position: Int) = if (isHeader(position)) HEADER else SOUND

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == HEADER) {
                HeaderHolder(WidgetSoundboardHeaderItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            } else {
                SoundHolder(WidgetSoundboardSoundItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).binding.soundboardHeaderName.text = row.name
                is Row.Sound -> (holder as SoundHolder)
                    .bind(row.sound, row.usable, row.sound.soundId in playingSoundIds, onClick)
            }
        }

        class HeaderHolder(val binding: WidgetSoundboardHeaderItemBinding) : RecyclerView.ViewHolder(binding.root)

        class SoundHolder(private val binding: WidgetSoundboardSoundItemBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(
                sound: SoundboardSound,
                usable: Boolean,
                selected: Boolean,
                onClick: (SoundboardSound, Boolean) -> Unit,
            ) {
                when {
                    sound.emojiId != null -> binding.soundboardSoundEmoji.setImageURI(
                        ModelEmojiCustom.getImageUri(sound.emojiId, false, EMOJI_SIZE_PX),
                    )
                    sound.emojiName != null -> binding.soundboardSoundEmoji.setImageURI(
                        ModelEmojiUnicode(listOf(sound.name.orEmpty()), sound.emojiName)
                            .getImageUri(false, EMOJI_SIZE_PX, binding.root.context),
                    )
                    else -> binding.soundboardSoundEmoji.setActualImageResource(R.drawable.ic_soundboard_24dp)
                }
                binding.soundboardSoundEmoji.contentDescription = sound.name
                binding.soundboardSoundName.text = sound.name
                binding.root.strokeColor = androidx.core.content.ContextCompat.getColor(
                    binding.root.context,
                    R.color.status_green_500,
                )
                binding.root.strokeWidth = if (selected) {
                    (2 * binding.root.resources.displayMetrics.density).toInt()
                } else {
                    0
                }
                binding.root.alpha = if (usable) 1f else LOCKED_ALPHA
                binding.soundboardSoundLock.visibility = if (usable) View.GONE else View.VISIBLE
                binding.root.setOnClickListener { onClick(sound, usable) }
            }
        }

        private companion object {
            const val HEADER = 0
            const val SOUND = 1
            const val LOCKED_ALPHA = 0.5f
        }
    }

    private sealed class Row {
        data class Header(val name: String) : Row()
        data class Sound(val sound: SoundboardSound, val usable: Boolean) : Row()
    }

    companion object {
        private const val ARG_CHANNEL_ID = "channel_id"
        private const val ARG_GUILD_ID = "guild_id"
        private const val COLUMN_COUNT = 3
        private const val EMOJI_SIZE_PX = 64
        private const val NITRO_UPSELL_LOCATION = "Soundboard Locked Sound"

        fun show(fragmentManager: FragmentManager, channelId: Long, guildId: Long) {
            WidgetSoundboardBottomSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHANNEL_ID, channelId)
                    putLong(ARG_GUILD_ID, guildId)
                }
                show(fragmentManager, WidgetSoundboardBottomSheet::class.java.simpleName)
            }
        }
    }
}
