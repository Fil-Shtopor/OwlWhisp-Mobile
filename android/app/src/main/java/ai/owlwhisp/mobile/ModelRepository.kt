package ai.owlwhisp.mobile

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ModelFile(val name: String, val url: String, val bytes: Long, val sha256: String)

class ModelRepository(context: Context) {
    val directory = File(context.noBackupFilesDir, "models/parakeet-tdt-0.6b-v3")
    private val manifest = JSONObject(context.assets.open("models/parakeet-tdt-0.6b-v3.json").bufferedReader().use { it.readText() })
    val name: String = "Parakeet 0.6B v3 · 25 языков"
    val files: List<ModelFile> = buildList {
        val common = manifest.getJSONArray("common")
        for (i in 0 until common.length()) add(parse(common.getJSONObject(i)))
        val artifacts = manifest.getJSONArray("artifacts")
        for (i in 0 until artifacts.length()) {
            val artifact = artifacts.getJSONObject(i)
            if (artifact.getString("target") == "cpu_int8") {
                val entries = artifact.getJSONArray("files")
                for (j in 0 until entries.length()) add(parse(entries.getJSONObject(j)))
            }
        }
    }.also { require(it.map { file -> file.name }.toSet().size == it.size) }

    val totalBytes: Long get() = files.sumOf { it.bytes }
    fun present(): Boolean = files.all { File(directory, it.name).let { file -> file.isFile && file.length() == it.bytes } }

    fun verify() {
        for (entry in files) require(valid(File(directory, entry.name), entry)) {
            "Файлы модели повреждены. Загрузите модель заново."
        }
    }

    fun install(onProgress: (Long, Long) -> Unit) {
        check(directory.mkdirs() || directory.isDirectory) { "Не удалось создать каталог модели" }
        var completed = 0L
        for (entry in files) {
            val destination = File(directory, entry.name)
            if (!valid(destination, entry)) download(entry, destination) { count -> onProgress(completed + count, totalBytes) }
            completed += entry.bytes
            onProgress(completed, totalBytes)
        }
        verify()
    }

    private fun parse(value: JSONObject): ModelFile {
        val name = value.getString("path")
        require(name.matches(Regex("[A-Za-z0-9_.-]+")) && name !in setOf(".", ".."))
        val url = value.getString("url")
        require(URL(url).protocol == "https")
        val bytes = value.getLong("bytes")
        val hash = value.getString("sha256")
        require(bytes > 0 && hash.matches(Regex("[a-f0-9]{64}")))
        return ModelFile(name, url, bytes, hash)
    }

    private fun valid(file: File, entry: ModelFile): Boolean {
        if (!file.isFile || file.length() != entry.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == entry.sha256
    }

    private fun download(entry: ModelFile, destination: File, onProgress: (Long) -> Unit) {
        val partial = File(directory, entry.name + ".part")
        val connection = URL(entry.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "OwlWhisp-Mobile/0.1")
        try {
            require(connection.responseCode in 200..299) { "Ошибка загрузки: HTTP " + connection.responseCode }
            require(connection.url.protocol == "https") { "Небезопасное перенаправление" }
            var downloaded = 0L
            connection.inputStream.buffered().use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        downloaded += size
                        require(downloaded <= entry.bytes) { "Неверный размер модели" }
                        output.write(buffer, 0, size)
                        onProgress(downloaded)
                    }
                }
            }
            require(valid(partial, entry)) { "Модель не прошла проверку SHA-256" }
            java.nio.file.Files.move(partial.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }
}
