package com.discord.utilities.updates

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest

const val UPDATE_FEED_URL = "https://microcord.mantikafasi.dev/latest.json"
private const val UPDATE_ORIGIN = "https://microcord.mantikafasi.dev"
private val SHA256 = Regex("[0-9a-f]{64}")
private const val MAX_DEX_METADATA_SIZE = 16L * 1024 * 1024

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
    /** Baseline profiles for [apkUrl], one per API range; absent on releases from before they were published. */
    val dexMetadata: List<DexMetadata>? = null,
) {
    data class Apk(val apkUrl: String?, val sha256: String?, val size: Long?, val dexMetadata: List<DexMetadata>? = null)

    /** A `.dm` ART compiles the APK with at install time on Android [minSdk]..[maxSdk] (unbounded when null). */
    data class DexMetadata(val url: String?, val sha256: String?, val size: Long?, val minSdk: Int?, val maxSdk: Int? = null)

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
        return copy(apkUrl = apk.apkUrl, sha256 = apk.sha256, size = apk.size, apks = null, dexMetadata = apk.dexMetadata)
    }

    /**
     * The well-formed dex metadata for this APK on Android [sdk], or null. Never throws: a missing or
     * malformed profile only means ART compiles the update later, so it must not block installing it.
     * Installers accept `.dm` files from Android 9 (API 28); older releases rely on ProfileInstaller.
     */
    fun dexMetadataFor(sdk: Int): DexMetadata? = dexMetadata.orEmpty().firstOrNull { dm ->
        dm.minSdk != null && dm.minSdk >= 28 && sdk >= dm.minSdk && sdk <= (dm.maxSdk ?: Int.MAX_VALUE) &&
            dm.sha256 != null && SHA256.matches(dm.sha256) && dm.size != null && dm.size in 1..MAX_DEX_METADATA_SIZE &&
            runCatching { isReleaseAsset(dm.url, "-api${dm.minSdk}.dm") }.getOrDefault(false)
    }

    fun validate(): AppUpdate = apply {
        require(release != null && Regex("build-[1-9][0-9]{0,9}").matches(release))
        require(isReleaseAsset(apkUrl, ".apk"))
        require(sha256 != null && SHA256.matches(sha256))
        require(size != null && size in 1..1_073_741_824L)
        if (listOf(commitSha, commitTimestamp, versionCode, versionName, packageName).any { it != null }) {
            require(commitSha != null && Regex("[0-9a-f]{40}").matches(commitSha))
            require(commitTimestamp != null && versionCode != null &&
                versionCode in 1..Int.MAX_VALUE.toLong() && versionCode == commitTimestamp - 1_577_836_800L)
            require(!versionName.isNullOrBlank() && versionName.length <= 100)
            require(packageName == "dev.mantikafasi.manticord")
        }
    }

    /** Whether [url] is one of this release's own files on the update origin: universal or one architecture's. */
    private fun isReleaseAsset(url: String?, suffix: String): Boolean {
        val uri = URI(requireNotNull(url))
        return uri.scheme == "https" && uri.host == "microcord.mantikafasi.dev" &&
            uri.port in listOf(-1, 443) && uri.rawUserInfo == null && uri.rawQuery == null &&
            uri.rawFragment == null && (listOf("") + UPDATE_ABIS.map { "-$it" }).any { uri.rawPath == "/releases/$release$it$suffix" }
    }

    fun isNewerThan(currentSha: String, currentTimestamp: Long, currentVersion: Long): Boolean =
        hasBuildMetadata && !commitSha.equals(currentSha, ignoreCase = true) &&
            requireNotNull(commitTimestamp) > currentTimestamp && requireNotNull(versionCode) >= currentVersion
}

/** Bound the copy and verify it before a private file can become installable. */
internal fun copyVerifiedUpdate(input: InputStream, destination: File, update: AppUpdate): File {
    update.validate()
    return copyVerifiedFile(input, destination, requireNotNull(update.size), requireNotNull(update.sha256))
}

/** [destination] only ever holds exactly [size] bytes with digest [sha256]; a partial copy is discarded. */
internal fun copyVerifiedFile(input: InputStream, destination: File, size: Long, sha256: String): File {
    val partial = File(destination.parentFile, "${destination.name}.part")
    try {
        partial.outputStream().use { copyVerified(input, it, size, sha256) }
        check((!destination.exists() || destination.delete()) && partial.renameTo(destination))
        return destination
    } finally {
        partial.delete()
    }
}

/** Streams [input] to [output], failing unless the bytes written were exactly [size] long with digest [sha256]. */
internal fun copyVerified(input: InputStream, output: OutputStream, size: Long, sha256: String) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= size)
        digest.update(buffer, 0, count)
        output.write(buffer, 0, count)
    }
    require(total == size && hex(digest.digest()) == sha256)
}

internal fun acceptsUpdatePackage(update: AppUpdate, installedVersion: Long,
    archivePackage: String?, archiveVersion: Long, sameSigners: Boolean): Boolean =
    update.hasBuildMetadata && archivePackage == update.packageName &&
        archiveVersion == update.versionCode && archiveVersion >= installedVersion && sameSigners

internal fun verifyUpdateFile(file: File, update: AppUpdate) {
    require(file.length() == update.size)
    val digest = MessageDigest.getInstance("SHA-256")
    file.forEachBlock { bytes, length -> digest.update(bytes, 0, length) }
    require(hex(digest.digest()) == update.sha256)
}

private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
