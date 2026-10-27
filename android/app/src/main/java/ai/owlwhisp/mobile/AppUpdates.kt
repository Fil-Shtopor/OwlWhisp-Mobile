package ai.owlwhisp.mobile

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class UpdateAsset(val url: String, val size: Long, val sha256: String)
data class AppRelease(val version: ReleaseVersion, val page: String, val notes: String, val asset: UpdateAsset?) {
    companion object {
        const val PAGE = "https://github.com/Fil-Shtopor/OwlWhisp-Mobile/releases"
        fun parse(json: JSONObject, current: String): AppRelease? {
            val installed = ReleaseVersion.parse(current) ?: error("Cannot read the installed app version")
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val tag = json.getString("tag_name")
            val version = ReleaseVersion.parse(tag) ?: error("Invalid release version")
            if (version <= installed) return null
            require(tag == "v$version") { "Unsupported release tag" }
            val page = "$PAGE/tag/$tag"
            require(json.getString("html_url") == page) { "Unexpected release page" }
            val name = "OwlWhisp-Mobile-$version-android.apk"
            val assets = json.getJSONArray("assets")
            val candidates = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name") == name }
            require(candidates.size <= 1) { "Ambiguous Android release assets" }
            val asset = candidates.firstOrNull()?.let {
                val url = "$PAGE/download/$tag/$name"
                require(it.getString("browser_download_url") == url) { "Unexpected APK download URL" }
                val size = it.getLong("size")
                require(size in 1..(256L * 1024 * 1024)) { "APK exceeds the download size limit" }
                val digest = it.optString("digest")
                require(Regex("sha256:[a-f0-9]{64}").matches(digest)) { "The release has no verified SHA-256 digest" }
                UpdateAsset(url, size, digest.removePrefix("sha256:"))
            }
            return AppRelease(version, page, json.optString("body").take(40_000), asset)
        }
    }
}

object UpdateNetwork {
    private val hosts = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    fun allowed(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host in hosts && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443)
    }.getOrDefault(false)
    fun open(url: String): HttpURLConnection {
        var next = url
        repeat(6) {
            require(allowed(next)) { "Update URL left GitHub's HTTPS servers" }
            val connection = URL(next).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000; connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "OwlWhisp-Mobile")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: error("Invalid update redirect")
                    next = URL(URL(next), location).toString()
                    connection.disconnect()
                } else {
                    require(status == 200) { if (status == 403 || status == 429) "GitHub rate limit reached. Try later." else "Update server returned HTTP $status" }
                    return connection
                }
            } catch (error: Throwable) { connection.disconnect(); throw error }
        }
        error("Too many update redirects")
    }
    fun metadata(): JSONObject {
        val connection = open("https://api.github.com/repos/Fil-Shtopor/OwlWhisp-Mobile/releases/latest")
        return try {
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 2 * 1024 * 1024) { "Release metadata is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            JSONObject(bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}

object VerifiedUpdateDownload {
    fun copy(input: InputStream, file: File, asset: UpdateAsset, cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {}) {
        require(asset.size > 0)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        file.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check(!cancelled()) { "Update download cancelled" }
                val size = input.read(buffer)
                if (size < 0) break
                count += size
                require(count <= asset.size) { "APK download exceeds the expected size" }
                output.write(buffer, 0, size); digest.update(buffer, 0, size)
                progress((count * 100 / asset.size).toInt())
            }
        }
        require(count == asset.size) { "APK download is incomplete" }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        require(hash == asset.sha256) { "APK checksum does not match the release" }
    }
}

object ApkUpdateVerifier {
    fun validate(archive: PackageInfo, installed: PackageInfo, release: ReleaseVersion, sdk: Int) {
        require(archive.packageName == installed.packageName) { "APK belongs to a different application" }
        require(ReleaseVersion.parse(archive.versionName.orEmpty()) == release) { "APK version does not match the release" }
        require(archive.longVersionCode > installed.longVersionCode) { "APK is not a newer build" }
        require((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= sdk) { "This update requires a newer Android version" }
        fun certificates(info: PackageInfo) = info.signingInfo?.apkContentsSigners?.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
        }?.toSet().orEmpty()
        val current = certificates(installed)
        require(current.isNotEmpty() && current == certificates(archive)) { "APK signing identity does not match the installed app" }
    }
    fun verify(context: Context, file: File, release: ReleaseVersion) {
        val manager = context.packageManager
        val archive = manager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Cannot read APK signature")
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        validate(archive, installed, release, android.os.Build.VERSION.SDK_INT)
    }
}

class AppUpdates(private val context: Context) {
    private val preferences = context.getSharedPreferences("owlwhisp", Context.MODE_PRIVATE)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = mutableSetOf<() -> Unit>()
    private val cancel = AtomicBoolean(false)
    private var lastLaunchCheck = 0L
    var available: AppRelease? = null; private set
    var readyFile: File? = null; private set
    var status = "Not checked yet"; private set
    var checking = false; private set
    var downloading = false; private set
    var progress = 0; private set
    var dismissedVersion: String? = null; private set
    var automatic: Boolean
        get() = preferences.getBoolean("automatic_updates", true)
        set(value) { preferences.edit().putBoolean("automatic_updates", value).apply(); changed() }
    private val installedVersion get() = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    fun observe(listener: () -> Unit) { listeners.add(listener); listener() }
    fun removeObserver(listener: () -> Unit) { listeners.remove(listener) }
    private fun changed() { listeners.toList().forEach { it() } }
    fun dismiss() { dismissedVersion = available?.version?.toString(); changed() }
    fun onLaunch() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastLaunchCheck != 0L && now - lastLaunchCheck < 60 * 60 * 1000) return
        if (automatic) { lastLaunchCheck = now; check() }
    }
    fun check() {
        if (checking || downloading) return
        checking = true; status = "Checking for updates"; changed()
        worker.execute {
            val result = runCatching { AppRelease.parse(UpdateNetwork.metadata(), installedVersion) }
            main.post {
                checking = false
                result.onSuccess {
                    if (available?.version != it?.version || it == null) readyFile = null
                    available = it
                    status = if (it == null) "Up to date" else "Version " + it.version + " is available"
                }.onFailure { status = "Could not check for updates. " + (it.message ?: "Try again later.") }
                changed()
            }
        }
    }
    fun cancelDownload() { cancel.set(true) }
    fun download() {
        val release = available ?: return
        val asset = release.asset ?: return
        if (checking || downloading) return
        downloading = true; readyFile = null; cancel.set(false); progress = 0; status = "Downloading update"; changed()
        worker.execute {
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            val partial = File(directory, "pending.apk")
            val destination = File(directory, "update.apk")
            val result = runCatching {
                val connection = UpdateNetwork.open(asset.url)
                try {
                    var reported = -1
                    connection.inputStream.use { input ->
                        VerifiedUpdateDownload.copy(input, partial, asset, { cancel.get() }) { percent ->
                            if (percent != reported) {
                                reported = percent
                                main.post { progress = percent; status = "Downloading update: $percent%"; changed() }
                            }
                        }
                    }
                } finally { connection.disconnect() }
                check(!cancel.get()) { "Update download cancelled" }
                ApkUpdateVerifier.verify(context, partial, release.version)
                if (destination.exists()) check(destination.delete()) { "Cannot replace the cached update" }
                check(partial.renameTo(destination)) { "Cannot save the downloaded update" }
                destination
            }
            partial.delete()
            main.post {
                downloading = false
                result.onSuccess { readyFile = it; status = "Update verified. Ready to install." }
                    .onFailure { status = it.message ?: "Update download failed" }
                changed()
            }
        }
    }
}
