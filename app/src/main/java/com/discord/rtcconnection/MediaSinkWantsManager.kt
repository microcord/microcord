package com.discord.rtcconnection

import com.discord.app.AppLog
import com.discord.rtcconnection.mediaengine.MediaEngineConnection
import com.discord.rtcconnection.mediaengine.MediaEngineThreadExecutor
import com.discord.rtcconnection.socket.io.Payloads
import kotlin.math.max
import rx.Observable
import rx.subjects.BehaviorSubject

/** Negotiates the receive quality of every remote video stream. */
class MediaSinkWantsManager(
    private val selfUserId: Long,
    private val executor: MediaEngineThreadExecutor,
    private val listener: Listener,
) : MediaEngineConnection.DefaultListener() {

    enum class VideoQualityMode(val numeral: Int) {
        AUTO(1),
        FULL(2),
        ;

        companion object {
            fun fromNumeral(numeral: Int?): VideoQualityMode = when (numeral) {
                2 -> FULL
                else -> AUTO
            }
        }
    }

    fun interface Listener {
        fun onVideoSsrc(userId: Long, audioSsrc: Long, videoSsrc: Long, metadata: VideoMetadata?)
    }

    data class VideoStreamDescriptor(
        val encodeQuality: EncodeQuality,
        val ssrc: Long,
        val metadata: VideoMetadata?,
    )

    private data class ResolutionBudget(
        val width: Int,
        val height: Int,
        val budgetPortion: Double,
        val mutedFramerate: Int,
        val framerate: Int,
    )

    private data class OrderedLadder(
        val budget: ResolutionBudget,
        val pixelCount: Int,
        val wantValue: EncodeQuality,
    )

    private val audioSsrcs = LinkedHashMap<Long, Long>()
    private val streamsByUser = LinkedHashMap<Long, List<VideoStreamDescriptor>>()
    private val videoUsers = LinkedHashSet<Long>()
    private val wantsSubject = BehaviorSubject.create<Map<String, EncodeQuality>>(
        mapOf(ANY to EncodeQuality.Hundred),
    )
    private var lastWants: Map<String, EncodeQuality> = emptyMap()
    private var connection: MediaEngineConnection? = null
    private var focusedUserId: Long? = null

    fun observeWants(): Observable<Map<String, EncodeQuality>> = wantsSubject.distinctUntilChanged()

    fun setConnection(connection: MediaEngineConnection?) = executor.run {
        this.connection?.removeListener(this)
        this.connection = connection
        connection?.addListener(this)
        if (connection != null) recalculate(streamsByUser.keys.map(Long::toString))
    }

    fun updateAudioSsrc(userId: Long, audioSsrc: Long?) = executor.run {
        if (audioSsrc == null) audioSsrcs.remove(userId) else audioSsrcs[userId] = audioSsrc
        recalculate()
    }

    internal fun updateVideoStreams(userId: Long, streams: List<Payloads.Stream>) = executor.run {
        val descriptors = streams.asSequence()
            .filter { it.active == true && (it.ssrc ?: 0) > 0 }
            .map { stream ->
                VideoStreamDescriptor(
                    encodeQuality = stream.quality?.let { value ->
                        EncodeQuality.values().firstOrNull { it.value == value }
                    } ?: EncodeQuality.Hundred,
                    ssrc = requireNotNull(stream.ssrc).toLong(),
                    metadata = stream.maxResolution?.let { resolution ->
                        VideoMetadata(
                            userId,
                            resolution.width,
                            resolution.height,
                            stream.maxFrameRate,
                            resolution.type,
                        )
                    },
                )
            }
            .toList()

        if (descriptors.isEmpty()) {
            streamsByUser.remove(userId)
            videoUsers.remove(userId)
        } else {
            streamsByUser[userId] = descriptors
            videoUsers += userId
        }
        recalculate(if (descriptors.isEmpty()) emptyList() else listOf(userId.toString()))
    }

    fun removeUser(userId: Long) {
        updateAudioSsrc(userId, null)
        updateVideoStreams(userId, emptyList())
    }

    fun setFocusedParticipant(userId: Long?) = executor.run {
        val priorities = ArrayList<String>(2)
        focusedUserId?.takeIf { it != selfUserId }?.let { priorities += it.toString() }
        when {
            userId == null -> focusedUserId = null
            userId in videoUsers -> {
                focusedUserId = userId
                priorities += userId.toString()
            }
            userId == selfUserId -> focusedUserId = userId
            else -> {
                AppLog.w(TAG, "Participant not found: $userId")
                return@run
            }
        }
        recalculate(priorities)
    }

    fun refresh() = executor.run { recalculate() }

    fun clear() = executor.run {
        connection?.removeListener(this)
        connection = null
        audioSsrcs.clear()
        streamsByUser.clear()
    }

    override fun onDestroy(connection: MediaEngineConnection) = Unit

    override fun onLocalMute(userId: Long, muted: Boolean) {
        executor.run { recalculate() }
    }

    override fun onLocalVideoOffScreen(userId: Long, offScreen: Boolean) {
        executor.run { recalculate() }
    }

    private fun recalculate(priorityUsers: List<String> = emptyList()) {
        val connection = connection ?: return
        val visibleVideoCount = videoUsers.count { !connection.isUserOffScreen(it) }
        val defaultQuality = LADDER.lastOrNull {
            it.pixelCount * visibleVideoCount <= VIDEO_PIXEL_BUDGET
        }?.wantValue ?: LADDER.first().wantValue
        val wants = linkedMapOf(ANY to defaultQuality)

        streamsByUser.forEach { (userId, descriptors) ->
            var selected = descriptors.firstOrNull() ?: return@forEach
            val focus = focusedUserId
            if (focus != null && focus != selfUserId) {
                if (descriptors.size > 1) {
                    if (userId == focus) {
                        descriptors.forEach { descriptor ->
                            if (descriptor.encodeQuality == EncodeQuality.Hundred) {
                                wants[descriptor.ssrc.toString()] = EncodeQuality.Hundred
                                selected = descriptor
                            } else {
                                wants[descriptor.ssrc.toString()] = EncodeQuality.Zero
                            }
                        }
                    } else {
                        descriptors.forEach { descriptor ->
                            if (descriptor.encodeQuality == EncodeQuality.Hundred) {
                                wants[descriptor.ssrc.toString()] = EncodeQuality.Zero
                            } else {
                                selected = descriptor
                            }
                        }
                    }
                } else if (userId == focus && defaultQuality != EncodeQuality.Hundred) {
                    wants[selected.ssrc.toString()] = EncodeQuality.Hundred
                }
            } else if (descriptors.size > 1) {
                descriptors.forEach { descriptor ->
                    if (descriptor.encodeQuality == EncodeQuality.Hundred) {
                        wants[descriptor.ssrc.toString()] = EncodeQuality.Zero
                    } else {
                        selected = descriptor
                    }
                }
            }

            if (connection.isUserOffScreen(userId) || connection.isUserVideoDisabled(userId)) {
                descriptors.forEach { wants[it.ssrc.toString()] = EncodeQuality.Zero }
            }

            if (userId.toString() in priorityUsers) {
                val audioSsrc = audioSsrcs[userId]
                if (audioSsrc != null) {
                    listener.onVideoSsrc(userId, audioSsrc, selected.ssrc, selected.metadata)
                } else {
                    AppLog.w(TAG, "Missing audioSsrc for user $userId, can't update video ssrc!")
                }
            }

            val previous = lastWants[selected.ssrc.toString()]
            val next = wants[selected.ssrc.toString()]
            if (previous == EncodeQuality.Zero && next != EncodeQuality.Zero) {
                connection.setUserVideoDisabled(userId, false)
            } else if (previous != EncodeQuality.Zero && next == EncodeQuality.Zero) {
                connection.setUserVideoDisabled(userId, true)
            }
        }

        audioSsrcs.forEach { (userId, audioSsrc) ->
            if (connection.isUserMuted(userId)) wants[audioSsrc.toString()] = EncodeQuality.Zero
        }
        lastWants = wants
        wantsSubject.onNext(wants)
    }

    companion object {
        private const val TAG = "MediaSinkWantsManager"
        private const val ANY = "any"
        private const val VIDEO_PIXEL_BUDGET = 1280 * 720

        private val LADDER: List<OrderedLadder> = buildList {
            val validRemainders = setOf(0.0, 4.0, 8.0, 10.0)
            val candidates = (1 until 4096).mapNotNull { height ->
                val width = height * 16.0 / 9.0
                if (width % 16.0 !in validRemainders || height.toDouble() % 16.0 !in validRemainders) {
                    null
                } else {
                    val pixels = height * width
                    Triple(width.toInt(), height, pixels)
                }
            }
            var quality = EncodeQuality.Hundred
            var previousWidth = 0
            for (participants in 1..25) {
                val candidate = candidates.lastOrNull { it.third.toInt() * participants <= VIDEO_PIXEL_BUDGET }
                    ?: candidates.first()
                if (previousWidth != candidate.first) {
                    val mutedFramerate = if (quality <= EncodeQuality.Twenty) 12 else 20
                    val budget = ResolutionBudget(
                        candidate.first,
                        candidate.second,
                        candidate.third / VIDEO_PIXEL_BUDGET,
                        mutedFramerate,
                        30,
                    )
                    add(OrderedLadder(budget, budget.width * budget.height, quality))
                    quality = EncodeQuality.values().lastOrNull { it.value < quality.value } ?: EncodeQuality.Zero
                    previousWidth = candidate.first
                }
            }
        }

        internal fun encodingQualityForWant(want: Int): MediaEngineConnection.EncodingQuality {
            val encodeQuality = EncodeQuality.forWant(want)
            val ordered = LADDER.lastOrNull { encodeQuality >= it.wantValue } ?: LADDER.first()
            val budget = ordered.budget
            return MediaEngineConnection.EncodingQuality(
                minBitrate = max((150_000 * budget.budgetPortion).toInt(), 150_000),
                maxBitrate = max((2_500_000 * budget.budgetPortion).toInt(), 150_000),
                width = budget.width,
                height = budget.height,
                mutedFramerate = budget.mutedFramerate,
                framerate = budget.framerate,
            )
        }
    }
}
