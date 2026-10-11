package app.feldkit.offload

import app.feldkit.hash.HashAlgo
import app.feldkit.hash.C4Hasher
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * ASC MHL 2.0 ("Media Hash List"): the hash list film and DIT workflows exchange. One generation is written next to the media:
 * `ascmhl/NNNN_<folder>_<date>Z.mhl` plus `ascmhl/ascmhl_chain.xml`, with per-file hashes and the directory content / structure
 * hashes the specification asks for. Checked with the reference tool (`ascmhl verify`, tools/verify-mhl.sh).
 */
object Mhl {
    /** Algorithms the format knows. */
    val ALGOS = listOf(HashAlgo.XXH64, HashAlgo.XXH3_64, HashAlgo.XXH3_128, HashAlgo.MD5, HashAlgo.SHA1, HashAlgo.C4)

    class Output(val fileName: String, val mhl: ByteArray, val chain: ByteArray)

    private class Node(val name: String, val isDir: Boolean) {
        val children = java.util.TreeMap<String, Node>()
        var size = 0L; var modified = 0L; var hash = ""      // file: its hash string
        var content = ""; var structure = ""                  // directory
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun bytesOf(algo: HashAlgo, s: String): ByteArray = if (algo == HashAlgo.C4) {
        val chars = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var n = java.math.BigInteger.ZERO
        for (i in 2 until s.length) n = n.multiply(java.math.BigInteger.valueOf(58)).add(java.math.BigInteger.valueOf(chars.indexOf(s[i]).toLong()))
        val raw = n.toByteArray().let { if (it.size > 64 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it }
        ByteArray(64 - raw.size) + raw
    } else s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun hashBytes(algo: HashAlgo, data: ByteArray): String {
        val h = algo.create(); h.update(data, 0, data.size); val d = h.digest(); h.release(); return algo.format(d)
    }

    private fun hashOfList(algo: HashAlgo, list: List<String>): String {
        val h = algo.create()
        for (x in list.sorted()) { val b = bytesOf(algo, x); h.update(b, 0, b.size) }
        val d = h.digest(); h.release(); return algo.format(d)
    }

    private fun fill(algo: HashAlgo, n: Node) {
        for (c in n.children.values) if (c.isDir) fill(algo, c)
        val contents = ArrayList<String>(); val structures = ArrayList<String>()
        for (c in n.children.values) {
            val nameBytes = c.name.toByteArray(Charsets.UTF_8)
            if (c.isDir) { contents.add(c.content); structures.add(hashBytes(algo, nameBytes + bytesOf(algo, c.structure))) }
            else { contents.add(c.hash); structures.add(hashBytes(algo, nameBytes + bytesOf(algo, c.hash))) }
        }
        n.content = hashOfList(algo, contents); n.structure = hashOfList(algo, structures)
    }

    /**
     * [results] are the verified files of one destination, with paths relative to the folder the MHL describes ([rootName]).
     * [seq] is the generation number (1 for a new history). Only files whose copy at [destination] was fine are listed.
     */
    fun build(results: List<FileResult>, destination: String, rootName: String, algo: HashAlgo, now: Instant, hostname: String, toolVersion: String, seq: Int = 1): Output? {
        require(algo in ALGOS) { "${algo.name} is not an MHL hash" }
        val tag = algo.mhl!!
        val root = Node("", true)
        var any = false
        for (r in results) {
            val d = r.perDest.firstOrNull { it.destination == destination } ?: continue
            if (d.status != DestStatus.VERIFIED && d.status != DestStatus.COPIED) continue
            val h = r.hashes?.get(algo) ?: continue
            var cur = root
            for (i in 0 until r.relPath.size - 1) cur = cur.children.getOrPut(r.relPath[i]) { Node(r.relPath[i], true) }
            val fileName = d.writtenName
            cur.children[fileName] = Node(fileName, false).also { it.hash = h; it.size = r.size; it.modified = r.modified }
            any = true
        }
        if (!any) return null
        fill(algo, root)

        val zone = ZoneId.systemDefault()
        fun iso(t: Instant, fraction: Boolean = false) = DateTimeFormatter.ofPattern(if (fraction) "yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx" else "yyyy-MM-dd'T'HH:mm:ssxxx").format(t.atZone(zone))
        val created = iso(now)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<hashlist version=\"2.0\" xmlns=\"urn:ASC:MHL:v2.0\">\n")
        sb.append("  <creatorinfo>\n    <creationdate>$created</creationdate>\n    <hostname>${esc(hostname)}</hostname>\n    <tool version=\"${esc(toolVersion)}\">FeldKit</tool>\n  </creatorinfo>\n")
        sb.append("  <processinfo>\n    <process>in-place</process>\n    <roothash>\n      <content>\n        <$tag hashdate=\"${iso(now, true)}\">${root.content}</$tag>\n      </content>\n      <structure>\n        <$tag hashdate=\"${iso(now, true)}\">${root.structure}</$tag>\n      </structure>\n    </roothash>\n")
        sb.append("    <ignore>\n      <pattern>.DS_Store</pattern>\n      <pattern>ascmhl</pattern>\n      <pattern>ascmhl/</pattern>\n    </ignore>\n  </processinfo>\n  <hashes>\n")
        fun emit(n: Node, path: String) {
            val dirs = n.children.values.filter { it.isDir }; val files = n.children.values.filter { !it.isDir }
            for (c in dirs) {
                val p = if (path.isEmpty()) c.name else "$path/${c.name}"
                emit(c, p)
                sb.append("    <directoryhash>\n      <path lastmodificationdate=\"$created\">${esc(p)}</path>\n      <content>\n        <$tag hashdate=\"${iso(now, true)}\">${c.content}</$tag>\n      </content>\n      <structure>\n        <$tag hashdate=\"${iso(now, true)}\">${c.structure}</$tag>\n      </structure>\n    </directoryhash>\n")
            }
            for (c in files) {
                val p = if (path.isEmpty()) c.name else "$path/${c.name}"
                val mod = iso(if (c.modified > 0) Instant.ofEpochMilli(c.modified) else now)
                sb.append("    <hash>\n      <path size=\"${c.size}\" lastmodificationdate=\"$mod\">${esc(p)}</path>\n      <$tag action=\"original\" hashdate=\"${iso(now, true)}\">${c.hash}</$tag>\n    </hash>\n")
            }
        }
        emit(root, "")
        sb.append("  </hashes>\n</hashlist>\n")
        val mhlBytes = sb.toString().toByteArray(Charsets.UTF_8)
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss").format(now.atZone(ZoneOffset.UTC))
        val name = "%04d_%s_%sZ.mhl".format(seq, rootName, stamp)
        val sha = MessageDigest.getInstance("SHA-512").digest(mhlBytes)
        val chain = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ascmhldirectory xmlns=\"urn:ASC:MHL:DIRECTORY:v2.0\">\n  <hashlist sequencenr=\"$seq\">\n    <path>${esc(name)}</path>\n    <c4>${C4Hasher.id(sha)}</c4>\n  </hashlist>\n</ascmhldirectory>\n".toByteArray(Charsets.UTF_8)
        return Output(name, mhlBytes, chain)
    }

    /** Writes the generation into `<jobFolder>/ascmhl/` of every destination. */
    fun write(dests: List<Destination>, jobFolder: String, results: List<FileResult>, algo: HashAlgo, hostname: String, toolVersion: String) {
        val now = Instant.now()
        for (d in dests) {
            val out = build(results, d.label, jobFolder, algo, now, hostname, toolVersion) ?: continue
            try {
                val dir = d.dir(listOf(jobFolder, "ascmhl"))
                dir.create(out.fileName).also { it.write(out.mhl, 0, out.mhl.size); it.finish() }
                dir.create("ascmhl_chain.xml").also { it.write(out.chain, 0, out.chain.size); it.finish() }
            } catch (e: Exception) { android.util.Log.w("FeldKit", "MHL not written", e) }
        }
    }
}
