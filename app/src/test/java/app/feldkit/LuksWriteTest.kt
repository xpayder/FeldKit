package app.feldkit

import app.feldkit.storage.filesystem.FilesystemMounter
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Random

/** Writes through an unlocked LUKS container (made by tools/linux/make-luks-writable.sh); tools/linux/validate-luks-written.sh checks the result with cryptsetup. */
class LuksWriteTest {
    private val dir = File(System.getProperty("fixtures.dir") ?: "build/fixtures")
    private val pass = "FeldKit-Test-1"
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun run(name: String) {
        val src = File(dir, "$name.img"); assumeTrue("fixture missing (tools/linux/make-luks-writable.sh)", src.exists())
        val img = File(dir, "written-$name.img"); src.copyTo(img, overwrite = true)
        FilesystemMounter.enableEncryptedWrite = true
        try {
            val dev = FileBlockDevice(img, true).also { it.open() }
            val r = FilesystemMounter.mountAll(dev).single().reader
            assertTrue(r.unlock(0, pass))
            val w = r.innerWriter(0); assertNotNull("no writer after unlock", w)
            val root = r.rootEntry(0)
            val small = "written through LUKS ✓\n".toByteArray()
            val big = ByteArray(1_500_000 + 31).also { Random(9).nextBytes(it) }
            assertTrue(w!!.writeFile(root, "new small.txt", small))
            assertTrue(w.createDirectory(root, "NewFolder"))
            val folder = r.listDirectory(0, "/").first { it.name == "NewFolder" }
            assertTrue(w.writeFile(folder, "big.bin", big))
            val old = r.listDirectory(0, "/").first { it.name == "dir" }
            val oldFile = r.listDirectory(0, old.path).first { it.name == "old.bin" }
            assertTrue(w.deleteEntry(oldFile))
            dev.close()
            val dev2 = FileBlockDevice(img, false).also { it.open() }
            val r2 = FilesystemMounter.mountAll(dev2).single().reader
            assertTrue(r2.unlock(0, pass))
            val top = r2.listDirectory(0, "/")
            assertArrayEquals(small, r2.readFile(top.first { it.name == "new small.txt" }))
            val b = r2.listDirectory(0, top.first { it.name == "NewFolder" }.path).first { it.name == "big.bin" }
            assertEquals(sha(big), sha(r2.readFile(b)!!))
            assertTrue(r2.listDirectory(0, top.first { it.name == "dir" }.path).none { it.name == "old.bin" })
            File(dir, "written-$name.expect").writeText("${sha(small)}\t${small.size}\tnew small.txt\n${sha(big)}\t${big.size}\tNewFolder/big.bin\n")
            dev2.close()
        } finally { FilesystemMounter.enableEncryptedWrite = false }
    }

    @Test fun exfatInLuks2Xts() = run("luksw-exfat-xts")
    @Test fun fat32InLuks1CbcEssiv() = run("luksw-fat32-cbc")
    @Test fun exfatInLuks2With4kSectors() = run("luksw-exfat-4k")
}
