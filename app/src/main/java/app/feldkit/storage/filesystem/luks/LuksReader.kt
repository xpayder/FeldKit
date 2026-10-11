package app.feldkit.storage.filesystem.luks

import android.util.Log
import app.feldkit.storage.crypto.Luks
import app.feldkit.storage.crypto.LuksDevice
import app.feldkit.storage.disk.BlockDeviceReader
import app.feldkit.storage.filesystem.FileSystemEntry
import app.feldkit.storage.filesystem.FileSystemReader
import app.feldkit.storage.filesystem.FilesystemMounter
import app.feldkit.storage.filesystem.VolumeInfo
import java.io.OutputStream

/** A LUKS1 / LUKS2 container (Linux disk encryption): locked until the passphrase is given, then it shows the file system(s) inside. Writing (experimental, Settings) encrypts every sector on its way to the drive. */
class LuksReader(private val dev: BlockDeviceReader, private val startLba: Long) : FileSystemReader {
    private var info: Luks.Info? = null
    private var inner: FileSystemReader? = null
    private var innerW: app.feldkit.storage.filesystem.FileSystemWriter? = null

    override fun innerWriter(volumeIndex: Int) = (inner as? app.feldkit.storage.filesystem.CompositeReader)?.writerFor(volumeIndex) ?: innerW

    override fun mount(): Boolean { info = Luks.load(dev, startLba); return info != null }

    override fun isLocked(volumeIndex: Int) = inner == null

    override fun unlock(volumeIndex: Int, secret: String): Boolean {
        val i = info ?: return false
        if (inner != null) return true
        i.unsupported?.let { Log.w("FeldKit", "LUKS: $it"); return false }
        val key = Luks.unlock(dev, startLba, i, secret) ?: return false
        val plain = LuksDevice(dev, startLba, i, key)
        val all = FilesystemMounter.mountAll(plain)
        if (all.isEmpty()) { Log.w("FeldKit", "LUKS: unlocked, but no known file system inside"); return false }
        // one file system: use it directly; several (LVM with more than one logical volume): present them as separate volumes
        if (all.size == 1) { inner = all[0].reader; innerW = if (FilesystemMounter.enableEncryptedWrite) all[0].writer else null }
        else inner = app.feldkit.storage.filesystem.CompositeReader(if (FilesystemMounter.enableEncryptedWrite) all else all.map { FilesystemMounter.MountedPartition(it.reader, null, it.startLba) }).also { it.mount() }
        return true
    }

    override fun getVolumeInfos(): List<VolumeInfo> {
        inner?.getVolumeInfos()?.let { list -> return list.map { it.copy(isEncrypted = true, encryption = "LUKS") } }
        val i = info
        val note = i?.unsupported?.let { " ($it)" } ?: ""
        return listOf(VolumeInfo(name = "LUKS${i?.version ?: ""} volume$note", type = "LUKS", totalBlocks = 0, blockSize = 512, isEncrypted = true, encryption = "LUKS"))
    }

    private fun vi(volumeIndex: Int) = if (inner is app.feldkit.storage.filesystem.CompositeReader) volumeIndex else 0
    override fun listDirectory(volumeIndex: Int, path: String) = inner?.listDirectory(vi(volumeIndex), path) ?: emptyList()
    override fun readFile(entry: FileSystemEntry) = inner?.readFile(entry)
    override fun readFileTo(entry: FileSystemEntry, out: OutputStream, onProgress: ((Long) -> Unit)?) = inner?.readFileTo(entry, out, onProgress) ?: false
    override fun readRange(entry: FileSystemEntry, offset: Long, buf: ByteArray, bufOff: Int, len: Int) = inner?.readRange(entry, offset, buf, bufOff, len) ?: -1
    override fun rootEntry(volumeIndex: Int) = inner?.rootEntry(vi(volumeIndex)) ?: super.rootEntry(volumeIndex)
    override fun unmount() { inner?.unmount(); inner = null; innerW = null; info = null }
}
