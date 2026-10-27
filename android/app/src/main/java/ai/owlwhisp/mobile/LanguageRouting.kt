package ai.owlwhisp.mobile

object LanguageRouting {
    data class Model(val id: String, val languages: List<String>, val supported: Boolean = true)
    fun resolve(enabled: Boolean, language: String, assignments: Map<String, String>, manual: String, models: List<Model>): String {
        if (!enabled) return manual
        val id = assignments[language]
        return models.firstOrNull { it.id == id && it.supported && language in it.languages }?.id ?: manual
    }
    fun assign(language: String, id: String, models: List<Model>): Boolean =
        models.any { it.id == id && it.supported && language in it.languages }
}
