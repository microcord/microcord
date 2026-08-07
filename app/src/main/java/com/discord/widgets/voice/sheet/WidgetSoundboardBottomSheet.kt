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
import com.discord.utilities.developer.DeveloperSettings
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
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
    private var selectedSoundId: Long? = null
    private var soundPlayer: ExoPlayer? = null
    private var defaultSounds: List<SoundboardSound> = emptyList()
    private val guildSounds = linkedMapOf<Long, List<SoundboardSound>>()
    private var query = ""
    private lateinit var soundAdapter: SoundAdapter
    private val renderGuildSounds = Runnable { if (_binding != null) showSounds() }

    override fun getContentViewResId() = R.layout.widget_soundboard_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = WidgetSoundboardBottomSheetBinding.bind(view)
        soundAdapter = SoundAdapter(::send)
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
        observeGuildSounds()
        loadSounds()
    }

    override fun onDestroyView() {
        stopSound()
        binding.root.removeCallbacks(renderGuildSounds)
        binding.soundboardSounds.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun loadSounds() {
        RestAPI.api.getDefaultSoundboardSounds()
            .restSubscribeOn()
            .ui(this)
            .bindToComponentLifecycle(this)
            .subscribe({ sounds ->
                defaultSounds = sounds
                showSounds()
            }) {
                showSounds()
                AppToast.show(this, R.string.default_failure_to_perform_action_message)
            }
    }

    private fun observeGuildSounds() {
        val guildIds = StoreStream.getGuilds().getGuilds().keys
        StoreStream.getGatewayConnection().soundboardSounds
            .onBackpressureBuffer()
            .filter { it.guildId in guildIds }
            .ui(this)
            .bindToComponentLifecycle(this)
            .subscribe(
                { payload ->
                    guildSounds[payload.guildId] = payload.soundboardSounds.map { sound ->
                        if (sound.guildId == null) sound.copy(guildId = payload.guildId) else sound
                    }
                    binding.root.removeCallbacks(renderGuildSounds)
                    binding.root.postDelayed(renderGuildSounds, GUILD_RENDER_DEBOUNCE_MS)
                },
                { AppToast.show(this, R.string.default_failure_to_perform_action_message) },
            )
        StoreStream.getGatewayConnection().requestSoundboardSounds(guildIds.toList())
    }

    private fun showSounds() {
        val seen = hashSetOf<Long>()
        val tierOverride = DeveloperSettings.premiumTierOverride()
        val allowExternalSounds = tierOverride == null || tierOverride == com.discord.api.premium.PremiumTier.TIER_2
        fun filtered(sounds: List<SoundboardSound>) = sounds.filter { sound ->
            val id = sound.soundId
            id != null && ((allowExternalSounds && sound.available != false) ||
                sound.guildId == currentGuildId || sound.guildId == null) && seen.add(id) &&
                (query.isBlank() || sound.name.orEmpty().contains(query, ignoreCase = true))
        }
        val rows = buildList {
            val guilds = StoreStream.getGuilds().getGuilds()
            val orderedGuildIds = StoreStream.getGuildsSorted().getOrderedGuilds().keys
            val sectionGuildIds = buildList {
                if (currentGuildId in guildSounds) add(currentGuildId)
                addAll(orderedGuildIds.filter { it != currentGuildId && it in guildSounds })
                addAll(guildSounds.keys.filter { it != currentGuildId && it !in orderedGuildIds })
            }
            sectionGuildIds.forEach { guildId ->
                val sounds = guildSounds[guildId].orEmpty()
                filtered(sounds).takeIf(List<*>::isNotEmpty)?.let { matches ->
                    add(Row.Header(guilds[guildId]?.name ?: guildId.toString()))
                    addAll(matches.map(Row::Sound))
                }
            }
            filtered(defaultSounds).takeIf(List<*>::isNotEmpty)?.let { sounds ->
                add(Row.Header(getString(R.string.soundboard_default_sounds)))
                addAll(sounds.map(Row::Sound))
            }
        }
        binding.soundboardEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        soundAdapter.submit(rows, selectedSoundId)
    }

    private fun send(sound: SoundboardSound) {
        val soundId = sound.soundId ?: return
        if (sending) return
        playSound(soundId)
        sending = true
        RestAPI.api.sendSoundboardSound(
            channelId,
            RestAPIParams.SendSoundboardSound(soundId, sound.guildId),
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
                if (isPlaying && soundPlayer === player) {
                    selectedSoundId = soundId
                    showSounds()
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
        selectedSoundId = null
        if (_binding != null) showSounds()
    }

    private class SoundAdapter(
        private val onClick: (SoundboardSound) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()
        private var selectedSoundId: Long? = null

        fun submit(rows: List<Row>, selectedSoundId: Long?) {
            this.rows = rows
            this.selectedSoundId = selectedSoundId
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
                is Row.Sound -> (holder as SoundHolder).bind(row.sound, row.sound.soundId == selectedSoundId, onClick)
            }
        }

        class HeaderHolder(val binding: WidgetSoundboardHeaderItemBinding) : RecyclerView.ViewHolder(binding.root)

        class SoundHolder(private val binding: WidgetSoundboardSoundItemBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(sound: SoundboardSound, selected: Boolean, onClick: (SoundboardSound) -> Unit) {
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
                binding.root.setOnClickListener { onClick(sound) }
            }
        }

        private companion object {
            const val HEADER = 0
            const val SOUND = 1
        }
    }

    private sealed class Row {
        data class Header(val name: String) : Row()
        data class Sound(val sound: SoundboardSound) : Row()
    }

    companion object {
        private const val ARG_CHANNEL_ID = "channel_id"
        private const val ARG_GUILD_ID = "guild_id"
        private const val COLUMN_COUNT = 3
        private const val EMOJI_SIZE_PX = 64
        private const val GUILD_RENDER_DEBOUNCE_MS = 100L

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
