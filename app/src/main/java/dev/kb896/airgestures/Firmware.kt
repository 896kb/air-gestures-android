package dev.kb896.airgestures

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** Band firmware: the builds Meta offers this band, kept on the phone, and unpacked to install. */
object Firmware {
    /** Meta's build numbers for the versions seen so far (the Meta AI app shows these). */
    private val BUILDS = mapOf("1003366292" to "64", "1054928927" to "70")

    /** "Build 64" for a known version, else the version number itself. */
    fun name(version: String) = BUILDS[version]?.let { "Build $it" } ?: version

    /** One build Meta offered, as downloaded into files/firmware/<version>: ota.zip, kek.json, response.json. */
    class Package(val version: String, val dir: File) {
        private val response = runCatching { JSONObject(File(dir, "response.json").readText()) }.getOrNull()
        /** Meta's build number ("64"), from its offer. */
        val build: String? = response?.let { find(it, "build_number_display_name") }?.toString()
        val size: Long get() = File(dir, "ota.zip").length()
        val complete: Boolean get() = File(dir, "ota.zip").isFile && File(dir, "kek.json").isFile
        /** Meta's release notes as plain text. */
        val notes: String? = response?.let { find(it, "release_notes_cms") as? String }
            ?.replace(Regex("<if-param-isnt-set.*?</if-param-isnt-set>", RegexOption.DOT_MATCHES_ALL), "")
            ?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
        val title get() = build?.let { "Build $it" } ?: name(version)
    }

    fun downloads(context: Context) = context.getExternalFilesDir("firmware")

    /** The downloaded builds, newest first. */
    fun packages(context: Context): List<Package> =
        downloads(context)?.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.all(Char::isDigit) }
            .map { Package(it.name, it) }
            .filter { it.complete }
            .sortedByDescending { it.version.toLong() }

    /**
     * Ask Meta for this band's firmware and download what it offers (blocking: off the main
     * thread). Needs the Meta sign-in and the band's serial. A one-line summary.
     */
    fun check(context: Context, session: String, serial: String): String {
        val dir = downloads(context)?.path ?: return "no storage for downloads"
        val summary = JSONObject(Bridge.firmwareArchive(session, serial, "1", dir))
        val offers = summary.optJSONArray("offers") ?: JSONArray()
        val found = (0 until offers.length()).mapNotNull { offers.optJSONObject(it) }
            .filterNot { it.optBoolean("same_as_above") }
            .map { offer -> offer.optString("name").takeIf { it.isNotEmpty() }?.let { "build $it" } ?: offer.optString("target_version") }
        return if (found.isEmpty()) "Meta offered no firmware" else "Meta offers ${found.joinToString(", ")}"
    }

    /**
     * The package unzipped for the band (files/ota/<version>): the manifest, the items it
     * lists, and the key. Only the manifest's own paths are taken out of the zip.
     */
    fun unpack(context: Context, pkg: Package): File {
        val target = File(context.getExternalFilesDir("ota"), pkg.version)
        target.mkdirs()
        ZipFile(File(pkg.dir, "ota.zip")).use { zip ->
            val manifestEntry = zip.getEntry("manifest.json") ?: throw IllegalStateException("the package has no manifest")
            val manifest = zip.getInputStream(manifestEntry).use { it.readBytes() }
            val items = JSONObject(manifest.decodeToString()).optJSONArray("items") ?: throw IllegalStateException("the manifest lists nothing")
            File(target, "manifest.json").writeBytes(manifest)
            for (i in 0 until items.length()) {
                val path = items.getJSONObject(i).getString("path")
                if (path.contains("..") || path.startsWith("/")) throw IllegalStateException("odd path in the manifest: $path")
                val entry = zip.getEntry(path) ?: throw IllegalStateException("the package lacks $path")
                val out = File(target, path)
                if (out.length() == entry.size) continue
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
        File(pkg.dir, "kek.json").copyTo(File(target, "kek.json"), overwrite = true)
        return target
    }

    /** The first value named [key] anywhere in [json]. */
    private fun find(json: Any?, key: String): Any? = when (json) {
        is JSONObject -> json.opt(key)?.takeUnless { it == JSONObject.NULL }
            ?: json.keys().asSequence().firstNotNullOfOrNull { find(json.opt(it), key) }
        is JSONArray -> (0 until json.length()).firstNotNullOfOrNull { find(json.opt(it), key) }
        else -> null
    }
}
