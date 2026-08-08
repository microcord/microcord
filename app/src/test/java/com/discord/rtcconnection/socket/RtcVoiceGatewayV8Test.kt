package com.discord.rtcconnection.socket

import com.discord.rtcconnection.dave.ByteReader
import com.discord.rtcconnection.dave.DaveSession
import com.discord.rtcconnection.dave.VoiceGatewayFrameCodec
import com.discord.rtcconnection.dave.formatFingerprint
import com.discord.rtcconnection.socket.io.Opcodes
import com.discord.rtcconnection.socket.io.Payloads
import com.google.gson.Gson
import com.google.gson.JsonParser
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtcVoiceGatewayV8Test {
    @Test
    fun v8TextAndBinaryFramesCarrySequence() {
        val text = VoiceGatewayFrameCodec.decodeText(Gson(), "{\"op\":24,\"d\":{},\"seq\":4660}")
        val binary = VoiceGatewayFrameCodec.decodeBinary(
            byteArrayOf(0x12, 0x34, Opcodes.DAVE_MLS_PROPOSALS.toByte(), 7, 8).toByteString(),
            version = 8,
        )

        assertEquals(4660, text.seq)
        assertEquals(4660, binary.sequence)
        assertEquals(Opcodes.DAVE_MLS_PROPOSALS, binary.opcode)
        assertArrayEquals(byteArrayOf(7, 8), binary.payload)
        assertArrayEquals(
            byteArrayOf(Opcodes.DAVE_MLS_KEY_PACKAGE.toByte(), 9),
            VoiceGatewayFrameCodec.encodeBinary(Opcodes.DAVE_MLS_KEY_PACKAGE, byteArrayOf(9)).toByteArray(),
        )
        assertArrayEquals(byteArrayOf(1, 2), ByteReader(byteArrayOf(1, 2, 3)).read(2))
    }

    @Test
    fun daveOpcodesAreContiguousAndNamed() {
        assertEquals(
            (21..31).toList(),
            listOf(
                Opcodes.DAVE_PREPARE_TRANSITION,
                Opcodes.DAVE_EXECUTE_TRANSITION,
                Opcodes.DAVE_TRANSITION_READY,
                Opcodes.DAVE_PREPARE_EPOCH,
                Opcodes.DAVE_MLS_EXTERNAL_SENDER,
                Opcodes.DAVE_MLS_KEY_PACKAGE,
                Opcodes.DAVE_MLS_PROPOSALS,
                Opcodes.DAVE_MLS_COMMIT_WELCOME,
                Opcodes.DAVE_MLS_ANNOUNCE_COMMIT_TRANSITION,
                Opcodes.DAVE_MLS_WELCOME,
                Opcodes.DAVE_MLS_INVALID_COMMIT_WELCOME,
            ),
        )
        assertEquals("DAVE_MLS_WELCOME", Opcodes.getNameOf(30))
    }

    @Test
    fun websocketTraceRedactsCredentialsAndTransportKeys() {
        val secret = "{\"token\":\"secret\"}"
        assertEquals("<redacted:${secret.length} chars>", RtcControlSocket.traceText(Opcodes.IDENTIFY, secret))
        assertEquals("<redacted:${secret.length} chars>", RtcControlSocket.traceText(Opcodes.RESUME, secret))
        assertEquals(
            "<redacted:${secret.length} chars>",
            RtcControlSocket.traceText(Opcodes.SELECT_PROTOCOL_ACK, secret),
        )
        assertEquals("{\"op\":8}", RtcControlSocket.traceText(Opcodes.HELLO, "{\"op\":8}"))
    }

    @Test
    fun externalSenderPrecedesEpochAndQueuedProposals() {
        val harness = DaveHarness()
        harness.session.onProtocolSelected(1)
        harness.session.onProposals(byteArrayOf(3))
        assertEquals(listOf("version:1"), harness.events)

        harness.session.onExternalSender(byteArrayOf(1))

        assertEquals(
            listOf("version:1", "external", "epoch:1:1", "proposals"),
            harness.events,
        )
        assertEquals(
            listOf(Opcodes.DAVE_MLS_KEY_PACKAGE, Opcodes.DAVE_MLS_COMMIT_WELCOME),
            harness.binary.map { it.first },
        )
    }

    @Test
    fun transitionReadyAndExecuteStayOrdered() {
        val harness = DaveHarness()
        harness.session.onPrepareTransition(protocolVersion = 1, transitionId = 9)
        harness.session.onExecuteTransition(9)

        assertEquals(listOf("prepare-transition:9:1", "execute:9"), harness.events)
        assertEquals(Opcodes.DAVE_TRANSITION_READY, harness.json.single().first)
    }

    @Test
    fun invalidCommitSendsRecoveryThenFreshKeyPackage() {
        val harness = DaveHarness(commitSucceeds = false)
        harness.session.onAnnounceCommitTransition(7, byteArrayOf(4))

        assertEquals(listOf("commit:7", "request-key"), harness.events)
        assertEquals(Opcodes.DAVE_MLS_INVALID_COMMIT_WELCOME, harness.json.single().first)
        assertEquals(Opcodes.DAVE_MLS_KEY_PACKAGE, harness.binary.single().first)
    }

    @Test
    fun heartbeatAndResumeUseLatestSequenceAck() {
        val gson = Gson()
        val heartbeat = JsonParser.parseString(gson.toJson(Payloads.Heartbeat(123L, 42))).asJsonObject
        val resume = JsonParser.parseString(gson.toJson(Payloads.Resume("token", "session", "server", 42))).asJsonObject

        assertEquals(42, heartbeat["seq_ack"].asInt)
        assertEquals(42, resume["seq_ack"].asInt)
        assertFalse(
            Payloads.Resume("fixture-token", "fixture-session", "fixture-server", 42)
                .toString()
                .contains("fixture-"),
        )
    }

    @Test
    fun closeRecoveryAndTransportFallbackAreDeterministic() {
        assertEquals(TransportModes.AES256_GCM, TransportModes.select(listOf(TransportModes.XCHACHA20, TransportModes.AES256_GCM), false))
        assertEquals(TransportModes.XCHACHA20, TransportModes.select(listOf(TransportModes.AES256_GCM), true))
        assertEquals(VoiceCloseRecovery.RETRY_WITH_XCHACHA20, VoiceCloseRecovery.from(4016))
        assertEquals(VoiceCloseRecovery.RETRY_WITH_DAVE, VoiceCloseRecovery.from(4017))
        assertEquals(VoiceCloseRecovery.NONE, VoiceCloseRecovery.from(4004))
    }

    @Test
    fun fingerprintUsesFiveByteBigEndianGroups() {
        assertEquals(
            "09060 60585 12110 63635 15160 66685",
            formatFingerprint(ByteArray(30) { it.toByte() }),
        )
        assertTrue(formatFingerprint(byteArrayOf(1, 2)).isEmpty())
    }

    private class DaveHarness(commitSucceeds: Boolean = true) : DaveSession.Listener {
        val events = mutableListOf<String>()
        val json = mutableListOf<Pair<Int, Any>>()
        val binary = mutableListOf<Pair<Int, ByteArray>>()
        val session = DaveSession(this, { op, data -> json += op to data }, { op, data -> binary += op to data })
        private val commitSucceeds = commitSucceeds

        override fun onDaveProtocolVersion(version: Int) { events += "version:$version" }
        override fun onDaveExternalSender(externalSender: ByteArray) { events += "external" }
        override fun onDavePrepareEpoch(epoch: Int, transitionId: Int, onKeyPackage: (ByteArray) -> Unit) {
            events += "epoch:$epoch:$transitionId"
            onKeyPackage(byteArrayOf(10))
        }
        override fun onDaveRequestKeyPackage(onKeyPackage: (ByteArray) -> Unit) {
            events += "request-key"
            onKeyPackage(byteArrayOf(11))
        }
        override fun onDaveProcessProposals(proposals: ByteArray, onCommitWelcome: (ByteArray) -> Unit) {
            events += "proposals"
            onCommitWelcome(byteArrayOf(12))
        }
        override fun onDavePrepareCommitTransition(
            transitionId: Int,
            commit: ByteArray,
            onProcessed: (Boolean, Int) -> Unit,
        ) {
            events += "commit:$transitionId"
            onProcessed(commitSucceeds, 1)
        }
        override fun onDavePrepareTransition(transitionId: Int, protocolVersion: Int, onPrepared: () -> Unit) {
            events += "prepare-transition:$transitionId:$protocolVersion"
            onPrepared()
        }
        override fun onDaveExecuteTransition(transitionId: Int) { events += "execute:$transitionId" }
    }
}
