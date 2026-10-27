package ai.owlwhisp.mobile

import android.app.ActivityManager
import android.content.Context
import org.json.JSONObject
import java.util.Locale

class ModelGuide(context: Context) {
    val data = JSONObject(context.assets.open("model-guide.json").bufferedReader().use { it.readText() })
    private val entries = data.getJSONArray("models").let { array -> (0 until array.length()).associate { array.getJSONObject(it).let { it.getString("id") to it } } }
    val profiles = data.getJSONArray("profiles").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
    val memoryGiB = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem / 1073741824.0
    val deviceTier get() = tier(memoryGiB)
    fun name(id: String): String = entries[id]?.getString("name") ?: id
    fun purpose(id: String): String = entries[id]?.getString("purpose") ?: ""
    fun recommend(language: String, tier: String, coverage: Boolean, models: List<ModelRepository>, smallest: Boolean = false): List<ModelRepository> {
        val key = if (coverage) "default" else if (language in listOf("zh", "ja", "ko", "yue")) "east_asian" else language
        val rules = data.getJSONObject("recommendations")
        val choices = (rules.optJSONObject(key) ?: rules.getJSONObject("default")).getJSONArray(tier)
        val order = (0 until choices.length()).map { choices.getString(it) }
        val eligible = models.filter { it.supported && language in it.languages }
        if (smallest) return eligible.sortedBy { it.totalBytes }.take(3)
        return eligible.sortedWith(compareBy<ModelRepository> { order.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }.thenBy { it.totalBytes }).take(3)
    }
    companion object {
        fun tier(memoryGiB: Double) = when { memoryGiB <= 4.5 -> "compact"; memoryGiB <= 8.5 -> "balanced"; else -> "roomy" }
        fun languageName(code: String) = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
    }
}
