package com.discord.plugins

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import rx.schedulers.Schedulers

/**
 * The plugin index, published on github.com/microcord/plugins' `builds` branch and read through
 * jsDelivr, which GitHub's raw-file rate limits do not apply to. Official APK links are jsDelivr links
 * pinned to a commit; third-party ones may be any HTTPS link. Every APK is pinned by its SHA-256 and
 * signer, so the link itself is not trusted.
 */
data class PluginIndex(val version: Int = 0, val plugins: List<Entry>? = null) {
    data class Entry(
        val id: String? = null,
        val packageName: String? = null,
        val name: String? = null,
        val description: String? = null,
        val authors: List<String>? = null,
        val versionCode: Long = 0,
        val versionName: String? = null,
        val pluginApiMinimum: Int = 0,
        val apkUrl: String? = null,
        val sha256: String? = null,
        val size: Long = 0,
        val signerSha256: String? = null,
        val sourceUrl: String? = null,
    ) {
        /** Throws unless every field the install path relies on is present and well formed. */
        fun validate(): Entry = also {
            require(PACKAGE.matches(packageName.orEmpty()) && !id.isNullOrBlank() && !name.isNullOrBlank())
            require(apkUrl.orEmpty().startsWith("https://"))
            require(HEX_SHA256.matches(sha256.orEmpty()) && size in 1..MAX_APK_BYTES)
            require(HEX_SHA256.matches(normalizedSigner()) && versionCode > 0)
        }

        /** The signer as hex without separators, the form both the index and the app compare in. */
        fun normalizedSigner(): String = signerSha256.orEmpty().replace(":", "").lowercase()
    }

    companion object {
        const val INDEX_URL = "https://cdn.jsdelivr.net/gh/microcord/plugins@builds/plugins.json"
        private const val MAX_INDEX_BYTES = 256L * 1024
        private const val MAX_APK_BYTES = 50L * 1024 * 1024
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val HEX_SHA256 = Regex("[0-9a-f]{64}")

        private val http by lazy {
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        }

        /** The index's valid entries; a malformed entry is dropped rather than failing the list. */
        fun fetch(): Observable<List<Entry>> = Observable.fromCallable {
            http.newCall(Request.Builder().url(INDEX_URL).build()).execute().use { response ->
                val index = Gson().fromJson(read(response, MAX_INDEX_BYTES).toString(Charsets.UTF_8), PluginIndex::class.java)
                index.plugins.orEmpty().mapNotNull { runCatching { it.validate() }.getOrNull() }
            }
        }.subscribeOn(Schedulers.io())

        /**
         * Downloads [entry]'s APK and returns it once its bytes, package name and signer all match the
         * index. Its signer is then approved, so the plugin loads once Android finishes installing it.
         */
        fun download(context: Context, entry: Entry): Observable<File> = Observable.fromCallable {
            entry.validate()
            val dir = File(context.filesDir, INSTALL_DIR).apply { deleteRecursively(); mkdirs() }
            val file = File(dir, "${entry.sha256}.apk")
            http.newCall(Request.Builder().url(requireNotNull(entry.apkUrl)).build()).execute().use { response ->
                file.writeBytes(read(response, entry.size))
            }
            require(file.length() == entry.size && sha256(file.readBytes()) == entry.sha256)
            val archive = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, SIGNER_FLAGS))
            require(archive.packageName == entry.packageName)
            val signer = signers(archive).single()
            require(sha256(signer) == entry.normalizedSigner())
            PluginLoadingPolicy.setPackageEnabled(archive.packageName, true, fingerprint(signer))
            file
        }.subscribeOn(Schedulers.io())

        /** Asks Android to uninstall [packageName]. */
        fun uninstall(context: Context, packageName: String) {
            context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
        }

        /** The installed version code of [packageName], or null when it is not installed. */
        fun installedVersion(context: Context, packageName: String): Long? = runCatching {
            PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(packageName, 0))
        }.getOrNull()

        /**
         * Whether the installed copy of [entry] carries a different signer than the index, so Android
         * refuses the index's APK as a conflicting package until that copy is uninstalled.
         */
        fun hasForeignSigner(context: Context, entry: Entry): Boolean = runCatching {
            val info = context.packageManager.getPackageInfo(requireNotNull(entry.packageName), SIGNER_FLAGS)
            signers(info).none { sha256(it) == entry.normalizedSigner() }
        }.getOrDefault(false)

        /** The directory `download` writes to; shared through the app's FileProvider for the installer. */
        const val INSTALL_DIR = "plugin_installs"

        @Suppress("DEPRECATION")
        private val SIGNER_FLAGS =
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

        @Suppress("DEPRECATION")
        private fun signers(info: PackageInfo): List<ByteArray> =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
                .orEmpty().map { it.toByteArray() }

        private fun read(response: Response, maxBytes: Long): ByteArray {
            check(response.isSuccessful)
            val source = requireNotNull(response.body).source()
            source.request(maxBytes + 1)
            require(source.buffer.size <= maxBytes)
            return source.buffer.readByteArray()
        }

        private fun sha256(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        /** The colon-separated upper-case form `PluginSources` records for installed packages. */
        private fun fingerprint(signer: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(signer).joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }
}
