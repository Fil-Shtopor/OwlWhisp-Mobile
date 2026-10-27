package ai.owlwhisp.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Exercises production routing, storage, release validation and real Android panel visibility. */
class MobileFeaturesInstrumentation : Instrumentation() {
    private var arguments = Bundle()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); this.arguments = arguments ?: Bundle(); start() }
    override fun onStart() {
        val result = Bundle()
        val app = targetContext.applicationContext as OwlWhispApp
        val saved = app.preferences.all.toMap()
        var host: PanelPreviewActivity? = null
        val files = listOf(File(targetContext.filesDir, "benchmark.json"),
            File(targetContext.filesDir, "benchmarks/moonshine-tiny-en.json"), File(targetContext.filesDir, "benchmarks/gigaam-v3-ru.json"))
        val backups = files.associateWith { if (it.isFile) it.readBytes() else null }
        try {
            val checks = JSONArray()
            host = startActivitySync(Intent(targetContext, PanelPreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PanelPreviewActivity
            val activity = host
            runOnMainSync {
                app.preferences.edit().putString("model", "moonshine-tiny-en").putBoolean("language_routing", false)
                    .putString("routing_language", "en").remove("language_models").apply()
                check(app.model.id == "moonshine-tiny-en")
                app.assignLanguage("en", "moonshine-tiny-en"); app.assignLanguage("ru", "gigaam-v3-ru")
                app.setRoutingEnabled(true)
                check(app.model.id == "moonshine-tiny-en")
                check(runCatching { app.assignLanguage("ru", "moonshine-tiny-en") }.isFailure)
                check(app.languageAssignments["ru"] == "gigaam-v3-ru")
                if (arguments.getString("capture") == "true") {
                    check(app.chooseLanguage("ru")); check(app.model.id == "gigaam-v3-ru")
                    check(app.chooseLanguage("en")); check(app.model.id == "moonshine-tiny-en")
                }
                app.setRoutingEnabled(false); check(app.model.id == "moonshine-tiny-en")
            }
            checks.put("language assignments, validation and preserved manual model")
            fun panel(label: String?, recording: Boolean, name: String) {
                runOnMainSync {
                    activity.panel.render(AppState(if (recording) Phase.RECORDING else Phase.IDLE, "Ready"),
                        "Moonshine Tiny", true, true, recording, languageLabel = label)
                }
                waitForIdleSync(); Thread.sleep(500)
                lateinit var layout: JSONObject
                runOnMainSync { layout = activity.panel.layoutReport() }
                check(layout.getBoolean("language_visible") == (label != null))
                check(layout.getBoolean("language_enabled") == !recording)
                if (label != null) {
                    val bounds = layout.getString("language").split(",").map { it.toInt() }
                    check(bounds[2] > bounds[0] && bounds[3] > bounds[1])
                }
                uiAutomation.takeScreenshot().let { bitmap ->
                    File(targetContext.filesDir, name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
            }
            panel(null, false, "features-language-disabled")
            panel("EN", false, "features-language-ready")
            panel("EN", true, "features-language-recording")
            checks.put("language button hidden when disabled; visible idle; locked during recording")
            if (arguments.getString("capture") == "true") {
                runOnMainSync { app.setRoutingEnabled(true); app.start(this) {} }
                waitForIdleSync(); Thread.sleep(700)
                runOnMainSync {
                    check(app.state.phase == Phase.RECORDING)
                    val before = app.model.id
                    check(!app.chooseLanguage("ru"))
                    app.assignLanguage("en", "parakeet-tdt-0.6b-v3")
                    check(app.model.id == before)
                    app.setRoutingEnabled(false); check(app.routingEnabled)
                    app.cancelCurrent()
                }
                waitForIdleSync()
                checks.put("real recording rejects language/model/feature changes without stopping capture")
            }
            val store = BenchmarkStore(targetContext)
            for ((id, language) in listOf("moonshine-tiny-en" to "en", "gigaam-v3-ru" to "ru")) {
                val model = app.models.first { it.id == id }
                val rows = JSONArray().put(JSONObject().put("file", "fleurs_" + language + "_1.wav").put("language", language)
                    .put("reference", "one two").put("transcript", "one").put("audio_s", 2.0).put("elapsed_ms", 1000))
                store.save(JSONObject().put("model", id).put("model_version", model.manifest.getString("version"))
                    .put("app_version", "test fixture").put("rows", rows).toString())
            }
            check(store.report(app.models.first { it.id == "moonshine-tiny-en" }) != null)
            check(store.metrics(app.models.first { it.id == "moonshine-tiny-en" }).contains("50.0%"))
            check(store.metrics(app.models.first { it.id == "gigaam-v3-ru" }).contains("RU"))
            check(runCatching { store.save(JSONObject().put("model", "../escape").toString()) }.isFailure)
            checks.put("independent model histories, per-language metrics and safe storage paths")
            val guide = ModelGuide(targetContext)
            for (language in listOf("ru", "en", "zh", "ja", "de", "ar")) {
                for (tier in listOf("compact", "balanced", "roomy")) {
                    val suggestions = guide.recommend(language, tier, false, app.models)
                    check(suggestions.isNotEmpty() && suggestions.all { it.supported && language in it.languages })
                }
            }
            check(guide.recommend("ru", "balanced", false, app.models).first().id == "gigaam-v3-ru")
            val small = guide.recommend("en", "compact", false, app.models, true).first()
            check(small.totalBytes == app.models.filter { it.supported && "en" in it.languages }.minOf { it.totalBytes })
            checks.put("phone/language suggestions filter platform support and match smallest-download priority")
            val page = AppRelease.PAGE
            fun release() = JSONObject().put("tag_name", "v0.1.6").put("html_url", "$page/tag/v0.1.6")
                .put("draft", false).put("prerelease", false).put("body", "Test release")
                .put("assets", JSONArray().put(JSONObject().put("name", "OwlWhisp-Mobile-0.1.6-android.apk")
                    .put("browser_download_url", "$page/download/v0.1.6/OwlWhisp-Mobile-0.1.6-android.apk")
                    .put("size", 100).put("digest", "sha256:" + "a".repeat(64))))
            check(AppRelease.parse(release(), "0.1.5")?.asset != null)
            check(AppRelease.parse(release(), "0.1.6") == null)
            check(AppRelease.parse(release().put("prerelease", true), "0.1.5") == null)
            check(runCatching { AppRelease.parse(release().put("html_url", "https://evil.test"), "0.1.5") }.isFailure)
            val broken = release()
            broken.getJSONArray("assets").getJSONObject(0).put("digest", "")
            check(runCatching { AppRelease.parse(broken, "0.1.5") }.isFailure)
            checks.put("new/equal/preview releases, release origin and missing checksum handling")
            val apk = File(targetContext.cacheDir, "updates/provider-test.apk").apply { parentFile?.mkdirs(); writeText("provider fixture") }
            val uri = FileProvider.getUriForFile(targetContext, targetContext.packageName + ".updates", apk)
            check(targetContext.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } == "provider fixture")
            check(runCatching { FileProvider.getUriForFile(targetContext, targetContext.packageName + ".updates", File(targetContext.filesDir, "private.apk")) }.isFailure)
            apk.delete()
            checks.put("installer URI is readable and restricted to the update cache")
            val oldPath = arguments.getString("old_apk")
            val newPath = arguments.getString("new_apk")
            if (oldPath != null && newPath != null) {
                val manager = targetContext.packageManager
                val old = checkNotNull(manager.getPackageArchiveInfo(oldPath, PackageManager.GET_SIGNING_CERTIFICATES))
                val fresh = checkNotNull(manager.getPackageArchiveInfo(newPath, PackageManager.GET_SIGNING_CERTIFICATES))
                val version = checkNotNull(ReleaseVersion.parse(fresh.versionName.orEmpty()))
                ApkUpdateVerifier.validate(fresh, old, version, android.os.Build.VERSION.SDK_INT)
                check(runCatching { ApkUpdateVerifier.validate(old, fresh, checkNotNull(ReleaseVersion.parse(old.versionName.orEmpty())), android.os.Build.VERSION.SDK_INT) }.isFailure)
                fresh.signingInfo = manager.getPackageInfo(targetContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                check(runCatching { ApkUpdateVerifier.validate(fresh, old, version, android.os.Build.VERSION.SDK_INT) }.isFailure)
                checks.put("real release APK accepts prior signing identity and rejects downgrade or another signing key")
            }
            if (arguments.getString("network") == "true") {
                val metadata = UpdateNetwork.metadata()
                check(AppRelease.parse(metadata, "0.0.0")?.asset != null)
                checks.put("live GitHub release metadata")
            }
            result.putString("checks", checks.toString()); finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("failure", error.stackTraceToString()); finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync {
                app.cancelCurrent()
                val editor = app.preferences.edit().clear()
                saved.forEach { (key, value) -> when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                } }
                editor.apply()
                host?.finish()
            }
            backups.forEach { (file, bytes) -> if (bytes == null) file.delete() else { file.parentFile?.mkdirs(); file.writeBytes(bytes) } }
        }
    }
}
