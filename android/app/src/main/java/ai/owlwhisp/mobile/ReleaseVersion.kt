package ai.owlwhisp.mobile

data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString() = "$major.$minor.$patch"
    companion object {
        fun parse(text: String): ReleaseVersion? {
            if (!Regex("v?(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})").matches(text)) return null
            val parts = text.removePrefix("v").split(".").map { it.toInt() }
            return ReleaseVersion(parts[0], parts[1], parts[2])
        }
    }
}
