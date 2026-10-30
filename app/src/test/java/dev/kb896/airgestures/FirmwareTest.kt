package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FirmwareTest {
    @get:Rule val temp = TemporaryFolder()

    private fun write(file: File, text: String) = file.apply { parentFile?.mkdirs(); writeText(text) }

    @Test fun `a check's files from before keys were per band move into band 1's folder`() {
        val downloads = temp.newFolder("firmware")
        val build = File(downloads, "1054928927")
        write(File(build, "ota.zip"), "zip")
        for (name in listOf("response.json", "kek.json", "check.json")) write(File(build, name), name)
        Firmware.moveOldKeys(downloads, "tag1")
        for (name in listOf("response.json", "kek.json", "check.json")) {
            assertEquals(name, File(build, "bands/tag1/$name").readText())
            assertFalse("moved, not copied: $name", File(build, name).exists())
        }
        assertTrue("the package stays shared", File(build, "ota.zip").isFile)
        val pkg = Firmware.Package(build.name, build, "tag1")
        assertEquals("kek.json", pkg.bandKey?.readText())
    }

    @Test fun `a build that already has the band's folder keeps both, and other folders are left alone`() {
        val downloads = temp.newFolder("firmware")
        val build = File(downloads, "1003366292")
        write(File(build, "kek.json"), "old")
        write(File(build, "bands/tag1/kek.json"), "new")
        val notBuild = File(downloads, "notes")
        write(File(notBuild, "kek.json"), "x")
        val noKey = File(downloads, "1054928927").apply { mkdirs() }
        Firmware.moveOldKeys(downloads, "tag1")
        assertEquals("old", File(build, "kek.json").readText())
        assertEquals("new", File(build, "bands/tag1/kek.json").readText())
        assertTrue(File(notBuild, "kek.json").isFile)
        assertFalse("nothing to move: no band folder made", File(noKey, "bands").exists())
    }
}
