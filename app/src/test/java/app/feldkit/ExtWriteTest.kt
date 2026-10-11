package app.feldkit

import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.storage.filesystem.FileSystemReader
import app.feldkit.storage.filesystem.FilesystemMounter
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.security.MessageDigest
import java.util.Random

/**
 * Runs the ext writer on a copy of each image made by tools/linux/make-ext-fixtures.sh (real mke2fs: ext2/3/4, 1 and 4 KiB blocks,
 * with and without metadata_csum, 32- and 64-bit, many groups), checks the result by reading it back, and leaves the image and
 * the full expected file list in build/fixtures/written-ext-<name>.{img,expect}; tools/linux/validate-ext-written.sh then runs
 * e2fsck and a real kernel mount on it.
 */
@RunWith(Parameterized::class)
class ExtWriteTest(private val name: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun params() = listOf("ext4-1k", "ext4-4k", "ext4-nocsum", "ext4-nojrnl", "ext4-32bit", "ext4-gdtcsum", "ext3", "ext2", "ext2-4k", "ext4-multi")
        private val dir = File(System.getProperty("fixtures.dir") ?: "build/fixtures")
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun tree(r: FileSystemReader, path: String = "/", out: MutableMap<String, Pair<String, Long>> = LinkedHashMap()): MutableMap<String, Pair<String, Long>> {
        for (e in r.listDirectory(0, path)) {
            if (e.isDirectory) { if (e.path != "/lost+found") tree(r, e.path, out) }
            else if (r.readFile(e)?.let { d -> out[e.path.trimStart('/')] = sha(d) to d.size.toLong() } == null) {}
        }
        return out
    }

    private fun find(r: FileSystemReader, path: String): FileSystemEntry =
        r.listDirectory(0, path.substringBeforeLast('/', "").ifEmpty { "/" }.let { if (it.startsWith("/")) it else "/$it" }).first { it.name == path.substringAfterLast('/') }

    @Test fun createDeleteRenameAndGrow() {
        val src = File(dir, "$name.img"); assumeTrue("fixture missing (tools/linux/make-ext-fixtures.sh)", src.exists())
        val img = File(dir, "written-ext-$name.img"); src.copyTo(img, overwrite = true)
        FilesystemMounter.enableExtWrite = true
        try {
            val dev = FileBlockDevice(img, true).also { it.open() }
            val (r, w0) = FilesystemMounter.mount(dev)!!
            assertNotNull("$name: the ext writer is not available (volume refused?)", w0)
            val w = w0!!
            val before = tree(r)
            val expected = LinkedHashMap(before)
            val rnd = Random(1234)
            fun rand(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
            fun put(parent: String, fname: String, data: ByteArray) {
                val p = if (parent == "/") r.rootEntry(0) else find(r, parent)
                assertTrue("$name: write $parent/$fname", w.writeFile(p, fname, data))
                expected[(parent.trim('/') + "/" + fname).trimStart('/')] = sha(data) to data.size.toLong()
            }

            // 1. new files: tiny, empty, block-aligned, odd-sized, multi-megabyte, unicode name
            put("/", "new small.txt", "created by FeldKit ✓\n".toByteArray())
            put("/", "new empty.txt", ByteArray(0))
            put("/", "exact.bin", rand(4096 * 3))
            put("/", "odd.bin", rand(1024 * 7 + 13))
            put("/", "Новый файл.txt", "привет".toByteArray())
            put("/dir", "inside dir.bin", rand(3 * 1024 * 1024 + 123))
            // 2. folders, also nested
            assertTrue(w.createDirectory(r.rootEntry(0), "NewFolder"))
            assertTrue(w.createDirectory(find(r, "/NewFolder"), "nested"))
            put("/NewFolder/nested", "deep.txt", "deep file\n".toByteArray())
            // 3. the hash-tree directory: add enough entries to need more directory blocks
            for (i in 1..150) put("/many", "added file with a fairly long name to fill blocks number $i.txt", "x$i".toByteArray())
            // 4. delete: a big file, the sparse file (extent tree), a symlink, a folder with content, a few from the big directory
            for (victim in listOf("/big.bin", "/sparse.bin", "/link-to-hello", "/many/file_7.txt", "/many/file_250.txt", "/odd1025.bin")) {
                assertTrue("$name: delete $victim", w.deleteEntry(find(r, victim)))
                expected.remove(victim.trimStart('/'))
            }
            assertTrue(w.deleteEntry(find(r, "/dir")))
            expected.keys.removeAll { it.startsWith("dir/") }
            // 5. rename a file and a folder
            assertTrue(w.renameEntry(find(r, "/hello.txt"), "renamed hello.txt")); expected["renamed hello.txt"] = expected.remove("hello.txt")!!
            assertTrue(w.renameEntry(find(r, "/many"), "manyrenamed"))
            for (k in expected.keys.filter { it.startsWith("many/") }) expected["manyrenamed/" + k.removePrefix("many/")] = expected.remove(k)!!
            // duplicates and bad names are refused
            assertFalse(w.writeFile(r.rootEntry(0), "exact.bin", ByteArray(1)))
            assertFalse(w.createDirectory(r.rootEntry(0), "NewFolder"))
            assertFalse(w.writeFile(r.rootEntry(0), "a/b", ByteArray(1)))
            if (name == "ext4-multi") put("/", "huge across groups.bin", rand(220 * 1024 * 1024))
            dev.close()

            // a fresh read of the changed image must match exactly
            val dev2 = FileBlockDevice(img, false).also { it.open() }
            val r2 = FilesystemMounter.mount(dev2)!!.first
            val after = tree(r2)
            assertEquals("$name: file set", expected.keys, after.keys)
            for ((p, v) in expected) assertEquals("$name: $p", v, after[p])
            File(dir, "written-ext-$name.expect").writeText(expected.entries.sortedBy { it.key }.joinToString("") { "${it.value.first}\t${it.value.second}\t${it.key}\n" })
            dev2.close()
        } finally { FilesystemMounter.enableExtWrite = false }
    }
}

/** Fragmentation, deep extent trees, double-indirect block maps, directories of thousands of entries and groups running out of inodes. */
@RunWith(Parameterized::class)
class ExtStressTest(private val name: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun params() = listOf("ext4-4k", "ext4-1k", "ext2-4k", "ext3", "ext4-multi")
        private val dir = File(System.getProperty("fixtures.dir") ?: "build/fixtures")
    }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun churn() {
        val src = File(dir, "$name.img"); assumeTrue("fixture missing", src.exists())
        val img = File(dir, "written-ext-stress-$name.img"); src.copyTo(img, overwrite = true)
        FilesystemMounter.enableExtWrite = true
        try {
            val dev = FileBlockDevice(img, true).also { it.open() }
            val (r, w0) = FilesystemMounter.mount(dev)!!; val w = w0!!
            val rnd = Random(99); val expected = LinkedHashMap<String, Pair<String, Long>>()
            fun rand(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
            val bs = if (name.endsWith("1k") || name == "ext3") 1024 else 4096
            assertTrue(w.createDirectory(r.rootEntry(0), "stress"))
            fun stress() = r.listDirectory(0, "/").first { it.name == "stress" }
            // 1. 200 files of 1..60 blocks, then delete the odd ones: free space full of holes
            val sizes = (1..200).map { (1 + rnd.nextInt(60)) * bs - rnd.nextInt(bs / 2) }
            for ((i, s) in sizes.withIndex()) { val d = rand(s); assertTrue(w.writeFile(stress(), "f$i.bin", d)); if (i % 2 == 0) expected["stress/f$i.bin"] = sha(d) to d.size.toLong() }
            for (i in 1 until 200 step 2) assertTrue(w.deleteEntry(r.listDirectory(0, "/stress").first { it.name == "f$i.bin" }))
            // 2. a file that cannot be contiguous any more: many extents, so an extent tree one level deep (or indirect blocks)
            val frag = rand(if (name == "ext2-4k") 12 * 1024 * 1024 + 5 else 3 * 1024 * 1024 + 5)
            assertTrue(w.writeFile(stress(), "fragmented.bin", frag)); expected["stress/fragmented.bin"] = sha(frag) to frag.size.toLong()
            // 3. a directory with thousands of entries (many directory blocks; on ext4-multi the root group runs out of inodes)
            assertTrue(w.createDirectory(stress(), "small"))
            val count = if (name == "ext4-multi") 4200 else 1500
            for (i in 0 until count) { val d = "n$i".toByteArray(); assertTrue("file $i", w.writeFile(r.listDirectory(0, "/stress").first { it.name == "small" }, "small file number $i.txt", d)); if (i % 7 == 0) expected["stress/small/small file number $i.txt"] = sha(d) to d.size.toLong() }
            // remove most of them again, keep every 7th
            val small = r.listDirectory(0, "/stress").first { it.name == "small" }
            for (e in r.listDirectory(0, small.path)) { val i = e.name.removePrefix("small file number ").removeSuffix(".txt").toInt(); if (i % 7 != 0) assertTrue(w.deleteEntry(e)) }
            // 4. delete the whole tree below one folder in one call
            assertTrue(w.createDirectory(stress(), "tmp")); assertTrue(w.createDirectory(r.listDirectory(0, "/stress").first { it.name == "tmp" }, "inner"))
            assertTrue(w.writeFile(r.listDirectory(0, "/stress/tmp").first { it.name == "inner" }, "x.bin", rand(70000)))
            assertTrue(w.deleteEntry(r.listDirectory(0, "/stress").first { it.name == "tmp" }))
            dev.close()
            val dev2 = FileBlockDevice(img, false).also { it.open() }
            val r2 = FilesystemMounter.mount(dev2)!!.first
            fun walk(p: String, out: MutableMap<String, Pair<String, Long>>) { for (e in r2.listDirectory(0, p)) if (e.isDirectory) walk(e.path, out) else out[e.path.trimStart('/')] = sha(r2.readFile(e)!!) to e.size }
            val got = LinkedHashMap<String, Pair<String, Long>>(); walk("/stress", got)
            assertEquals(expected.keys, got.keys); for ((k, v) in expected) assertEquals(k, v, got[k])
            // the original files must be untouched
            val orig = FileBlockDevice(src, false).also { it.open() }
            val r0 = FilesystemMounter.mount(orig)!!.first
            fun walk0(p: String) { for (e in r0.listDirectory(0, p)) { if (e.isDirectory) { if (e.path != "/lost+found") walk0(e.path) } else assertEquals(e.path, sha(r0.readFile(e)!!), sha(r2.readFile(r2.listDirectory(0, p).first { it.name == e.name })!!)) } }
            walk0("/")
            val all = LinkedHashMap<String, Pair<String, Long>>()
            fun walkAll(p: String) { for (e in r2.listDirectory(0, p)) if (e.isDirectory) { if (e.path != "/lost+found") walkAll(e.path) } else all[e.path.trimStart('/')] = sha(r2.readFile(e)!!) to e.size }
            walkAll("/")
            File(dir, "written-ext-stress-$name.expect").writeText(all.entries.sortedBy { it.key }.joinToString("") { "${it.value.first}\t${it.value.second}\t${it.key}\n" })
            dev2.close(); orig.close()
        } finally { FilesystemMounter.enableExtWrite = false }
    }
}

/** The writer must stay out of volumes that are not in a clean, supported state. */
class ExtRefusalTest {
    private val dir = File(System.getProperty("fixtures.dir") ?: "build/fixtures")
    private fun sb(name: String): ByteArray? = File(dir, "$name.img").takeIf { it.exists() }?.inputStream()?.use { it.skip(1024); it.readNBytes(1024) }
    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    private fun put32(b: ByteArray, o: Int, v: Int) { for (i in 0..3) b[o + i] = (v ushr (8 * i)).toByte() }

    @Test fun refusals() {
        val clean = sb("ext4-4k") ?: return
        assertNull(app.feldkit.storage.filesystem.ext.ExtWriter.refusal(clean))
        fun mutated(f: (ByteArray) -> Unit) = clean.copyOf().also(f).let { app.feldkit.storage.filesystem.ext.ExtWriter.refusal(it) }
        assertNotNull("journal needs recovery", mutated { put32(it, 0x60, le32(it, 0x60) or 0x4) })
        assertNotNull("bigalloc", mutated { put32(it, 0x64, le32(it, 0x64) or 0x200) })
        assertNotNull("quota", mutated { put32(it, 0x64, le32(it, 0x64) or 0x100) })
        assertNotNull("inline data", mutated { put32(it, 0x60, le32(it, 0x60) or 0x8000) })
        assertNotNull("encrypted", mutated { put32(it, 0x60, le32(it, 0x60) or 0x10000) })
        assertNotNull("meta_bg", mutated { put32(it, 0x60, le32(it, 0x60) or 0x10) })
        assertNotNull("not unmounted cleanly", mutated { it[0x3A] = (it[0x3A].toInt() and 1.inv()).toByte() })
        assertNotNull("error flag", mutated { it[0x3A] = (it[0x3A].toInt() or 2).toByte() })
        assertNotNull("orphans", mutated { put32(it, 0xE8, 12) })
    }
}
