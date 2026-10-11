package app.feldkit.storage.filesystem.ext

import android.util.Log
import app.feldkit.storage.disk.BlockDeviceReader
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.storage.filesystem.FileSystemWriter
import java.io.InputStream

/**
 * ext2 / ext3 / ext4 writer: create files and folders, delete (also whole folders), rename. Classic block maps and extent trees,
 * 32- and 64-bit group descriptors, flex_bg, uninitialised block groups, metadata_csum (crc32c) and uninit_bg (crc16) checksums.
 *
 * It writes straight to the volume without using the journal, so it only accepts a volume whose journal is clean (no recovery
 * needed), no orphan list and no error flag; volumes with features it cannot keep consistent (quota, bigalloc, meta_bg,
 * inline data, encryption, ...) stay read-only. Hash-tree directories it has to change are turned into plain linear ones
 * (valid for every kernel; `e2fsck -D` rebuilds the index). Order of writing: data, inode, directory entry, then bitmaps and
 * counters, so that an interruption leaves at worst lost free-space counts that `e2fsck` repairs.
 */
class ExtWriter(
    private val dev: BlockDeviceReader,
    private val startLba: Long,
    private val reader: ExtReader,
) : FileSystemWriter {
    companion object {
        private const val TAG = "FeldKit"
        private const val ROOT = 2L

        // feature bits
        private const val C_HAS_JOURNAL = 0x4; private const val C_SPARSE_SUPER2 = 0x200; private const val C_FAST_COMMIT = 0x400
        private const val I_COMPRESSION = 0x1; private const val I_FILETYPE = 0x2; private const val I_RECOVER = 0x4; private const val I_JOURNAL_DEV = 0x8
        private const val I_META_BG = 0x10; private const val I_EXTENTS = 0x40; private const val I_64BIT = 0x80; private const val I_MMP = 0x100
        private const val I_EA_INODE = 0x400; private const val I_DIRDATA = 0x1000; private const val I_CSUM_SEED = 0x2000
        private const val I_INLINE_DATA = 0x8000; private const val I_ENCRYPT = 0x10000; private const val I_CASEFOLD = 0x20000
        private const val R_SPARSE_SUPER = 0x1; private const val R_HUGE_FILE = 0x8; private const val R_GDT_CSUM = 0x10; private const val R_DIR_NLINK = 0x20
        private const val R_SNAPSHOT = 0x80; private const val R_QUOTA = 0x100; private const val R_BIGALLOC = 0x200; private const val R_METADATA_CSUM = 0x400
        private const val R_READONLY = 0x1000; private const val R_PROJECT = 0x2000; private const val R_SHARED_BLOCKS = 0x4000; private const val R_VERITY = 0x8000

        private const val BG_INODE_UNINIT = 0x1; private const val BG_BLOCK_UNINIT = 0x2; private const val BG_ITABLE_ZEROED = 0x4
        private const val FL_INDEX = 0x1000; private const val FL_EXTENTS = 0x80000; private const val FL_HUGE_FILE = 0x40000; private const val FL_INLINE = 0x10000000

        /** Why a volume must stay read-only, or null when the writer can handle it. */
        fun refusal(sb: ByteArray): String? {
            fun u32(o: Int) = (sb[o].toLong() and 0xFF) or ((sb[o + 1].toLong() and 0xFF) shl 8) or ((sb[o + 2].toLong() and 0xFF) shl 16) or ((sb[o + 3].toLong() and 0xFF) shl 24)
            fun u16(o: Int) = (sb[o].toInt() and 0xFF) or ((sb[o + 1].toInt() and 0xFF) shl 8)
            val compat = u32(0x5C).toInt(); val incompat = u32(0x60).toInt(); val ro = u32(0x64).toInt()
            val bad = incompat and (I_COMPRESSION or I_JOURNAL_DEV or I_META_BG or I_MMP or I_EA_INODE or I_DIRDATA or I_INLINE_DATA or I_ENCRYPT or I_CASEFOLD)
            return when {
                incompat and I_RECOVER != 0 -> "the journal needs recovery (mount it once on a Linux system, or run e2fsck)"
                bad != 0 -> "it uses ext features the writer cannot keep consistent (0x%x)".format(bad)
                ro and (R_BIGALLOC or R_QUOTA or R_PROJECT or R_READONLY or R_SHARED_BLOCKS or R_VERITY or R_SNAPSHOT) != 0 -> "it uses ext features the writer cannot keep consistent (ro 0x%x)".format(ro)
                compat and (C_SPARSE_SUPER2 or C_FAST_COMMIT) != 0 -> "it uses sparse_super2 or fast commits"
                u16(0x3A) and 1 == 0 -> "the file system was not cleanly unmounted"
                u16(0x3A) and 2 != 0 -> "the file system is marked as having errors (run e2fsck)"
                u32(0xE8) != 0L -> "orphaned inodes are waiting to be cleaned up (run e2fsck)"
                else -> null
            }
        }
    }

    // ── geometry and features (filled by load) ──
    private var loaded = false
    private var refused: String? = null
    private lateinit var sb: ByteArray
    private var bs = 1024
    private var inodeSize = 128
    private var ipg = 0L; private var bpg = 0L; private var firstData = 0L
    private var blocksCount = 0L; private var inodesCount = 0L
    private var descSize = 32
    private var groupCount = 0
    private var firstIno = 11L
    private var reservedGdt = 0
    private var rBlocks = 0L
    private var hasExtents = false; private var hasFiletype = false; private var is64 = false
    private var metaCsum = false; private var gdtCsum = false; private var dirNlink = false; private var hugeFile = false
    private var csumSeed = 0
    private var uuid = ByteArray(16)
    private var wantExtra = 0
    private var sparseSuper = false
    private lateinit var gdt: ByteArray
    private var freeBlocksTotal = 0L; private var freeInodesTotal = 0L

    // ── caches (flushed by commit) ──
    private val blockBm = HashMap<Int, ByteArray>()
    private val inodeBm = HashMap<Int, ByteArray>()
    private val dirtyBlockBm = HashSet<Int>(); private val dirtyInodeBm = HashSet<Int>(); private val dirtyGroups = HashSet<Int>()

    // ── little-endian helpers ──
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
    private fun p16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() }
    private fun p32(b: ByteArray, o: Int, v: Long) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte() }

    // ── raw volume access ──
    private fun readBytes(off: Long, len: Int): ByteArray? {
        val ss = dev.sectorSize(); val abs = startLba * ss + off
        val first = abs / ss; val last = (abs + len - 1) / ss
        val data = dev.readSectors(first, (last - first + 1).toInt()) ?: return null
        val skip = (abs - first * ss).toInt()
        return if (skip == 0 && data.size == len) data else data.copyOfRange(skip, skip + len)
    }

    private fun writeBytes(off: Long, data: ByteArray, len: Int = data.size): Boolean {
        val ss = dev.sectorSize(); val abs = startLba * ss + off
        val first = abs / ss; val last = (abs + len - 1) / ss
        val skip = (abs - first * ss).toInt()
        if (skip == 0 && len % ss == 0) return dev.writeSectors(first, if (len == data.size) data else data.copyOf(len))
        val buf = dev.readSectors(first, (last - first + 1).toInt()) ?: return false
        System.arraycopy(data, 0, buf, skip, len)
        return dev.writeSectors(first, buf)
    }

    private fun readBlock(n: Long): ByteArray? = readBytes(n * bs, bs)
    private fun writeBlock(n: Long, data: ByteArray): Boolean = writeBytes(n * bs, data, bs)

    // ── load / refuse ──
    @Synchronized private fun ensureLoaded(): Boolean {
        if (loaded) return refused == null
        loaded = true
        val raw = readBytes(1024, 1024) ?: run { refused = "cannot read the superblock"; return false }
        refused = refusal(raw)
        if (refused != null) { Log.w(TAG, "ext: read-only: $refused"); return false }
        return try { loadState(raw); true } catch (e: Exception) { refused = "cannot read the volume layout: ${e.message}"; Log.w(TAG, "ext: $refused"); false }
    }

    private fun loadState(raw: ByteArray) {
        sb = raw
        bs = 1024 shl u32(sb, 0x18).toInt()
        val compat = u32(sb, 0x5C).toInt(); val incompat = u32(sb, 0x60).toInt(); val ro = u32(sb, 0x64).toInt()
        inodesCount = u32(sb, 0); ipg = u32(sb, 0x28); bpg = u32(sb, 0x20); firstData = u32(sb, 0x14)
        is64 = incompat and I_64BIT != 0
        blocksCount = u32(sb, 4) or (if (is64) u32(sb, 0x150) shl 32 else 0L)
        rBlocks = u32(sb, 8) or (if (is64) u32(sb, 0x154) shl 32 else 0L)
        freeBlocksTotal = u32(sb, 0x0C) or (if (is64) u32(sb, 0x158) shl 32 else 0L)
        freeInodesTotal = u32(sb, 0x10)
        inodeSize = if (u32(sb, 0x4C) >= 1) u16(sb, 0x58) else 128
        firstIno = if (u32(sb, 0x4C) >= 1) u32(sb, 0x54) else 11L
        descSize = if (is64) u16(sb, 0xFE).coerceAtLeast(64) else 32
        reservedGdt = u16(sb, 0xCE)
        hasExtents = incompat and I_EXTENTS != 0; hasFiletype = incompat and I_FILETYPE != 0
        metaCsum = ro and R_METADATA_CSUM != 0; gdtCsum = ro and R_GDT_CSUM != 0 && !metaCsum
        dirNlink = ro and R_DIR_NLINK != 0; hugeFile = ro and R_HUGE_FILE != 0; sparseSuper = ro and R_SPARSE_SUPER != 0
        uuid = sb.copyOfRange(0x68, 0x78)
        csumSeed = if (incompat and I_CSUM_SEED != 0) u32(sb, 0x270).toInt() else ExtCrc.crc32c(-1, uuid)
        wantExtra = u16(sb, 0x15E)
        require(bs in 1024..65536 && ipg > 0 && bpg > 0 && inodeSize in 128..bs) { "bad geometry" }
        groupCount = ((blocksCount - firstData + bpg - 1) / bpg).toInt()
        val gdtBlocks = (groupCount.toLong() * descSize + bs - 1) / bs
        val g = ByteArray((gdtBlocks * bs).toInt())
        for (i in 0 until gdtBlocks.toInt()) readBlock(firstData + 1 + i)?.copyInto(g, i * bs) ?: error("cannot read the group descriptors")
        gdt = g
        blockBm.clear(); inodeBm.clear(); dirtyBlockBm.clear(); dirtyInodeBm.clear(); dirtyGroups.clear()
    }

    /** Forgets everything not yet committed (after a failed operation) and reads the layout again. */
    private fun reloadState() { readBytes(1024, 1024)?.let { try { loadState(it) } catch (_: Exception) {} } }

    // ── group descriptors ──
    private fun gdOff(g: Int) = g * descSize
    private fun gd16(g: Int, o: Int) = u16(gdt, gdOff(g) + o)
    private fun gd32(g: Int, o: Int) = u32(gdt, gdOff(g) + o)
    private fun hasHi() = descSize >= 64
    private fun blockBitmapLoc(g: Int) = gd32(g, 0) or (if (hasHi()) gd32(g, 0x20) shl 32 else 0L)
    private fun inodeBitmapLoc(g: Int) = gd32(g, 4) or (if (hasHi()) gd32(g, 0x24) shl 32 else 0L)
    private fun inodeTableLoc(g: Int) = gd32(g, 8) or (if (hasHi()) gd32(g, 0x28) shl 32 else 0L)
    private fun gFreeBlocks(g: Int) = gd16(g, 0x0C) + (if (hasHi()) gd16(g, 0x2C) shl 16 else 0)
    private fun gFreeInodes(g: Int) = gd16(g, 0x0E) + (if (hasHi()) gd16(g, 0x2E) shl 16 else 0)
    private fun gUsedDirs(g: Int) = gd16(g, 0x10) + (if (hasHi()) gd16(g, 0x30) shl 16 else 0)
    private fun gFlags(g: Int) = gd16(g, 0x12)
    private fun gItableUnused(g: Int) = gd16(g, 0x1C) + (if (hasHi()) gd16(g, 0x32) shl 16 else 0)
    private fun setGd(g: Int, lo: Int, hiOff: Int, v: Int) { p16(gdt, gdOff(g) + lo, v and 0xFFFF); if (hasHi()) p16(gdt, gdOff(g) + hiOff, v ushr 16) }
    private fun setFreeBlocks(g: Int, v: Int) { setGd(g, 0x0C, 0x2C, v); dirtyGroups.add(g) }
    private fun setFreeInodes(g: Int, v: Int) { setGd(g, 0x0E, 0x2E, v); dirtyGroups.add(g) }
    private fun setUsedDirs(g: Int, v: Int) { setGd(g, 0x10, 0x30, v); dirtyGroups.add(g) }
    private fun setItableUnused(g: Int, v: Int) { setGd(g, 0x1C, 0x32, v); dirtyGroups.add(g) }
    private fun setFlags(g: Int, v: Int) { p16(gdt, gdOff(g) + 0x12, v); dirtyGroups.add(g) }

    private fun groupFirstBlock(g: Int) = firstData + g * bpg
    private fun blocksInGroup(g: Int) = minOf(bpg, blocksCount - groupFirstBlock(g)).toInt()
    private fun groupOfBlock(b: Long) = ((b - firstData) / bpg).toInt()

    // ── bitmaps ──
    private fun hasSuper(g: Int): Boolean {
        if (g == 0) return true
        if (!sparseSuper) return true
        if (g == 1) return true
        for (base in intArrayOf(3, 5, 7)) { var p = base.toLong(); while (p < g) p *= base; if (p == g.toLong()) return true }
        return false
    }

    private fun padBlockBitmap(g: Int, bm: ByteArray) {
        val n = blocksInGroup(g)
        for (i in n until (bs * 8)) bm[i shr 3] = (bm[i shr 3].toInt() or (1 shl (i and 7))).toByte()
    }

    /** The block bitmap of group [g]; a never-initialised group (BLOCK_UNINIT) gets the bitmap mke2fs would have written. */
    private fun blockBitmap(g: Int): ByteArray = blockBm.getOrPut(g) {
        if (gFlags(g) and BG_BLOCK_UNINIT != 0) {
            val bm = ByteArray(bs)
            val first = groupFirstBlock(g); val last = first + blocksInGroup(g)
            fun mark(from: Long, to: Long) { for (b in maxOf(from, first) until minOf(to, last)) { val i = (b - first).toInt(); bm[i shr 3] = (bm[i shr 3].toInt() or (1 shl (i and 7))).toByte() } }
            if (hasSuper(g)) mark(first, first + 1 + (groupCount.toLong() * descSize + bs - 1) / bs + reservedGdt)
            for (h in 0 until groupCount) {
                mark(blockBitmapLoc(h), blockBitmapLoc(h) + 1); mark(inodeBitmapLoc(h), inodeBitmapLoc(h) + 1)
                mark(inodeTableLoc(h), inodeTableLoc(h) + (ipg * inodeSize + bs - 1) / bs)
            }
            padBlockBitmap(g, bm); bm
        } else readBlock(blockBitmapLoc(g)) ?: error("cannot read a block bitmap")
    }

    private fun inodeBitmap(g: Int): ByteArray = inodeBm.getOrPut(g) {
        if (gFlags(g) and BG_INODE_UNINIT != 0) ByteArray(bs).also { for (i in (ipg / 8).toInt() until bs) it[i] = 0xFF.toByte() }
        else readBlock(inodeBitmapLoc(g)) ?: error("cannot read an inode bitmap")
    }

    private fun bit(bm: ByteArray, i: Int) = bm[i shr 3].toInt() and (1 shl (i and 7)) != 0
    private fun setBit(bm: ByteArray, i: Int, v: Boolean) { bm[i shr 3] = (if (v) bm[i shr 3].toInt() or (1 shl (i and 7)) else bm[i shr 3].toInt() and (1 shl (i and 7)).inv()).toByte() }

    // ── allocation ──
    private class Run(val logical: Long, val phys: Long, val len: Long)

    private fun clearBlockUninit(g: Int) { if (gFlags(g) and BG_BLOCK_UNINIT != 0) setFlags(g, gFlags(g) and BG_BLOCK_UNINIT.inv()) }

    /** Allocates [count] blocks, preferably contiguous and near [goalBlock]; null (nothing changed) when the volume is too full. */
    private fun allocBlocks(count: Long, goalBlock: Long): List<Run>? {
        if (count <= 0) return emptyList()
        if (freeBlocksTotal - rBlocks < count) return null
        val out = ArrayList<Run>(); var remaining = count
        var g = groupOfBlock(goalBlock).coerceIn(0, groupCount - 1)
        for (step in 0 until groupCount) {
            if (remaining <= 0) break
            if (gFreeBlocks(g) > 0) {
                val bm = blockBitmap(g); val n = blocksInGroup(g)
                // find the runs of free blocks in this group
                var i = 0
                val runs = ArrayList<Pair<Int, Int>>()
                while (i < n) {
                    if (i and 7 == 0 && bm[i shr 3] == 0xFF.toByte()) { i += 8; continue }
                    if (bit(bm, i)) { i++; continue }
                    var j = i; while (j < n && !bit(bm, j)) j++
                    runs.add(i to (j - i)); i = j
                }
                val pick = runs.firstOrNull { it.second >= remaining } ?: runs.maxByOrNull { it.second }
                if (pick != null) {
                    var start = pick.first; var take = minOf(pick.second.toLong(), remaining).toInt()
                    // runs longer than one extent are fine here: they are split when the mapping is built
                    for (k in start until start + take) setBit(bm, k, true)
                    clearBlockUninit(g); dirtyBlockBm.add(g)
                    setFreeBlocks(g, gFreeBlocks(g) - take)
                    out.add(Run(0, groupFirstBlock(g) + start, take.toLong()))
                    remaining -= take; freeBlocksTotal -= take
                    if (remaining > 0) continue                  // more from the same group's other runs: loop again without advancing
                }
            }
            g = (g + 1) % groupCount
        }
        if (remaining > 0) { reloadState(); return null }
        return out
    }

    private fun freeRun(start: Long, len: Long) {
        var b = start; var left = len
        while (left > 0) {
            val g = groupOfBlock(b); val inG = (b - groupFirstBlock(g)).toInt()
            val n = minOf(left, (blocksInGroup(g) - inG).toLong()).toInt()
            val bm = blockBitmap(g)
            for (k in 0 until n) setBit(bm, inG + k, false)
            dirtyBlockBm.add(g); setFreeBlocks(g, gFreeBlocks(g) + n)
            freeBlocksTotal += n; b += n; left -= n
        }
    }

    private fun allocInode(parentGroup: Int, dir: Boolean): Long? {
        if (freeInodesTotal <= 0) return null
        var g = parentGroup.coerceIn(0, groupCount - 1)
        for (step in 0 until groupCount) {
            if (gFreeInodes(g) > 0) {
                val bm = inodeBitmap(g)
                for (i in 0 until ipg.toInt()) {
                    if (!bit(bm, i)) {
                        setBit(bm, i, true); dirtyInodeBm.add(g)
                        setFreeInodes(g, gFreeInodes(g) - 1)
                        if (dir) setUsedDirs(g, gUsedDirs(g) + 1)
                        setItableUnused(g, minOf(gItableUnused(g).toLong(), ipg - i - 1).toInt())
                        setFlags(g, gFlags(g) and BG_INODE_UNINIT.inv())
                        freeInodesTotal--
                        return g * ipg + i + 1
                    }
                }
            }
            g = (g + 1) % groupCount
        }
        return null
    }

    private fun freeInode(ino: Long, dir: Boolean) {
        val g = ((ino - 1) / ipg).toInt(); val i = ((ino - 1) % ipg).toInt()
        setBit(inodeBitmap(g), i, false); dirtyInodeBm.add(g)
        setFreeInodes(g, gFreeInodes(g) + 1)
        if (dir && gUsedDirs(g) > 0) setUsedDirs(g, gUsedDirs(g) - 1)
        freeInodesTotal++
    }

    // ── inodes ──
    private fun inodeLoc(ino: Long): Long {
        val g = ((ino - 1) / ipg).toInt(); val idx = (ino - 1) % ipg
        return inodeTableLoc(g) * bs + idx * inodeSize
    }

    private fun readInode(ino: Long): ByteArray? = readBytes(inodeLoc(ino), inodeSize)

    private fun inodeCsum(ino: Long, raw: ByteArray): Int {
        var seed = ExtCrc.crc32cLe32(csumSeed, ino); seed = ExtCrc.crc32cLe32(seed, u32(raw, 0x64))
        val zero = ByteArray(2)
        var c = ExtCrc.crc32c(seed, raw, 0, 0x7C)
        c = ExtCrc.crc32c(c, zero); c = ExtCrc.crc32c(c, raw, 0x7E, 128 - 0x7E)
        if (inodeSize > 128) {
            c = ExtCrc.crc32c(c, raw, 128, 0x82 - 128)
            var off = 0x82
            if (u16(raw, 0x80) >= 4) { c = ExtCrc.crc32c(c, zero); off += 2 }
            c = ExtCrc.crc32c(c, raw, off, inodeSize - off)
        }
        return c
    }

    private fun writeInode(ino: Long, raw: ByteArray): Boolean {
        if (metaCsum) {
            val c = inodeCsum(ino, raw)
            p16(raw, 0x7C, c and 0xFFFF)
            if (inodeSize > 128 && u16(raw, 0x80) >= 4) p16(raw, 0x82, c ushr 16)
        }
        return writeBytes(inodeLoc(ino), raw, inodeSize)
    }

    private fun now() = System.currentTimeMillis() / 1000

    private fun newInodeRaw(mode: Int, uid: Int, gid: Int, links: Int, size: Long, flags: Int): ByteArray {
        val raw = ByteArray(inodeSize); val t = now()
        p16(raw, 0, mode); p16(raw, 2, uid and 0xFFFF); p32(raw, 4, size and 0xFFFFFFFFL); p32(raw, 8, t); p32(raw, 0xC, t); p32(raw, 0x10, t)
        p16(raw, 0x18, gid and 0xFFFF); p16(raw, 0x1A, links); p32(raw, 0x20, flags.toLong())
        p32(raw, 0x64, (t xor (System.nanoTime() ushr 7)) and 0xFFFFFFFFL)                       // generation
        p32(raw, 0x6C, size ushr 32); p16(raw, 0x78, uid ushr 16); p16(raw, 0x7A, gid ushr 16)
        if (inodeSize > 128) {
            val extra = (if (wantExtra in 4..(inodeSize - 128)) wantExtra else 32).coerceAtMost(inodeSize - 128)
            p16(raw, 0x80, extra); if (extra >= 0x94 - 128 + 4) { p32(raw, 0x90, t) }
        }
        return raw
    }

    private fun setBlocks(raw: ByteArray, blocks: Long) {
        val sectors = blocks * (bs / 512)
        p32(raw, 0x1C, sectors and 0xFFFFFFFFL); p16(raw, 0x74, (sectors ushr 32).toInt())
    }

    private fun inodeSizeOf(raw: ByteArray) = u32(raw, 4) or (u32(raw, 0x6C) shl 32)
    private fun inodeBlocks(raw: ByteArray): Long {
        val sectors = u32(raw, 0x1C) or (u16(raw, 0x74).toLong() shl 32)
        val unit = if (hugeFile && u32(raw, 0x20).toInt() and FL_HUGE_FILE != 0) bs.toLong() else 512L
        return sectors * unit / bs
    }

    private fun touch(raw: ByteArray) { val t = now(); p32(raw, 0xC, t); p32(raw, 0x10, t) }

    // ── block mapping: read ──
    private class Mapping(val runs: List<Run>, val meta: List<Long>)

    private fun extentLeafCap() = (bs - 12) / 12

    private fun readExtents(node: ByteArray, off: Int, runs: MutableList<Run>, meta: MutableList<Long>, depth: Int) {
        require(depth <= 5 && u16(node, off) == 0xF30A) { "bad extent node" }
        val entries = u16(node, off + 2); val level = u16(node, off + 6)
        for (i in 0 until entries) {
            val e = off + 12 + i * 12
            if (level == 0) {
                val len = u16(node, e + 4); val n = if (len > 32768) len - 32768 else len     // uninitialised extents count as allocated
                runs.add(Run(u32(node, e), (u16(node, e + 6).toLong() shl 32) or u32(node, e + 8), n.toLong()))
            } else {
                val child = u32(node, e + 4) or (u16(node, e + 8).toLong() shl 32)
                meta.add(child); readExtents(readBlock(child) ?: error("cannot read an extent block"), 0, runs, meta, depth + 1)
            }
        }
    }

    private fun readBlockMap(raw: ByteArray): Mapping {
        val ptrs = bs / 4; val meta = ArrayList<Long>(); val pairs = ArrayList<Pair<Long, Long>>()
        fun walk(block: Long, level: Int, base: Long) {
            if (block == 0L) return
            meta.add(block)
            val b = readBlock(block) ?: error("cannot read an indirect block")
            var span = 1L; repeat(level - 1) { span *= ptrs }
            for (i in 0 until ptrs) {
                val p = u32(b, i * 4); if (p == 0L) continue
                if (level == 1) pairs.add(base + i to p) else walk(p, level - 1, base + i * span)
            }
        }
        for (i in 0 until 12) { val p = u32(raw, 0x28 + i * 4); if (p != 0L) pairs.add(i.toLong() to p) }
        val ptrs2 = ptrs.toLong() * ptrs
        walk(u32(raw, 0x28 + 48), 1, 12L)
        walk(u32(raw, 0x28 + 52), 2, 12L + ptrs)
        walk(u32(raw, 0x28 + 56), 3, 12L + ptrs + ptrs2)
        pairs.sortBy { it.first }
        val runs = ArrayList<Run>()
        for ((l, p) in pairs) {
            val last = runs.lastOrNull()
            if (last != null && last.logical + last.len == l && last.phys + last.len == p) runs[runs.size - 1] = Run(last.logical, last.phys, last.len + 1) else runs.add(Run(l, p, 1))
        }
        return Mapping(runs, meta)
    }

    private fun readMapping(raw: ByteArray): Mapping {
        val flags = u32(raw, 0x20).toInt()
        if (flags and FL_INLINE != 0) error("inline data")
        if (flags and FL_EXTENTS != 0) {
            val runs = ArrayList<Run>(); val meta = ArrayList<Long>()
            readExtents(raw, 0x28, runs, meta, 0); runs.sortBy { it.logical }
            return Mapping(runs, meta)
        }
        return readBlockMap(raw)
    }

    private fun freeMapping(m: Mapping) { for (r in m.runs) freeRun(r.phys, r.len); for (b in m.meta) freeRun(b, 1) }

    // ── block mapping: build ──
    private fun splitRuns(runs: List<Run>): List<Run> {
        val out = ArrayList<Run>()
        for (r in runs) { var done = 0L; while (done < r.len) { val n = minOf(32768L, r.len - done); out.add(Run(r.logical + done, r.phys + done, n)); done += n } }
        return out
    }

    /** Merges runs that follow each other both logically and physically (after allocation and extension). */
    private fun mergeRuns(runs: List<Run>): List<Run> {
        val out = ArrayList<Run>()
        for (r in runs.sortedBy { it.logical }) {
            val l = out.lastOrNull()
            if (l != null && l.logical + l.len == r.logical && l.phys + l.len == r.phys) out[out.size - 1] = Run(l.logical, l.phys, l.len + r.len) else out.add(r)
        }
        return out
    }

    /** Number of extra blocks (extent leaves / indirect blocks) the mapping of these data runs needs. */
    private fun metaNeeded(runs: List<Run>): Int {
        if (hasExtents) { val n = splitRuns(runs).size; if (n <= 4) return 0; val leaves = (n + extentLeafCap() - 1) / extentLeafCap(); require(leaves <= 4) { "file too fragmented" }; return leaves }
        val total = runs.sumOf { it.len }.let { if (runs.isEmpty()) 0L else runs.maxOf { r -> r.logical + r.len } }
        var rest = total - 12; if (rest <= 0) return 0
        val p = (bs / 4).toLong(); var c = 0L
        var take = minOf(rest, p); c += 1; rest -= take
        if (rest > 0) { take = minOf(rest, p * p); c += 1 + (take + p - 1) / p; rest -= take }
        if (rest > 0) { take = minOf(rest, p * p * p); c += 1 + (take + p * p - 1) / (p * p) + (take + p - 1) / p; rest -= take }
        require(rest <= 0) { "file too large for a block map" }
        return c.toInt()
    }

    /** Fills i_block of [raw] with the mapping and returns the extra blocks to write. [meta] must hold [metaNeeded] block numbers. */
    private fun applyMapping(ino: Long, raw: ByteArray, runs: List<Run>, meta: ArrayDeque<Long>): List<Pair<Long, ByteArray>> {
        java.util.Arrays.fill(raw, 0x28, 0x28 + 60, 0)
        val writes = ArrayList<Pair<Long, ByteArray>>()
        if (hasExtents) {
            val sr = splitRuns(runs)
            fun header(b: ByteArray, off: Int, entries: Int, max: Int, depth: Int) { p16(b, off, 0xF30A); p16(b, off + 2, entries); p16(b, off + 4, max); p16(b, off + 6, depth) }
            fun extent(b: ByteArray, off: Int, r: Run) { p32(b, off, r.logical); p16(b, off + 4, r.len.toInt()); p16(b, off + 6, (r.phys ushr 32).toInt()); p32(b, off + 8, r.phys and 0xFFFFFFFFL) }
            if (sr.size <= 4) {
                header(raw, 0x28, sr.size, 4, 0)
                sr.forEachIndexed { i, r -> extent(raw, 0x28 + 12 + i * 12, r) }
            } else {
                val cap = extentLeafCap(); val chunks = sr.chunked(cap)
                header(raw, 0x28, chunks.size, 4, 1)
                chunks.forEachIndexed { k, ch ->
                    val blk = meta.removeFirst(); val buf = ByteArray(bs)
                    header(buf, 0, ch.size, cap, 0); ch.forEachIndexed { i, r -> extent(buf, 12 + i * 12, r) }
                    if (metaCsum) {
                        var seed = ExtCrc.crc32cLe32(csumSeed, ino); seed = ExtCrc.crc32cLe32(seed, u32(raw, 0x64))
                        p32(buf, 12 + cap * 12, ExtCrc.crc32c(seed, buf, 0, 12 + cap * 12).toLong() and 0xFFFFFFFFL)
                    }
                    writes.add(blk to buf)
                    val e = 0x28 + 12 + k * 12
                    p32(raw, e, ch[0].logical); p32(raw, e + 4, blk and 0xFFFFFFFFL); p16(raw, e + 8, (blk ushr 32).toInt())
                }
            }
            return writes
        }
        // classic block map
        val total = if (runs.isEmpty()) 0 else runs.maxOf { it.logical + it.len }.toInt()
        val blocks = LongArray(total); for (r in runs) for (k in 0 until r.len.toInt()) blocks[(r.logical + k).toInt()] = r.phys + k
        for (i in 0 until minOf(12, total)) p32(raw, 0x28 + i * 4, blocks[i])
        val p = bs / 4
        fun make(level: Int, first: Int, count: Int): Long {
            val blk = meta.removeFirst(); val buf = ByteArray(bs)
            var span = 1; repeat(level - 1) { span *= p }
            var k = 0; var i = first
            while (i < first + count) {
                val n = minOf(span, first + count - i)
                if (level == 1) p32(buf, k * 4, blocks[i]) else p32(buf, k * 4, make(level - 1, i, n))
                k++; i += if (level == 1) 1 else n
            }
            writes.add(blk to buf); return blk
        }
        var idx = 12; var rest = total - 12
        if (rest > 0) { val n = minOf(rest, p); p32(raw, 0x28 + 48, make(1, idx, n)); idx += n; rest -= n }
        if (rest > 0) { val n = minOf(rest.toLong(), p.toLong() * p).toInt(); p32(raw, 0x28 + 52, make(2, idx, n)); idx += n; rest -= n }
        if (rest > 0) { p32(raw, 0x28 + 56, make(3, idx, rest)) }
        return writes
    }

    // ── directories ──
    private class Ent(val offset: Int, val inode: Long, val recLen: Int, val nameLen: Int, val type: Int, val name: String)

    private fun dirUsable() = if (metaCsum) bs - 12 else bs

    private fun parseEnts(b: ByteArray): List<Ent> {
        val out = ArrayList<Ent>(); var p = 0; val usable = dirUsable()
        while (p + 8 <= usable) {
            val rec = u16(b, p + 4); val nl = b[p + 6].toInt() and 0xFF
            if (rec < 8 || rec % 4 != 0 || p + rec > usable || 8 + nl > rec) break
            out.add(Ent(p, u32(b, p), rec, nl, b[p + 7].toInt() and 0xFF, String(b, p + 8, nl, Charsets.UTF_8))); p += rec
        }
        return out
    }

    private fun entLen(nameLen: Int) = (8 + nameLen + 3) and 3.inv()

    private fun typeOf(mode: Int) = when (mode and 0xF000) { 0x8000 -> 1; 0x4000 -> 2; 0x2000 -> 3; 0x6000 -> 4; 0x1000 -> 5; 0xC000 -> 6; 0xA000 -> 7; else -> 0 }

    private fun finishDirBlock(ino: Long, gen: Long, b: ByteArray) {
        if (!metaCsum) return
        val t = bs - 12
        p32(b, t, 0); p16(b, t + 4, 12); b[t + 6] = 0; b[t + 7] = 0xDE.toByte()
        var seed = ExtCrc.crc32cLe32(csumSeed, ino); seed = ExtCrc.crc32cLe32(seed, gen)
        p32(b, t + 8, ExtCrc.crc32c(seed, b, 0, t).toLong() and 0xFFFFFFFFL)
    }

    private class DirBlocks(val raw: ByteArray, val mapping: Mapping, val phys: List<Long>)

    private fun loadDir(ino: Long): DirBlocks? {
        val raw = readInode(ino) ?: return null
        if (u16(raw, 0) and 0xF000 != 0x4000) return null
        val m = readMapping(raw)
        val n = ((inodeSizeOf(raw) + bs - 1) / bs).toInt()
        val phys = LongArray(n); for (r in m.runs) for (k in 0 until r.len.toInt()) if (r.logical + k < n) phys[(r.logical + k).toInt()] = r.phys + k
        return DirBlocks(raw, m, phys.toList())
    }

    private fun lookup(dirIno: Long, name: String): Pair<Long, Int>? {
        val d = loadDir(dirIno) ?: return null
        for (pb in d.phys) { if (pb == 0L) continue; val b = readBlock(pb) ?: return null; parseEnts(b).firstOrNull { it.inode != 0L && it.name == name }?.let { return it.inode to it.type } }
        return null
    }

    private fun resolve(path: String): Long? {
        var cur = ROOT
        for (part in path.trim('/').split('/').filter { it.isNotEmpty() }) cur = lookup(cur, part)?.first ?: return null
        return cur
    }

    private fun dirInodeOf(e: FileSystemEntry): Long? = if (e.inodeOid > 0 && (e.path == "/" || e.isDirectory)) e.inodeOid else resolve(e.path)

    /** Turns a hash-tree directory into a plain linear one: index blocks become empty directory blocks. */
    private fun linearize(ino: Long, d: DirBlocks) {
        val raw = d.raw; val gen = u32(raw, 0x64); val usable = dirUsable()
        for ((i, pb) in d.phys.withIndex()) {
            if (pb == 0L) continue
            val b = readBlock(pb) ?: error("cannot read a directory block")
            val first = u16(b, 4)
            if (i == 0) {
                // dx_root: "." then ".." whose record swallows the index
                val dotRec = u16(b, 4); val dd = dotRec
                val rec = bs - dd - (if (metaCsum) 12 else 0)
                java.util.Arrays.fill(b, dd + 12, bs, 0)
                p16(b, dd + 4, rec)
            } else if (u32(b, 0) == 0L && first == bs) {
                java.util.Arrays.fill(b, 0, bs, 0); p16(b, 4, usable)               // dx_node: one empty entry
            } else continue
            finishDirBlock(ino, gen, b); if (!writeBlock(pb, b)) error("cannot write a directory block")
        }
        p32(raw, 0x20, (u32(raw, 0x20).toInt() and FL_INDEX.inv()).toLong())
        if (!writeInode(ino, raw)) error("cannot write the directory inode")
    }

    private fun addEntry(dirIno: Long, name: String, child: Long, type: Int): Boolean {
        var d = loadDir(dirIno) ?: return false
        if (u32(d.raw, 0x20).toInt() and FL_INDEX != 0) { linearize(dirIno, d); d = loadDir(dirIno) ?: return false }
        val nb = name.toByteArray(Charsets.UTF_8); val need = entLen(nb.size); val gen = u32(d.raw, 0x64)
        fun put(b: ByteArray, off: Int, rec: Int) {
            p32(b, off, child); p16(b, off + 4, rec); b[off + 6] = nb.size.toByte(); b[off + 7] = (if (hasFiletype) type else 0).toByte()
            System.arraycopy(nb, 0, b, off + 8, nb.size)
        }
        for (pb in d.phys) {
            if (pb == 0L) continue
            val b = readBlock(pb) ?: return false
            for (e in parseEnts(b)) {
                val used = if (e.inode == 0L) 0 else entLen(e.nameLen)
                if (e.recLen - used >= need) {
                    if (e.inode == 0L) put(b, e.offset, e.recLen)
                    else { p16(b, e.offset + 4, used); put(b, e.offset + used, e.recLen - used) }
                    finishDirBlock(dirIno, gen, b); return writeBlock(pb, b)
                }
            }
        }
        // no room: the directory grows by one block
        val goal = d.phys.lastOrNull { it != 0L } ?: (groupFirstBlock(((dirIno - 1) / ipg).toInt()))
        val got = allocBlocks(1, goal) ?: return false
        val newBlk = got[0].phys
        val b = ByteArray(bs); put(b, 0, dirUsable()); finishDirBlock(dirIno, gen, b)
        if (!writeBlock(newBlk, b)) return false
        val runs = mergeRuns(d.mapping.runs + Run(d.phys.size.toLong(), newBlk, 1))
        val raw = d.raw
        // rebuild the mapping (the old extra blocks are released, new ones allocated)
        for (m in d.mapping.meta) freeRun(m, 1)
        val metaBlocks = allocMeta(runs, goal) ?: return false
        val writes = applyMapping(dirIno, raw, runs, ArrayDeque(metaBlocks))
        for ((bn, data) in writes) if (!writeBlock(bn, data)) return false
        p32(raw, 4, (inodeSizeOf(raw) + bs) and 0xFFFFFFFFL); p32(raw, 0x6C, (inodeSizeOf(raw) + bs) ushr 32)
        setBlocks(raw, runs.sumOf { it.len } + metaBlocks.size + xattrBlocks(raw))
        touch(raw)
        return writeInode(dirIno, raw)
    }

    private fun xattrBlocks(raw: ByteArray) = if (u32(raw, 0x68) != 0L) 1L else 0L

    private fun allocMeta(runs: List<Run>, goal: Long): List<Long>? {
        val n = metaNeeded(runs); if (n == 0) return emptyList()
        val got = allocBlocks(n.toLong(), goal) ?: return null
        return got.flatMap { r -> (0 until r.len).map { r.phys + it } }
    }

    private fun removeEntry(dirIno: Long, name: String): Boolean {
        var d = loadDir(dirIno) ?: return false
        if (u32(d.raw, 0x20).toInt() and FL_INDEX != 0) { linearize(dirIno, d); d = loadDir(dirIno) ?: return false }
        val gen = u32(d.raw, 0x64)
        for (pb in d.phys) {
            if (pb == 0L) continue
            val b = readBlock(pb) ?: return false
            val ents = parseEnts(b)
            val k = ents.indexOfFirst { it.inode != 0L && it.name == name }
            if (k < 0) continue
            val e = ents[k]
            if (k > 0) p16(b, ents[k - 1].offset + 4, ents[k - 1].recLen + e.recLen) else { p32(b, e.offset, 0); b[e.offset + 6] = 0; b[e.offset + 7] = 0 }
            finishDirBlock(dirIno, gen, b); return writeBlock(pb, b)
        }
        return false
    }

    // ── commit ──
    private fun gdtChecksum(g: Int): Int {
        val o = gdOff(g)
        if (metaCsum) {
            var c = ExtCrc.crc32cLe32(csumSeed, g.toLong())
            c = ExtCrc.crc32c(c, gdt, o, 0x1E); c = ExtCrc.crc32c(c, ByteArray(2))
            if (0x20 < descSize) c = ExtCrc.crc32c(c, gdt, o + 0x20, descSize - 0x20)
            return c and 0xFFFF
        }
        var c = ExtCrc.crc16(0xFFFF, uuid); c = ExtCrc.crc16(c, byteArrayOf(g.toByte(), (g ushr 8).toByte(), (g ushr 16).toByte(), (g ushr 24).toByte()))
        c = ExtCrc.crc16(c, gdt, o, 0x1E)
        if (is64 && 0x20 < descSize) c = ExtCrc.crc16(c, gdt, o + 0x20, descSize - 0x20)
        return c
    }

    private fun commit(): Boolean {
        var ok = true
        for (g in dirtyBlockBm.toList()) {
            val bm = blockBm[g] ?: continue
            if (metaCsum) { val c = ExtCrc.crc32c(csumSeed, bm, 0, (bpg / 8).toInt()); p16(gdt, gdOff(g) + 0x18, c and 0xFFFF); if (descSize >= 0x3A) p16(gdt, gdOff(g) + 0x38, c ushr 16) }
            ok = ok && writeBlock(blockBitmapLoc(g), bm); dirtyGroups.add(g)
        }
        for (g in dirtyInodeBm.toList()) {
            val bm = inodeBm[g] ?: continue
            if (metaCsum) { val c = ExtCrc.crc32c(csumSeed, bm, 0, (ipg / 8).toInt()); p16(gdt, gdOff(g) + 0x1A, c and 0xFFFF); if (descSize >= 0x3C) p16(gdt, gdOff(g) + 0x3A, c ushr 16) }
            ok = ok && writeBlock(inodeBitmapLoc(g), bm); dirtyGroups.add(g)
        }
        val gdtBlocksDirty = HashSet<Int>()
        for (g in dirtyGroups) {
            if (metaCsum || gdtCsum) p16(gdt, gdOff(g) + 0x1E, gdtChecksum(g))
            gdtBlocksDirty.add(gdOff(g) / bs)
        }
        for (b in gdtBlocksDirty) ok = ok && writeBlock(firstData + 1 + b, gdt.copyOfRange(b * bs, b * bs + bs))
        // superblock: free counts, write time, checksum
        p32(sb, 0x0C, freeBlocksTotal and 0xFFFFFFFFL); if (is64) p32(sb, 0x158, freeBlocksTotal ushr 32); p32(sb, 0x10, freeInodesTotal); p32(sb, 0x30, now())
        if (metaCsum) p32(sb, 0x3FC, ExtCrc.crc32c(-1, sb, 0, 0x3FC).toLong() and 0xFFFFFFFFL)
        ok = ok && writeBytes(1024, sb)
        dirtyBlockBm.clear(); dirtyInodeBm.clear(); dirtyGroups.clear()
        dev.flushCache()
        reader.invalidate()
        return ok
    }

    // ── public operations ──
    private inline fun guarded(what: String, block: () -> Boolean): Boolean = synchronized(this) {
        if (!ensureLoaded()) return false
        try {
            val ok = block()
            if (!ok) { reloadState(); reader.invalidate() }
            ok
        } catch (e: Throwable) { Log.w(TAG, "ext: $what failed", e); reloadState(); reader.invalidate(); false }
    }

    private fun validName(n: String) = n.isNotEmpty() && n != "." && n != ".." && !n.contains('/') && !n.contains('\u0000') && n.toByteArray(Charsets.UTF_8).size <= 255

    override fun createDirectory(parentEntry: FileSystemEntry, name: String): Boolean = guarded("mkdir $name") {
        if (!validName(name)) return@guarded false
        val parent = dirInodeOf(parentEntry) ?: return@guarded false
        if (lookup(parent, name) != null) return@guarded false
        val praw = readInode(parent) ?: return@guarded false
        val pgroup = ((parent - 1) / ipg).toInt()
        val ino = allocInode(pgroup, true) ?: return@guarded false
        val got = allocBlocks(1, groupFirstBlock(pgroup)) ?: return@guarded false
        val blk = got[0].phys
        val raw = newInodeRaw(0x4000 or 0x1ED, u16(praw, 2) or (u16(praw, 0x78) shl 16), u16(praw, 0x18) or (u16(praw, 0x7A) shl 16), 2, bs.toLong(), if (hasExtents) FL_EXTENTS else 0)
        val runs = listOf(Run(0, blk, 1))
        applyMapping(ino, raw, runs, ArrayDeque())
        setBlocks(raw, 1)
        // "." and ".."
        val b = ByteArray(bs)
        p32(b, 0, ino); p16(b, 4, 12); b[6] = 1; b[7] = (if (hasFiletype) 2 else 0).toByte(); b[8] = '.'.code.toByte()
        p32(b, 12, parent); p16(b, 16, dirUsable() - 12); b[18] = 2; b[19] = (if (hasFiletype) 2 else 0).toByte(); b[20] = '.'.code.toByte(); b[21] = '.'.code.toByte()
        finishDirBlock(ino, u32(raw, 0x64), b)
        if (!writeBlock(blk, b) || !writeInode(ino, raw)) return@guarded false
        if (!addEntry(parent, name, ino, 2)) return@guarded false
        // the parent gains a link (the child's ".."); dir_nlink volumes cap the count at 1 = "many"
        val pr = readInode(parent) ?: return@guarded false
        val links = u16(pr, 0x1A)
        if (links in 1 until 65000 || !dirNlink) { if (links >= 65000) return@guarded false; p16(pr, 0x1A, links + 1) } else p16(pr, 0x1A, 1)
        touch(pr); if (!writeInode(parent, pr)) return@guarded false
        commit()
    }

    override fun writeFile(parentEntry: FileSystemEntry, name: String, data: ByteArray): Boolean =
        writeFileStream(parentEntry, name, data.size.toLong(), java.io.ByteArrayInputStream(data), null)

    override fun writeFileStream(parentEntry: FileSystemEntry, name: String, size: Long, input: InputStream, onProgress: ((Long) -> Unit)?): Boolean = guarded("writeFile $name") {
        if (!validName(name) || size < 0) return@guarded false
        val parent = dirInodeOf(parentEntry) ?: return@guarded false
        if (lookup(parent, name) != null) { Log.w(TAG, "ext: '$name' already exists"); return@guarded false }
        val praw = readInode(parent) ?: return@guarded false
        val pgroup = ((parent - 1) / ipg).toInt()
        val nBlocks = (size + bs - 1) / bs
        val ino = allocInode(pgroup, false) ?: return@guarded false
        val goal = groupFirstBlock(((ino - 1) / ipg).toInt())
        val data = allocBlocks(nBlocks, goal) ?: run { Log.w(TAG, "ext: no space for $name"); return@guarded false }
        var logical = 0L
        val runs = mergeRuns(data.map { r -> Run(logical, r.phys, r.len).also { logical += r.len } })
        val metaBlocks = allocMeta(runs, goal) ?: return@guarded false
        // data first
        val chunkBlocks = (1 shl 20) / bs
        val buf = ByteArray(chunkBlocks * bs); var written = 0L
        for (r in runs) {
            var done = 0L
            while (done < r.len) {
                val cnt = minOf(chunkBlocks.toLong(), r.len - done).toInt()
                val want = minOf(cnt.toLong() * bs, size - written).toInt()
                var got = 0
                while (got < want) { val k = input.read(buf, got, want - got); if (k < 0) break; got += k }
                if (got < want) throw java.io.IOException("the source ended early ($written+$got of $size)")
                java.util.Arrays.fill(buf, got, cnt * bs, 0)
                if (!writeBytes((r.phys + done) * bs, buf, cnt * bs)) return@guarded false
                done += cnt; written += got; onProgress?.invoke(written)
            }
        }
        val raw = newInodeRaw(0x8000 or 0x1A4, u16(praw, 2) or (u16(praw, 0x78) shl 16), u16(praw, 0x18) or (u16(praw, 0x7A) shl 16), 1, size, if (hasExtents) FL_EXTENTS else 0)
        val writes = applyMapping(ino, raw, runs, ArrayDeque(metaBlocks))
        for ((bn, d) in writes) if (!writeBlock(bn, d)) return@guarded false
        setBlocks(raw, nBlocks + metaBlocks.size)
        if (size >= 0x80000000L) { /* large_file is set by mke2fs on every modern volume */ }
        if (!writeInode(ino, raw)) return@guarded false
        if (!addEntry(parent, name, ino, 1)) return@guarded false
        commit()
    }

    /** Frees an inode and all its blocks. Directories must already be empty. */
    private fun destroyInode(ino: Long, raw: ByteArray) {
        val mode = u16(raw, 0); val isDir = mode and 0xF000 == 0x4000
        val fastSymlink = mode and 0xF000 == 0xA000 && inodeBlocks(raw) == 0L
        if (!fastSymlink && u32(raw, 0x20).toInt() and FL_INLINE == 0) freeMapping(readMapping(raw))
        val acl = u32(raw, 0x68)
        if (acl != 0L) freeRun(acl, 1)                       // the extended-attribute block (single owner on volumes without shared blocks)
        val dead = ByteArray(inodeSize); p32(dead, 0x64, u32(raw, 0x64)); p32(dead, 0x14, now()); p16(dead, 0x1A, 0)
        if (inodeSize > 128) p16(dead, 0x80, u16(raw, 0x80))
        writeInode(ino, dead)
        freeInode(ino, isDir)
    }

    private fun deleteTree(ino: Long): Boolean {
        val raw = readInode(ino) ?: return false
        val mode = u16(raw, 0)
        if (mode and 0xF000 == 0x4000) {
            val d = loadDir(ino) ?: return false
            for (pb in d.phys) {
                if (pb == 0L) continue
                val b = readBlock(pb) ?: return false
                for (e in parseEnts(b)) if (e.inode != 0L && e.name != "." && e.name != "..") {
                    val cr = readInode(e.inode) ?: return false
                    if (u16(cr, 0) and 0xF000 == 0x4000) { if (!deleteTree(e.inode)) return false } else { if (!unlinkFile(e.inode, cr)) return false }
                }
            }
            destroyInode(ino, raw)
            return true
        }
        return unlinkFile(ino, raw)
    }

    private fun unlinkFile(ino: Long, raw: ByteArray): Boolean {
        val links = u16(raw, 0x1A)
        if (links > 1) { p16(raw, 0x1A, links - 1); touch(raw); return writeInode(ino, raw) }
        destroyInode(ino, raw); return true
    }

    override fun deleteEntry(entry: FileSystemEntry): Boolean = guarded("delete ${entry.name}") {
        if (entry.path == "/" || entry.name.isEmpty()) return@guarded false
        val parent = resolve(entry.path.substringBeforeLast('/').ifEmpty { "/" }) ?: return@guarded false
        val (ino, _) = lookup(parent, entry.name) ?: return@guarded false
        if (entry.inodeOid > 0 && entry.inodeOid != ino) return@guarded false
        val raw = readInode(ino) ?: return@guarded false
        val isDir = u16(raw, 0) and 0xF000 == 0x4000
        if (!removeEntry(parent, entry.name)) return@guarded false
        if (isDir) {
            if (!deleteTree(ino)) return@guarded false
            val pr = readInode(parent) ?: return@guarded false
            val links = u16(pr, 0x1A); if (links > 2 || (!dirNlink && links > 1)) p16(pr, 0x1A, links - 1)
            touch(pr); if (!writeInode(parent, pr)) return@guarded false
        } else {
            if (!unlinkFile(ino, raw)) return@guarded false
            val pr = readInode(parent) ?: return@guarded false; touch(pr); writeInode(parent, pr)
        }
        commit()
    }

    override fun renameEntry(entry: FileSystemEntry, newName: String): Boolean = guarded("rename ${entry.name}") {
        if (!validName(newName) || entry.path == "/") return@guarded false
        val parent = resolve(entry.path.substringBeforeLast('/').ifEmpty { "/" }) ?: return@guarded false
        val (ino, type) = lookup(parent, entry.name) ?: return@guarded false
        if (newName == entry.name) return@guarded true
        if (lookup(parent, newName) != null) return@guarded false
        if (!addEntry(parent, newName, ino, type)) return@guarded false
        if (!removeEntry(parent, entry.name)) return@guarded false
        readInode(ino)?.let { touch(it); writeInode(ino, it) }
        commit()
    }
}
