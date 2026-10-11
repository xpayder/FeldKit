package app.feldkit

import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.storage.filesystem.FilesystemMounter
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Random

/**
 * Writes through an unlocked BitLocker volume (every sector encrypted on its way out) on a copy of the fixtures made by
 * tools/make-bitlocker.py, and leaves build/fixtures/bitlocker-written-<name>.img for tools/bitlocker/validate-written.sh, which
 * has dislocker and the Linux filesystem tools check it independently.
 */
class BitLockerWriteTest {
    private val dir = File(System.getProperty("fixtures.dir") ?: "build/fixtures")
    private val password = "VolumeX-Test-1"
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun run(name: String) {
        val src = File(dir, "$name.img"); assumeTrue("fixture missing", src.exists())
        val img = File(dir, "bitlocker-written-$name.img"); src.copyTo(img, overwrite = true)
        FilesystemMounter.enableEncryptedWrite = true; FilesystemMounter.enableNtfsWrite = true
        try {
            val dev = FileBlockDevice(img, true).also { it.open() }
            val part = FilesystemMounter.mountAll(dev).single()
            val r = part.reader
            assertTrue(r.unlock(0, password))
            val w = r.innerWriter(0) ?: fail("no writer after unlock").let { return }
            val root = r.rootEntry(0)
            val small = "written through BitLocker ✓\n".toByteArray()
            val big = ByteArray(2 * 1024 * 1024 + 77).also { Random(7).nextBytes(it) }
            assertTrue(w.writeFile(root, "new small.txt", small))
            assertTrue(w.createDirectory(root, "NewFolder"))
            val folder = r.listDirectory(0, "/").first { it.name == "NewFolder" }
            assertTrue(w.writeFile(folder, "big.bin", big))
            // rename and delete one existing file
            val victim = r.listDirectory(0, "/").first { !it.isDirectory && !it.name.startsWith("new small") && !it.name.startsWith("System") }
            assertTrue(w.renameEntry(victim, "renamed " + victim.name))
            val kill = r.listDirectory(0, "/").firstOrNull { !it.isDirectory && !it.name.startsWith("new small") && !it.name.startsWith("renamed") && !it.name.startsWith("System") && !it.name.startsWith("\$") }
            kill?.let { assertTrue(w.deleteEntry(it)) }
            dev.close()

            // a fresh mount of the changed image must show exactly that, decrypted
            val dev2 = FileBlockDevice(img, false).also { it.open() }
            val r2 = FilesystemMounter.mountAll(dev2).single().reader
            assertTrue(r2.unlock(0, password))
            val top = r2.listDirectory(0, "/")
            val s = top.first { it.name == "new small.txt" }; assertEquals(small.size.toLong(), s.size); assertArrayEquals(small, r2.readFile(s))
            val f2 = top.first { it.name == "NewFolder" }
            val b = r2.listDirectory(0, f2.path).first { it.name == "big.bin" }; assertEquals(sha(big), sha(r2.readFile(b)!!))
            assertTrue(top.any { it.name == "renamed " + victim.name }); assertFalse(top.any { it.name == victim.name })
            kill?.let { k -> assertFalse(top.any { it.name == k.name }) }
            File(dir, "bitlocker-written-$name.expect").writeText("${sha(small)}\t${small.size}\tnew small.txt\n${sha(big)}\t${big.size}\tNewFolder/big.bin\n")
            dev2.close()
        } finally { FilesystemMounter.enableEncryptedWrite = false; FilesystemMounter.enableNtfsWrite = false }
    }

    @Test fun exfatToGoXts256() = run("bitlocker-exfat")
    @Test fun ntfsXts128() = run("bitlocker-ntfs")
    @Test fun ntfsCbc128() = run("bitlocker-cbc")
}
