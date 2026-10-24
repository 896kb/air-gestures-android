package dev.kb896.airgestures

/** Band firmware versions as people know them. */
object Firmware {
    /** Meta's build numbers for the versions seen so far (the Meta AI app shows these). */
    private val BUILDS = mapOf("1003366292" to "64", "1054928927" to "70")

    /** "Build 64" for a known version, else the version number itself. */
    fun name(version: String) = BUILDS[version]?.let { "Build $it" } ?: version
}
