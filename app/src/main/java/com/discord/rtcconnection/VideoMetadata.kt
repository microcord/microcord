package com.discord.rtcconnection

import com.discord.rtcconnection.socket.io.Payloads

/**
 * The resolution envelope a remote sender advertised for its video stream.
 *
 * The client needs it before a single frame arrives: participant tiles size themselves from the
 * advertised aspect ratio rather than waiting for the first decoded frame, which would otherwise
 * make every tile pop from a square placeholder to its real shape.
 *
 * Reference: `com.discord.rtcconnection.VideoMetadata`.
 */
data class VideoMetadata(
    val userID: Long,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFrameRate: Int?,
    val type: Payloads.ResolutionType,
)
