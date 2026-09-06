package com.discord.utilities.updates

import com.discord.utilities.feature.ClientPreferenceSnapshotTest.MemoryPreferences
import com.discord.utilities.feature.ClientSettings
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class AppUpdateTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "sanitized-apk-fixture".toByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val update = AppUpdate("build-42", "https://microcord.mantikafasi.dev/releases/build-42.apk",
        digest, bytes.size.toLong(), "b".repeat(40), 1_800_000_000L, 222_163_200L, "1.0", "dev.mantikafasi.manticord")

    @Test fun `automatic checks default on and use the shared backed up typed setting`() {
        val prefs = MemoryPreferences()
        val setting = ClientSettings.automaticUpdateChecks
        assertTrue(setting.get(prefs))
        setting.set(prefs, false)
        assertFalse(setting.get(prefs))
        assertSame(setting, ClientSettings.resolve("default", setting.key))
        assertEquals(ClientSettings.Type.BOOLEAN, ClientSettings.backupType("default", setting.key))
    }

    @Test fun `only a different newer source with a nondecreasing APK version is offered`() {
        val valid = update.validate()
        assertTrue(valid.isNewerThan("a".repeat(40), 1_799_999_999, 1))
        assertFalse(valid.isNewerThan("b".repeat(40), 1_799_999_999, 1))
        assertFalse(valid.isNewerThan("a".repeat(40), 1_800_000_001, 1))
        assertFalse(valid.isNewerThan("a".repeat(40), 1_800_000_000, 1))
        assertFalse(valid.isNewerThan("a".repeat(40), 1_799_999_999, 222_163_201))
        val legacy = Gson().fromJson("""{"release":"build-42","apkUrl":"${update.apkUrl}","sha256":"$digest","size":${bytes.size}}""", AppUpdate::class.java).validate()
        assertFalse(legacy.hasBuildMetadata)
        assertFalse(legacy.isNewerThan("a".repeat(40), 1, 1))
    }

    @Test fun `unsafe URLs partial metadata wrong packages and unbounded sizes fail closed`() {
        val invalid = listOf(
            update.copy(apkUrl = "http://microcord.mantikafasi.dev/releases/build-42.apk"),
            update.copy(apkUrl = "https://evil.example/releases/build-42.apk"),
            update.copy(apkUrl = "${update.apkUrl}?token=not-a-real-token"),
            update.copy(apkUrl = "https://user@microcord.mantikafasi.dev/releases/build-42.apk"),
            update.copy(apkUrl = "https://microcord.mantikafasi.dev/releases/build-43.apk"),
            update.copy(commitSha = null), update.copy(commitSha = "abc"), update.copy(versionCode = 1),
            update.copy(packageName = "other.app"), update.copy(sha256 = "bad"),
            update.copy(size = 0), update.copy(size = Long.MAX_VALUE), update.copy(release = "../secret"),
        )
        invalid.forEach { candidate -> assertThrows(Exception::class.java) { candidate.validate() } }
    }

    @Test fun `only complete matching bytes become a private installable file`() {
        val file = File(temporary.root, "update.apk")
        bytes.inputStream().use { copyVerifiedUpdate(it, file, update) }
        assertArrayEquals(bytes, file.readBytes())
        verifyUpdateFile(file, update)
        for (bad in listOf(bytes.dropLast(1).toByteArray(), bytes + 0, ByteArray(bytes.size))) {
            assertThrows(Exception::class.java) { bad.inputStream().use { copyVerifiedUpdate(it, file, update) } }
            assertArrayEquals(bytes, file.readBytes())
            assertFalse(File(temporary.root, "update.apk.part").exists())
        }
        file.writeBytes(ByteArray(bytes.size))
        assertThrows(Exception::class.java) { verifyUpdateFile(file, update) }
    }

    @Test fun `installation rejects a wrong package version or signing certificate`() {
        assertTrue(acceptsUpdatePackage(update, 1, update.packageName, update.versionCode!!, true))
        assertFalse(acceptsUpdatePackage(update, 1, "other.app", update.versionCode!!, true))
        assertFalse(acceptsUpdatePackage(update, update.versionCode!! + 1, update.packageName, update.versionCode!!, true))
        assertFalse(acceptsUpdatePackage(update, 1, update.packageName, 1, true))
        assertFalse(acceptsUpdatePackage(update, 1, update.packageName, update.versionCode!!, false))
    }
}
