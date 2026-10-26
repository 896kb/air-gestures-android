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

    /**
     * One build Meta offered, as downloaded into files/firmware/<version>: ota.zip (the same for
     * every band) and, in bands/<tag>/, what Meta gave each band: response.json and kek.json,
     * the key wrapped for that band alone. [band] is the connected band's tag (see
     * [bandTag]); without it, or before that band's check, the offer read is any band's.
     */
    class Package(val version: String, val dir: File, band: String?) {
        /** This band's files, when Meta has been asked for them. */
        private val mine = band?.let { File(dir, "bands/$it") }?.takeIf { File(it, "response.json").isFile }
        private val response = runCatching { JSONObject(File(mine ?: anyBand(), "response.json").readText()) }.getOrNull()
        /** Meta's build number ("64"), from its offer. */
        val build: String? = response?.let { find(it, "build_number_display_name") }?.toString()
        val size: Long get() = File(dir, "ota.zip").length()
        /** The key (KEK) version the band needs to read this build. */
        val kek: Long? = response?.let { find(it, "kek_id") }?.toString()?.toLongOrNull()
        /** The key for this band, downloaded (another band's can't be used: the band refuses it). */
        val bandKey: File? = mine?.let { File(it, "kek.json") }?.takeIf { it.isFile }
        val complete: Boolean get() = File(dir, "ota.zip").isFile && response != null
        /** Meta's release notes as plain text. */
        val notes: String? = response?.let { find(it, "release_notes_cms") as? String }
            ?.replace(Regex("<if-param-isnt-set.*?</if-param-isnt-set>", RegexOption.DOT_MATCHES_ALL), "")
            ?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
        val title get() = build?.let { "Build $it" } ?: name(version)

        /** Some band's folder with an offer, else the package's own (as kept before keys were per band). */
        private fun anyBand(): File =
            File(dir, "bands").listFiles().orEmpty().firstOrNull { File(it, "response.json").isFile } ?: dir
    }

    /** The connected band's folder name for its firmware files (a hash of its serial), or null. */
    fun bandTag(): String? = BandService.instance?.deviceInfo()?.optString("tag")?.takeIf { it.isNotEmpty() }

    fun downloads(context: Context) = context.getExternalFilesDir("firmware")

    /** The downloaded builds, newest first, read for [band] (see [Package]). */
    fun packages(context: Context, band: String?): List<Package> =
        downloads(context)?.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.all(Char::isDigit) }
            .map { Package(it.name, it, band) }
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
     * lists, and this band's key (none when it hasn't one: never another band's). Only the
     * manifest's own paths are taken out of the zip.
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
        val kek = File(target, "kek.json")
        pkg.bandKey?.copyTo(kek, overwrite = true) ?: kek.delete()
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
