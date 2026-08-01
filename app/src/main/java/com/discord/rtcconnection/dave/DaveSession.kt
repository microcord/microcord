package com.discord.rtcconnection.dave

import com.discord.app.AppLog
import com.discord.rtcconnection.socket.io.Opcodes
import com.google.gson.annotations.SerializedName

/** Orders DAVE gateway messages while the native connection remains owned by RtcConnection. */
internal class DaveSession(
    private val listener: Listener,
    private val sendJson: (opcode: Int, payload: Any) -> Unit,
    private val sendBinary: (opcode: Int, payload: ByteArray) -> Unit,
) {
    interface Listener {
        fun onDaveProtocolVersion(version: Int) = Unit
        fun onDaveExternalSender(externalSender: ByteArray) = Unit
        fun onDavePrepareEpoch(epoch: Int, transitionId: Int, onKeyPackage: (ByteArray) -> Unit) = Unit
        fun onDaveRequestKeyPackage(onKeyPackage: (ByteArray) -> Unit) = Unit
        fun onDaveProcessProposals(proposals: ByteArray, onCommitWelcome: (ByteArray) -> Unit) = Unit
        fun onDavePrepareCommitTransition(
            transitionId: Int,
            commit: ByteArray,
            onProcessed: (processed: Boolean, protocolVersion: Int) -> Unit,
        ) = Unit
        fun onDaveProcessWelcome(
            transitionId: Int,
            welcome: ByteArray,
            onProcessed: (joined: Boolean, protocolVersion: Int) -> Unit,
        ) = Unit
        fun onDavePrepareTransition(transitionId: Int, protocolVersion: Int, onPrepared: () -> Unit) = Unit
        fun onDaveExecuteTransition(transitionId: Int) = Unit
    }

    private data class Epoch(val epoch: Int, val transitionId: Int)
    private data class Transition(@SerializedName("transition_id") val transitionId: Int)

    private var generation = 0
    private var externalSenderReceived = false
    private var epochPrepared = false
    private var pendingEpoch: Epoch? = null
    private val pendingProposals = ArrayDeque<ByteArray>()

    fun reset() {
        generation++
        externalSenderReceived = false
        epochPrepared = false
        pendingEpoch = null
        pendingProposals.clear()
    }

    fun onProtocolSelected(protocolVersion: Int) {
        AppLog.d(TAG, "protocol selected version=$protocolVersion")
        listener.onDaveProtocolVersion(protocolVersion)
        if (protocolVersion < 1) {
            pendingEpoch = null
            epochPrepared = false
            listener.onDavePrepareTransition(0, 0) { listener.onDaveExecuteTransition(0) }
            return
        }
        queueEpoch(epoch = 1, transitionId = 1)
    }

    fun onExternalSender(payload: ByteArray) {
        AppLog.d(TAG, "external sender received bytes=${payload.size}")
        listener.onDaveExternalSender(payload.copyOf())
        externalSenderReceived = true
        preparePendingEpoch()
    }

    fun onPrepareEpoch(protocolVersion: Int, epoch: Int, transitionId: Int?) {
        listener.onDaveProtocolVersion(protocolVersion)
        queueEpoch(epoch, transitionId ?: epoch)
    }

    fun onProposals(payload: ByteArray) {
        AppLog.d(TAG, "proposals received bytes=${payload.size} epochPrepared=$epochPrepared")
        if (!epochPrepared) {
            pendingProposals += payload.copyOf()
            return
        }
        processProposals(payload)
    }

    fun onAnnounceCommitTransition(transitionId: Int, commit: ByteArray) {
        AppLog.d(TAG, "commit transition announced id=$transitionId bytes=${commit.size}")
        val currentGeneration = generation
        listener.onDavePrepareCommitTransition(transitionId, commit.copyOf()) { processed, version ->
            if (currentGeneration != generation) return@onDavePrepareCommitTransition
            AppLog.d(TAG, "commit transition processed id=$transitionId success=$processed version=$version")
            if (processed) listener.onDaveProtocolVersion(version) else recoverFromInvalidCommit(transitionId)
        }
    }

    fun onWelcome(transitionId: Int, welcome: ByteArray) {
        AppLog.d(TAG, "welcome received id=$transitionId bytes=${welcome.size}")
        val currentGeneration = generation
        listener.onDaveProcessWelcome(transitionId, welcome.copyOf()) { joined, version ->
            if (currentGeneration != generation) return@onDaveProcessWelcome
            AppLog.d(TAG, "welcome processed id=$transitionId success=$joined version=$version")
            if (joined) listener.onDaveProtocolVersion(version) else recoverFromInvalidCommit(transitionId)
        }
    }

    fun onPrepareTransition(protocolVersion: Int, transitionId: Int) {
        AppLog.d(TAG, "prepare transition id=$transitionId version=$protocolVersion")
        val currentGeneration = generation
        listener.onDavePrepareTransition(transitionId, protocolVersion) {
            if (currentGeneration != generation) return@onDavePrepareTransition
            if (transitionId == 0) listener.onDaveExecuteTransition(0)
            else sendJson(Opcodes.DAVE_TRANSITION_READY, Transition(transitionId))
        }
    }

    fun onExecuteTransition(transitionId: Int) {
        AppLog.d(TAG, "execute transition id=$transitionId")
        listener.onDaveExecuteTransition(transitionId)
    }

    private fun queueEpoch(epoch: Int, transitionId: Int) {
        epochPrepared = false
        pendingEpoch = Epoch(epoch, transitionId)
        preparePendingEpoch()
    }

    private fun preparePendingEpoch() {
        val epoch = pendingEpoch ?: return
        if (!externalSenderReceived) return
        pendingEpoch = null
        val currentGeneration = generation
        listener.onDavePrepareEpoch(epoch.epoch, epoch.transitionId) { keyPackage ->
            if (currentGeneration != generation) return@onDavePrepareEpoch
            sendBinary(Opcodes.DAVE_MLS_KEY_PACKAGE, keyPackage.copyOf())
            AppLog.d(TAG, "epoch prepared epoch=${epoch.epoch} transition=${epoch.transitionId}")
            epochPrepared = true
            while (pendingProposals.isNotEmpty()) processProposals(pendingProposals.removeFirst())
        }
    }

    private fun processProposals(payload: ByteArray) {
        val currentGeneration = generation
        listener.onDaveProcessProposals(payload.copyOf()) { commitWelcome ->
            if (currentGeneration == generation) sendBinary(Opcodes.DAVE_MLS_COMMIT_WELCOME, commitWelcome.copyOf())
        }
    }

    private fun recoverFromInvalidCommit(transitionId: Int) {
        sendJson(Opcodes.DAVE_MLS_INVALID_COMMIT_WELCOME, Transition(transitionId))
        val currentGeneration = generation
        listener.onDaveRequestKeyPackage { keyPackage ->
            if (currentGeneration == generation) sendBinary(Opcodes.DAVE_MLS_KEY_PACKAGE, keyPackage.copyOf())
        }
    }

    private companion object {
        const val TAG = "DaveSession"
    }
}
