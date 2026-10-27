package ai.owlwhisp.mobile

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

data class ModelFile(val name: String, val url: String, val bytes: Long, val sha256: String)

class ModelRepository(context: Context, val id: String = "parakeet-tdt-0.6b-v3") {
    companion object {
        fun ids(context: Context): List<String> {
            val list = JSONObject(context.assets.open("models/catalog.json").bufferedReader().use { it.readText() }).getJSONArray("android")
            return (0 until list.length()).map { list.getString(it) }
        }
        fun catalog(context: Context) = ids(context).map { ModelRepository(context, it) }
    }
    init { require(id in ids(context)) }
    val manifest = JSONObject(context.assets.open("models/" + id + ".json").bufferedReader().use { it.readText() })
    val directory = File(context.noBackupFilesDir, "models/" + id)
    val name = manifest.getString("name")
    val engine = manifest.optString("engine", "sherpa")
    val recognition: JSONObject get() = manifest.getJSONObject("recognition")
    val languages = manifest.getJSONArray("languages").let { a -> (0 until a.length()).map { a.getString(it) } }
    val supported = ModelAvailability.supports(engine, android.os.Build.SUPPORTED_ABIS.toList())
    private val downloads = buildList<JSONObject> {
        val common = manifest.getJSONArray("common")
        for (i in 0 until common.length()) add(common.getJSONObject(i))
        val artifacts = manifest.getJSONArray("artifacts")
        for (i in 0 until artifacts.length()) {
            val artifact = artifacts.getJSONObject(i)
            if (artifact.getString("target") == "cpu_int8") {
                val entries = artifact.getJSONArray("files")
                for (j in 0 until entries.length()) add(entries.getJSONObject(j))
            }
        }
    }
    val files = downloads.flatMap { spec ->
        val unpack = spec.optJSONArray("unpack")
        if (unpack == null) listOf(parse(spec)) else (0 until unpack.length()).map { parse(unpack.getJSONObject(it)) }
    }.also { require(it.isNotEmpty() && it.map { file -> file.name }.toSet().size == it.size) }
    val totalBytes: Long get() = downloads.sumOf { it.getLong("bytes") }
    val installedBytes: Long get() = files.sumOf { it.bytes }
    fun present() = files.all { File(directory, it.name).let { file -> file.isFile && file.length() == it.bytes } }
    fun verify() {
        for (entry in files) require(valid(File(directory, entry.name), entry)) {
            "Model files are corrupt. Download the model again."
        }
    }
    fun delete() {
        for (entry in files) File(directory, entry.name).delete()
        for (spec in downloads) { File(directory, spec.getString("path")).delete(); File(directory, spec.getString("path") + ".part").delete() }
        directory.delete()
    }
    fun install(onProgress: (Long, Long) -> Unit) {
        require(supported) { "Whistle requires an ARM64 device" }
        check(directory.mkdirs() || directory.isDirectory)
        var completed = 0L
        for (spec in downloads) {
            val unpack = spec.optJSONArray("unpack")
            if (unpack == null) {
                val entry = parse(spec)
                val destination = File(directory, entry.name)
                if (!valid(destination, entry)) download(entry, destination) { count -> onProgress(completed + count, totalBytes) }
            } else if (!files.all { valid(File(directory, it.name), it) }) {
                val entry = parse(spec)
                val archive = File(directory, entry.name)
                try {
                    download(entry, archive) { count -> onProgress(completed + count, totalBytes) }
                    val expected = (0 until unpack.length()).associate { unpack.getJSONObject(it).let { row -> row.getString("archive_path") to parse(row) } }
                    unpack(archive, expected)
                } finally { archive.delete() }
            }
            completed += spec.getLong("bytes"); onProgress(completed, totalBytes)
        }
        verify()
    }

    private fun unpack(archive: File, expected: Map<String, ModelFile>) {
        val staged = mutableListOf<Pair<File, File>>()
        try {
            GZIPInputStream(archive.inputStream().buffered()).use { input ->
                val header = ByteArray(512)
                val seen = mutableSetOf<String>()
                while (true) {
                    var read = 0
                    while (read < 512) { val n = input.read(header, read, 512 - read); require(n >= 0) { "Incomplete TAR archive" }; read += n }
                    if (header.all { it == 0.toByte() }) break
                    fun field(start: Int, length: Int) = String(header, start, length, Charsets.US_ASCII).trim('\u0000', ' ')
                    val name = field(0, 100)
                    val size = field(124, 12).toLong(8)
                    require(size in 0..(512L * 1024 * 1024))
                    val entry = expected[name]
                    if (entry != null) {
                        require(seen.add(name) && size == entry.bytes && header[156].toInt() in listOf(0, 48))
                        val partial = File(directory, entry.name + ".part")
                        staged.add(partial to File(directory, entry.name))
                        partial.outputStream().buffered().use { out ->
                            var left = size; val buffer = ByteArray(256 * 1024)
                            while (left > 0) { val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt()); require(n > 0); out.write(buffer, 0, n); left -= n }
                        }
                        require(valid(partial, entry)) { "Extracted model failed SHA-256 verification" }
                    } else {
                        var left = size
                        while (left > 0) { val n = input.skip(left); require(n > 0); left -= n }
                    }
                    var padding = (512 - size % 512) % 512
                    while (padding > 0) { val n = input.skip(padding); require(n > 0); padding -= n }
                }
                require(seen == expected.keys) { "Incomplete model archive" }
            }
            for ((part, dest) in staged) java.nio.file.Files.move(part.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { staged.forEach { it.first.delete() } }
    }

    private fun parse(value: JSONObject): ModelFile {
        val name = value.getString("path")
        require(name.split('/').all { it.matches(Regex("[A-Za-z0-9_.-]+")) && it !in setOf(".", "..") })
        val url = value.optString("url", "")
        require(url.isEmpty() || URL(url).protocol == "https")
        val bytes = value.getLong("bytes"); val hash = value.getString("sha256")
        require(bytes > 0 && hash.matches(Regex("[a-f0-9]{64}")))
        return ModelFile(name, url, bytes, hash)
    }
    private fun valid(file: File, entry: ModelFile): Boolean {
        if (!file.isFile || file.length() != entry.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == entry.sha256
    }
    private fun download(entry: ModelFile, destination: File, onProgress: (Long) -> Unit) {
        require(entry.url.startsWith("https://"))
        val partial = File(directory, entry.name + ".part")
        check(partial.parentFile!!.mkdirs() || partial.parentFile!!.isDirectory)
        val connection = URL(entry.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000; connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "OwlWhisp-Mobile/0.1")
        try {
            require(connection.responseCode in 200..299) { "HTTP " + connection.responseCode }
            require(connection.url.protocol == "https")
            var downloaded = 0L
            connection.inputStream.buffered().use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val size = input.read(buffer); if (size < 0) break
                        downloaded += size; require(downloaded <= entry.bytes)
                        output.write(buffer, 0, size); onProgress(downloaded)
                    }
                }
            }
            require(valid(partial, entry)) { "Model failed SHA-256 verification" }
            java.nio.file.Files.move(partial.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { connection.disconnect(); partial.delete() }
    }
}
