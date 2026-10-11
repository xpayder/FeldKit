# How every format is verified

The detailed version of the table in the [README](../README.md): what each format supports, the exact limits, and how each claim is checked.

## Filesystems (verified on real images)

Every row below is checked by automated tests that build real disk images with the macOS tools
(`tools/make-fixtures.sh`), run the app's own mount/read/write code on them, and then let macOS
judge the result (`tools/verify-written.sh`: `fsck_apfs` / `fsck_hfs -f` / `fsck_exfat` / `fsck_msdos`,
mount, SHA-256 comparison).

| Format | Read | Write | Notes |
|--------|:----:|:-----:|-------|
| **exFAT** | yes | yes | NoFatChain files, 128 KB clusters, 8 GB volume tested |
| **FAT32** | yes | yes | long names, `~N` aliases, FSInfo |
| **HFS+ / HFS+J** | yes | yes | catalog B-tree split/merge, attribute cleanup on delete; refuses unclean/journal-pending volumes |
| **APFS** | yes | experimental | in-place editing (not copy-on-write); off by default (Settings > Experimental); needs an unencrypted volume with no snapshots; creates missing chunk bitmaps (verified with `fsck_apfs`, 150 MB file across chunks) |
| ext2/3/4 | yes | experimental | Reads block maps and extent trees, 64-bit, flex_bg, hash-tree directories, streamed reads. Write (off by default, Settings > Experimental): create, rename and delete files and folders, directly without the journal; refuses volumes whose journal needs recovery or that use quota, bigalloc, meta_bg, inline data or encryption. Verified with `e2fsck -fn` and a real Linux kernel mount on 15 images from `mke2fs` (ext2, ext3, ext4, 1 and 4 KiB blocks, with and without metadata_csum, 32- and 64-bit, 700 MB with many groups, fragmented files, directories of thousands of entries): `tools/linux/make-ext-fixtures.sh`, `ExtWriteTest`, `tools/linux/validate-ext-written.sh` |
| FileVault (APFS) | yes (password or recovery key) | no | verified against a volume encrypted by macOS (`filevault.img`, tests in `FileVaultTest`); opened read-only |
| NTFS | yes | experimental | MFT, attribute lists, sparse + compressed (LZNT1) files, large directories; verified on an image made by `mkntfs`/ntfs-3g (`tools/make-ntfs-fixture.sh`). Write (off by default): create / rename / delete with MFT growth and B+tree index splits, refuses dirty or hibernated volumes; verified with ntfs-3g and `ntfsresize` (`tools/verify-ntfs.sh`), **not** with Windows chkdsk. Not readable: EFS-encrypted files |
| BitLocker (Windows 7+, To Go) | yes (password or 48-digit recovery key) | experimental | XTS and CBC ciphers (not the Windows 7 diffuser mode); NTFS / exFAT / FAT32 inside. Write (off by default, Settings > Experimental): every sector is encrypted on its way to the drive; the result opens in `dislocker` and passes `fsck.exfat` (`BitLockerWriteTest`, `tools/bitlocker/validate-written.sh`); fixtures opened by cryptsetup and dislocker (`tools/make-bitlocker.py`) |
| ISO 9660 (+ Joliet, Rock Ridge) | yes | no | optical images and installer sticks; open an `.iso` / `.img` from inside a drive and browse it without unpacking. Checked against images from `hdiutil` and `xorriso` (`tools/make-iso-fixtures.sh`, `tools/make-iso-rr.sh`) |
| UDF | yes | no | DVD / Blu-ray images; plain and sparable partitions (not CD-RW virtual partitions). Checked against `hdiutil` images |
| Apple disk images (.dmg) | yes | no | UDRO / UDZO (zlib) / UDBZ (bzip2) holding HFS+ (APM or GPT); LZFSE (ULFO / ULMO) is recognised but not supported yet |
| LUKS 1 / LUKS 2 | yes (passphrase) | experimental | AES-XTS and AES-CBC (plain / plain64 / ESSIV); PBKDF2, Argon2i and Argon2id (a 1 GiB Argon2id keyslot unlocks in about 50 s on a Galaxy S21 FE); not serpent / twofish. 512-byte and 4 KiB encryption sectors. Write (off by default, Settings > Experimental) re-encrypts every sector; opened and fsck-checked with the real `cryptsetup` (`LuksWriteTest`, `tools/linux/validate-luks-written.sh`). Verified against containers made by `cryptsetup` 2.7 and the RFC 9106 Argon2 vectors |
| LVM 2 | yes | with the volume inside | physical volumes holding one or more logical volumes (linear); each logical volume is its own volume. Verified against `lvm2` images; no RAID / thin / striped |

Write operations: add files (streamed), new folder, rename, delete (recursive). Multi-partition GPT/MBR
drives are supported (EFI partition skipped).

### Known limits
- APFS write is in-place (no copy-on-write): an unplugged cable mid-write can leave the volume needing `fsck_apfs`. Encrypted and snapshotted volumes stay read-only.
- HFS+ write does not grow the catalog file and cannot delete files whose attributes or extents live in overflow structures.
- 4Kn (4096-byte logical sector) drives are not supported; sector size is assumed to be 512.
- A power loss while an APFS write is in progress can leave the drive needing repair on a Mac.

### Test it yourself
```
tools/make-fixtures.sh          # builds app/build/fixtures/*.img with macOS tools
./gradlew :app:testDebugUnitTest
tools/verify-written.sh         # asks macOS (fsck + mount + hashes) about every written image
tools/phone-image.sh push|open|import|pull   # run the debug app on a phone against an image
```
