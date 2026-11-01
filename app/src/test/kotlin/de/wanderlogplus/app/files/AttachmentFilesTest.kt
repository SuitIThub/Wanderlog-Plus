package de.wanderlogplus.app.files

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    private val dir by lazy { tmp.newFolder("att") }
    private fun files() = AttachmentFiles(dir)

    @Test fun importHashesAndStoresUnderHash() {
        val f = files()
        val r = f.importStream("abc".byteInputStream())
        assertEquals(AttachmentFiles.Imported(abc, 3), r)
        assertEquals("abc", f.fileFor(abc)!!.readText())
    }

    @Test fun importSameContentTwiceReusesFile() {
        val f = files()
        val a = f.importStream("abc".byteInputStream())
        val b = f.importStream("abc".byteInputStream())
        assertEquals(a, b)
        assertEquals(1, f.target(abc).parentFile!!.listFiles()!!.size)
    }

    @Test fun tooLargeThrowsAndLeavesNoTmp() {
        val f = files()
        assertThrows(AttachmentTooLargeException::class.java) { f.importStream("abc".byteInputStream(), maxBytes = 2) }
        assertEquals(0, f.target(abc).parentFile!!.listFiles()!!.size)
    }

    @Test fun fileForRejectsInvalidHash() {
        assertNull(files().fileFor("../x"))
        assertNull(files().fileFor(abc)) // valid but absent
    }

    @Test fun versionCountsUpOnAdd() {
        val f = files()
        val before = f.version.value
        f.importStream("abc".byteInputStream())
        assertTrue(f.version.value > before)
    }
}
