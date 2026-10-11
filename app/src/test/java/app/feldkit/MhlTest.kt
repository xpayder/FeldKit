package app.feldkit

import app.feldkit.hash.HashAlgo
import app.feldkit.hash.MultiHasher
import app.feldkit.offload.DestResult
import app.feldkit.offload.DestStatus
import app.feldkit.offload.FileResult
import app.feldkit.offload.Mhl
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Writes a small tree plus an ASC MHL 2.0 history for it into build/fixtures/mhl-out/ (JCA hashes only: MD5, SHA-1, C4).
 * tools/verify-mhl.sh then runs the reference `ascmhl verify` over it.
 */
class MhlTest {
    @Test fun writeSample() {
        val base = File(System.getProperty("fixtures.dir") ?: "build/fixtures", "mhl-out").also { it.deleteRecursively(); it.mkdirs() }
        for (algo in listOf(HashAlgo.MD5, HashAlgo.SHA1, HashAlgo.C4)) {
            val root = File(base, "job-${algo.name.lowercase()}").also { it.mkdirs() }
            val files = mapOf("a.txt" to "hello", "Clip/b.bin" to "world!", "Clip/sub/c.txt" to "deep", "Привет мир.txt" to "unicode", "empty.bin" to "")
            val results = ArrayList<FileResult>()
            for ((p, c) in files) {
                val f = File(root, p); f.parentFile.mkdirs(); f.writeText(c)
                val mh = MultiHasher(listOf(algo)); mh.update(c.toByteArray())
                results.add(FileResult(p.split('/'), c.toByteArray().size.toLong(), mh.finishHex(), listOf(DestResult("dest", DestStatus.VERIFIED, p.substringAfterLast('/'))), null, f.lastModified()))
            }
            val out = Mhl.build(results, "dest", root.name, algo, Instant.now(), "FeldKit test host", "1.0")
            assertNotNull(out)
            val dir = File(root, "ascmhl").also { it.mkdirs() }
            File(dir, out!!.fileName).writeBytes(out.mhl); File(dir, "ascmhl_chain.xml").writeBytes(out.chain)
        }
    }
}
