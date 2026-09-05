package com.discord.utilities.feature

import com.discord.gateway.GatewayLogRedactor
import com.discord.models.domain.UserSettingsProtoResponse
import com.discord.restapi.RestAPIBuilder
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestLogRedactor
import com.discord.utilities.analytics.AnalyticSuperProperties
import com.discord.utilities.feature.ManticordSettingsBackupCodec.Payload
import com.discord.utilities.rest.RestAPI
import com.google.gson.JsonParser
import com.google.protobuf.UnknownFieldSet
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import rx.Observable
import rx.schedulers.Schedulers

class ManticordSettingsBackupTest {
    private val server = MockWebServer()

    @After fun tearDown() {
        RestAPI.reset()
        server.shutdown()
    }

    @Test fun saveAndLoadUseDefaultMetadataAndVerifyPersistence() {
        val payload = fixture()
        val initial = wire(null, 5)
        val saved = wire(payload, 6)
        response(initial)
        response(saved)
        response(saved)
        response(saved)
        RestAPI.install(RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES), emptyList())
        val backup = ManticordSettingsBackup(RestAPI.api) { true }
        backup.save(Observable.just(payload)).toBlocking().single()
        assertEquals(payload, backup.load().toBlocking().single())
        val requests = List(4) { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        assertEquals(listOf("GET", "PATCH", "GET", "GET"), requests.map { it.method })
        requests.forEach {
            assertEquals("/api/v9/users/@me/settings-proto/3", it.path)
            assertEquals(AnalyticSuperProperties.USER_AGENT, it.getHeader("User-Agent"))
            assertEquals(AnalyticSuperProperties.superPropertiesStringBase64, it.getHeader("X-Super-Properties"))
            assertNotNull(it.getHeader("X-Discord-Timezone"))
        }
        val patch = JsonParser.parseString(requests[1].body.readUtf8()).asJsonObject
        assertEquals(5, patch["required_data_version"].asInt)
        assertEquals(payload, ManticordSettingsBackupCodec.decode(patch["settings"].asString))
    }

