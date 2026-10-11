package app.feldkit.storage.filesystem.bitlocker

import android.util.Log
import app.feldkit.storage.crypto.BitLocker
import app.feldkit.storage.disk.BlockDeviceReader
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.storage.filesystem.FileSystemReader
import app.feldkit.storage.filesystem.FilesystemMounter
import app.feldkit.storage.filesystem.VolumeInfo
import java.io.OutputStream

/** The volume as the file system sees it: decrypted sectors, with the relocated first sectors put back at 0. */
class BitLockerDevice(
    private val raw: BlockDeviceReader, private val startLba: Long, private val info: BitLocker.Info, key: ByteArray
) : BlockDeviceReader {
    private val cipher = BitLocker.SectorCipher(info.method, key)
    private val hdrSectors = info.headerSize / 512
    private val hdrBase = info.headerOffset / 512
    private val total = info.volumeSize / 512
    override fun open() = true
    override fun close() {}
    override fun isOpen() = true
    override fun sectorSize() = 512
    override fun sectorCount() = total

    override fun readSector(lba: Long): ByteArray? = readSectors(lba, 1)

    override fun readSectors(startLba: Long, count: Int): ByteArray? {
        if (startLba < 0 || startLba + count > total) return null
        val out = ByteArray(count * 512)
        var done = 0
        while (done < count) {
            val l = startLba + done
            val inHeader = l < hdrSectors
            val phys = if (inHeader) hdrBase + l else l
            val run = if (inHeader) minOf(count - done.toLong(), hdrSectors - l).toInt() else count - done
            val data = raw.readSectors(this.startLba + phys, run) ?: return null
            for (i in 0 until run) cipher.decrypt(data, i * 512, out, (done + i) * 512, phys + i)
            done += run
        }
        return out
    }

    override fun dropReadCache() = raw.dropReadCache()
    override fun flushCache() = raw.flushCache()
    override fun writeSector(lba: Long, data: ByteArray): Boolean = writeSectors(lba, data)

    /** Encrypts whole sectors and writes them where BitLocker keeps them (the first sectors live in the relocated header area). */
    override fun writeSectors(startLba: Long, data: ByteArray): Boolean {
        val count = data.size / 512
        if (data.size % 512 != 0 || startLba < 0 || startLba + count > total) return false
        var done = 0
        while (done < count) {
            val l = startLba + done
            val inHeader = l < hdrSectors
            val phys = if (inHeader) hdrBase + l else l
            val run = if (inHeader) minOf(count - done.toLong(), hdrSectors - l).toInt() else count - done
            val enc = ByteArray(run * 512)
            for (i in 0 until run) cipher.encrypt(data, (done + i) * 512, enc, i * 512, phys + i)
            if (!raw.writeSectors(this.startLba + phys, enc)) return false
            done += run
        }
        return true
    }
}

/**
 * A BitLocker volume that must be unlocked (password or 48-digit recovery password) before it can be read; afterwards it
 * delegates to the NTFS / exFAT / FAT32 filesystem inside. Writing (experimental, Settings) encrypts every sector on its way to the drive.
 */
class BitLockerReader(private val dev: BlockDeviceReader, private val startLba: Long) : FileSystemReader {
    private var info: BitLocker.Info? = null
    private var inner: FileSystemReader? = null
    private var innerW: app.feldkit.storage.filesystem.FileSystemWriter? = null

    override fun innerWriter(volumeIndex: Int) = innerW

    override fun mount(): Boolean {
        info = BitLocker.load(dev, startLba) ?: return false
        // a suspended ("clear key") volume opens without a secret
        val i = info!!
        if (i.supported) BitLocker.unlockWithClearKey(i)?.let { openWith(it) }
        return true
    }

    private fun openWith(fvek: ByteArray): Boolean {
        val i = info ?: return false
        val m = FilesystemMounter.mountInner(BitLockerDevice(dev, startLba, i, fvek)) ?: return false
        inner = m.first; innerW = m.second
        return true
    }

    override fun isLocked(volumeIndex: Int) = inner == null

    override fun unlock(volumeIndex: Int, secret: String): Boolean {
        val i = info ?: return false
        if (inner != null) return true
        if (!i.supported) { Log.w("FeldKit", "BitLocker: unsupported mode ${"%#x".format(i.method)} / state"); return false }
        val key = BitLocker.unlock(i, secret) ?: return false
        return openWith(key)
    }

    override fun getVolumeInfos(): List<VolumeInfo> {
        val i = info
        inner?.getVolumeInfos()?.let { list -> return list.map { it.copy(isEncrypted = true, encryption = "BitLocker") } }
        val note = if (i != null && !i.supported) " (this BitLocker mode is not supported yet)" else ""
        return listOf(VolumeInfo(
            name = (i?.description?.takeIf { it.isNotBlank() } ?: "BitLocker volume") + note, type = "BitLocker",
            totalBlocks = (i?.volumeSize ?: 0L) / 512, blockSize = 512, isEncrypted = true, encryption = "BitLocker"
        ))
    }

    override fun listDirectory(volumeIndex: Int, path: String) = inner?.listDirectory(volumeIndex, path) ?: emptyList()
    override fun readFile(entry: FileSystemEntry) = inner?.readFile(entry)
    override fun readFileTo(entry: FileSystemEntry, out: OutputStream, onProgress: ((Long) -> Unit)?) = inner?.readFileTo(entry, out, onProgress) ?: false
    override fun readRange(entry: FileSystemEntry, offset: Long, buf: ByteArray, bufOff: Int, len: Int) = inner?.readRange(entry, offset, buf, bufOff, len) ?: -1
    override fun rootEntry(volumeIndex: Int) = inner?.rootEntry(volumeIndex) ?: super.rootEntry(volumeIndex)
    override fun unmount() { inner?.unmount(); inner = null; innerW = null; info = null }
}
