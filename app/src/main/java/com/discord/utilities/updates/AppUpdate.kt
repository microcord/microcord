package com.discord.utilities.updates

import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

const val UPDATE_FEED_URL = "https://microcord.mantikafasi.dev/latest.json"
private const val UPDATE_ORIGIN = "https://microcord.mantikafasi.dev"

/** The architectures releases publish their own APKs for. */
val UPDATE_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

/** Nullable wire fields let validation distinguish historical releases from malformed metadata. */
data class AppUpdate(
    val release: String?,
    val apkUrl: String?,
    val sha256: String?,
    val size: Long?,
    val commitSha: String? = null,
    val commitTimestamp: Long? = null,
    val versionCode: Long? = null,
    val versionName: String? = null,
    val packageName: String? = null,
    /** Per-architecture APKs of the same build, keyed by ABI; absent on releases from before the split. */
    val apks: Map<String, Apk>? = null,
) {
    data class Apk(val apkUrl: String?, val sha256: String?, val size: Long?)

    val hasBuildMetadata get() = commitSha != null

    /** Where this release's changelog lives; independent of which APK is downloaded. */
    val changesUrl get() = "$UPDATE_ORIGIN/releases/$release/changes.json"

    /**
     * This update with the APK for the first of [supportedAbis] the release has a split for, so a
     * phone downloads only its own native libraries. Unchanged when there is none: the universal
     * APK installs everywhere.
     */
    fun forAbis(supportedAbis: List<String>): AppUpdate {
        val abi = supportedAbis.firstOrNull { it in UPDATE_ABIS && apks?.get(it) != null } ?: return copy(apks = null)
        val apk = requireNotNull(apks?.get(abi))
        return copy(apkUrl = apk.apkUrl, sha256 = apk.sha256, size = apk.size, apks = null)
    }

    fun validate(): AppUpdate = apply {
        require(release != null && Regex("build-[1-9][0-9]{0,9}").matches(release))
        val uri = URI(requireNotNull(apkUrl))
        require(uri.scheme == "https" && uri.host == "microcord.mantikafasi.dev" &&
            uri.port in listOf(-1, 443) && uri.rawUserInfo == null && uri.rawQuery == null &&
            uri.rawFragment == null &&
            (uri.rawPath == "/releases/$release.apk" || UPDATE_ABIS.any { uri.rawPath == "/releases/$release-$it.apk" }))
        require(sha256 != null && Regex("[0-9a-f]{64}").matches(sha256))
        require(size != null && size in 1..1_073_741_824L)
        if (listOf(commitSha, commitTimestamp, versionCode, versionName, packageName).any { it != null }) {
            require(commitSha != null && Regex("[0-9a-f]{40}").matches(commitSha))
            require(commitTimestamp != null && versionCode != null &&
                versionCode in 1..Int.MAX_VALUE.toLong() && versionCode == commitTimestamp - 1_577_836_800L)
            require(!versionName.isNullOrBlank() && versionName.length <= 100)
            require(packageName == "dev.mantikafasi.manticord")
        }
    }

    fun isNewerThan(currentSha: String, currentTimestamp: Long, currentVersion: Long): Boolean =
        hasBuildMetadata && !commitSha.equals(currentSha, ignoreCase = true) &&
            requireNotNull(commitTimestamp) > currentTimestamp && requireNotNull(versionCode) >= currentVersion
}

/** Bound the copy and verify it before a private file can become installable. */
internal fun copyVerifiedUpdate(input: InputStream, destination: File, update: AppUpdate): File {
    update.validate()
    val partial = File(destination.parentFile, "${destination.name}.part")
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        partial.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= requireNotNull(update.size))
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        require(total == update.size && actual == update.sha256)
        check((!destination.exists() || destination.delete()) && partial.renameTo(destination))
        return destination
    } finally {
        partial.delete()
    }
}

internal fun acceptsUpdatePackage(update: AppUpdate, installedVersion: Long,
    archivePackage: String?, archiveVersion: Long, sameSigners: Boolean): Boolean =
    update.hasBuildMetadata && archivePackage == update.packageName &&
        archiveVersion == update.versionCode && archiveVersion >= installedVersion && sameSigners

internal fun verifyUpdateFile(file: File, update: AppUpdate) {
    require(file.length() == update.size)
    val digest = MessageDigest.getInstance("SHA-256")
    file.forEachBlock { bytes, length -> digest.update(bytes, 0, length) }
    require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == update.sha256)
}