    @Test fun conflictsAndDiscardedPayloadsNeverReportSuccess() {
        val initial = wire(null, 5)
        response(initial)
        server.enqueue(MockResponse().setBody("""{"settings":"$initial","out_of_date":true}"""))
        response(initial)
        response(initial)
        RestAPI.install(RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES), emptyList())
        val backup = ManticordSettingsBackup(RestAPI.api) { true }
        repeat(2) {
            assertThrows(IllegalStateException::class.java) { backup.save(Observable.just(fixture())).toBlocking().single() }
        }
        assertEquals(4, server.requestCount)
    }

    @Test fun explicitClearVerifiesDeletionAndAllowsTheFirstSave() {
        val foreign = UnknownFieldSet.parseFrom(bytes(2, bytes(2, bytes(1, byteArrayOf(0xff.toByte())))))
            .toBuilder().addField(1, UnknownFieldSet.parseFrom(wire(null, 5).decodeBase64()!!.toByteArray()).getField(1))
            .build().toByteArray().toByteString().base64()
        val cleared = wire(null, 6)
        val saved = wire(fixture(), 7)
        response(foreign)
        response(cleared)
        response(cleared)
        response(cleared)
        response(saved)
        response(saved)
        RestAPI.install(RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES), emptyList())
        val backup = ManticordSettingsBackup(RestAPI.api) { true }
        backup.clear().toBlocking().single()
        backup.save(Observable.just(fixture())).toBlocking().single()
        val requests = List(6) { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        assertEquals(listOf("GET", "PATCH", "GET", "GET", "PATCH", "GET"), requests.map { it.method })
        requests.forEach {
            assertEquals("/api/v9/users/@me/settings-proto/3", it.path)
            assertEquals(AnalyticSuperProperties.USER_AGENT, it.getHeader("User-Agent"))
            assertNotNull(it.getHeader("X-Super-Properties"))
            assertNotNull(it.getHeader("X-Discord-Timezone"))
        }
        val deletion = JsonParser.parseString(requests[1].body.readUtf8()).asJsonObject
        assertEquals(5, deletion["required_data_version"].asInt)
        assertNull(ManticordSettingsBackupCodec.decode(deletion["settings"].asString))
        val upload = JsonParser.parseString(requests[4].body.readUtf8()).asJsonObject
        assertEquals(6, upload["required_data_version"].asInt)
        assertEquals(fixture(), ManticordSettingsBackupCodec.decode(upload["settings"].asString))
    }

    @Test fun foreignDataAndUnknownLocalSettingsAreNeverOverwritten() {
        val foreign = bytes(2, bytes(2, bytes(1, "foreign".toByteArray())))
        response(foreign.toByteString().base64())
        RestAPI.install(RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES), emptyList())
        val backup = ManticordSettingsBackup(RestAPI.api) { true }
        assertThrows(ManticordSettingsBackupCodec.UnrecognizedCloudData::class.java) {
            backup.save(Observable.just(fixture())).toBlocking().single()
        }
        val unsafe = fixture().let { it.copy(strings = it.strings + ("prefs:default/STORE_AUTHED_TOKEN" to "excluded fixture")) }
        val error = assertThrows(IllegalArgumentException::class.java) { backup.save(Observable.just(unsafe)).toBlocking().single() }
        assertFalse(error is ManticordSettingsBackupCodec.UnrecognizedCloudData)
        assertEquals(1, server.requestCount)
    }

    @Test fun disabledAndChangedAccountsCannotStartOrContinueAWrite() {
        var permitted = false
        var requests = 0
        val api = Proxy.newProxyInstance(RestAPIInterface::class.java.classLoader,
            arrayOf(RestAPIInterface::class.java)) { _, method, _ ->
            check(method.name == "getUserSettingsProto")
            requests++
            Observable.just(UserSettingsProtoResponse(wire(null, 1))).doOnNext { permitted = false }
        } as RestAPIInterface
        val backup = ManticordSettingsBackup(api) { permitted }
        assertThrows(CancellationException::class.java) { backup.load().toBlocking().single() }
        assertThrows(CancellationException::class.java) { backup.clear().toBlocking().single() }
        assertThrows(CancellationException::class.java) { backup.save(Observable.just(fixture())).toBlocking().single() }
        assertEquals(0, requests)
        permitted = true
        assertThrows(CancellationException::class.java) { backup.save(Observable.just(fixture())).toBlocking().single() }
        assertEquals(1, requests)
        permitted = true
        assertThrows(CancellationException::class.java) { backup.clear().toBlocking().single() }
        assertEquals(2, requests)
    }

    @Test fun encodedBackupsAreRedactedInRestAndGatewayLogs() {
        val encoded = wire(fixture(), 1)
        assertFalse(RestLogRedactor.redact("""{"settings":"$encoded"}""").contains(encoded))
        assertFalse(GatewayLogRedactor.redact("""{"settings":{"type":3,"proto":"$encoded"}}""").contains(encoded))
    }

    @Test fun networkSubscriptionsLeaveTheSnapshotDispatcher() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "fixture-store-dispatcher") }
        try {
            val threads = CopyOnWriteArrayList<String>()
            val payload = fixture()
            var reads = 0
            var snapshotThread = ""
            val api = Proxy.newProxyInstance(RestAPIInterface::class.java.classLoader,
                arrayOf(RestAPIInterface::class.java)) { _, method, _ ->
                Observable.defer {
                    threads.add(Thread.currentThread().name)
                    Observable.just(UserSettingsProtoResponse(
                        if (method.name == "getUserSettingsProto" && reads++ == 0) wire(null, 1) else wire(payload, 2),
                    ))
                }
            } as RestAPIInterface
            val snapshot = Observable.fromCallable { snapshotThread = Thread.currentThread().name; payload }
                .subscribeOn(Schedulers.from(executor))
            ManticordSettingsBackup(api) { true }.save(snapshot).timeout(5, TimeUnit.SECONDS).toBlocking().single()
            assertEquals("fixture-store-dispatcher", snapshotThread)
            assertEquals(3, threads.size)
            assertTrue(threads.none { it == snapshotThread })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun partialRestoreRollsBackAndFailedRecoveryPropagates() {
        var value = "original"
        assertFalse(ManticordSettingsBackup.restoreOrRollback(
            restore = { value = "partial"; error("fixture failure") },
            rollback = { value = "original" },
        ))
        assertEquals("original", value)
        assertThrows(IllegalStateException::class.java) {
            ManticordSettingsBackup.restoreOrRollback(
                restore = { error("fixture restore failure") }, rollback = { error("fixture rollback failure") },
            )
        }
        assertTrue(ManticordSettingsBackup.restoreOrRollback(
            restore = { value = "restored" }, rollback = { fail("Rollback must not run after success") },
        ))
        assertEquals("restored", value)
    }

    @Test fun stagingPublicationIsReadBackBeforeSuccess() {
        val directory = Files.createTempDirectory("settings-stage-test").toFile()
        try {
            val file = androidx.core.util.AtomicFile(java.io.File(directory, "stage"))
            val first = "fixture first stage".toByteArray()
            val next = "fixture replacement stage".toByteArray()
            ManticordSettingsBackup.writeVerified(file, first)
            assertArrayEquals(first, file.openRead().use { it.readBytes() })
            val failedPublication = object : androidx.core.util.AtomicFile(file.baseFile) {
                override fun finishWrite(stream: java.io.FileOutputStream?) {
                    stream?.close() // Simulate finishWrite logging a rename failure instead of throwing.
                }
            }
            assertThrows(IllegalStateException::class.java) { ManticordSettingsBackup.writeVerified(failedPublication, next) }
            assertArrayEquals(first, file.openRead().use { it.readBytes() })
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun response(settings: String) = server.enqueue(MockResponse().setBody("""{"settings":"$settings"}"""))

    private fun wire(payload: Payload?, version: Long): String {
        val root = payload?.let {
            UnknownFieldSet.parseFrom(ManticordSettingsBackupCodec.encodeForUpload("", it).settings.decodeBase64()!!.toByteArray())
        } ?: UnknownFieldSet.getDefaultInstance()
        val versions = UnknownFieldSet.newBuilder().addField(3,
            UnknownFieldSet.Field.newBuilder().addVarint(version).build()).build()
        return root.toBuilder().addField(1, UnknownFieldSet.Field.newBuilder()
            .addLengthDelimited(versions.toByteString()).build()).build().toByteArray().toByteString().base64()
    }

    private fun bytes(number: Int, value: ByteArray) = UnknownFieldSet.newBuilder().addField(number,
        UnknownFieldSet.Field.newBuilder().addLengthDelimited(com.google.protobuf.ByteString.copyFrom(value)).build()).build().toByteArray()

    private fun fixture() = LocalSettingsSnapshotTest.fixture()
}
