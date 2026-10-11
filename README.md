<p align="center">
  <img src="docs/assets/logo.svg" width="128" alt="FeldKit logo">
</p>

<h1 align="center">FeldKit</h1>

<p align="center"><b>Open Mac and Windows drives on your Android phone. Free, open source, no root, no account.</b></p>

<p align="center">
APFS &middot; HFS+ &middot; NTFS &middot; exFAT &middot; FAT32 &middot; FileVault &middot; BitLocker
</p>

FeldKit is a field kit for people who work with drives: musicians, sound designers, photographers, videographers and
DITs, designers, animators, developers. When the laptop is not around, plug the SSD or card reader into your phone over
USB-C / OTG, see the files, play and preview them, check them, and copy them off (or on) with proof that nothing got
corrupted. No paid "drive reader" subscription needed.

## Does this solve your problem?

| You searched for | FeldKit |
|---|---|
| "Android can't read my Mac SSD / APFS drive" | reads APFS and HFS+, writes experimentally |
| "Android says drive is unsupported / needs formatting (NTFS)" | reads NTFS, writes experimentally |
| "open BitLocker drive on Android phone" | unlocks with password or 48-digit recovery key |
| "open FileVault encrypted external drive without a Mac" | unlocks with password or recovery key (read-only) |
| "copy footage from SSD to phone and verify the copy" | SHA-256 verified copy + `.sha256` manifest |
| "play ProRes / DNxHD / FLAC / AIFF from USB drive on Android" | built-in player with a libVLC fallback |
| "view RAW photos / PSD / EXIF from a card on my phone" | viewer with zoom and an EXIF panel |
| "unzip / unrar from an external drive on Android" | built-in archive browser and extractor |

## For developers

- **Source code you can read.** Syntax colours for Kotlin, Java, C/C++, C#, JavaScript/TypeScript, Python, Rust, Go, Swift, shell, Ruby, PHP, Lua, SQL, CSS, JSON, XML/HTML, Markdown, YAML, TOML, INI, Makefile and Dockerfile (by extension or `#!` line), with line numbers.
- **Search in the file:** plain text or regular expression, with or without case, next/previous, every match highlighted, and `:123` to jump to a line.
- **Structured files.** JSON and property lists (XML and Apple's binary `bplist00`) open as a collapsible tree (long-press copies the path, e.g. `$.items[2].name`), CSV and TSV as a table, and Markdown rendered (headings, lists, tasks, quotes, code with colours, tables, links). Each has a switch to the plain source. Property lists are checked against Python's `plistlib`.
- **SQLite databases** (`.sqlite`, `.sqlite3`, `.db`, also Core Data stores): tables and views, schema, rows page by page, `SELECT` queries, BLOBs shown by size, and CSV export to Downloads/FeldKit. The database is opened read-only from a temporary copy (up to 400 MB).
- **Edit a text file on the drive** (writable drives, up to 512 KB). Undo and redo, a confirmation before saving, and a careful save: the file must be unchanged since you opened it, its old content is kept inside FeldKit, the new content is written beside it and read back byte for byte, and only then replaces the original (the encoding, BOM and line endings are kept). If anything fails the original stays as it was; the history button in the editor loads the version from before the last save.

## For photographers

- **Develop RAW files.** Open a RAW and tap "Develop RAW": FeldKit decodes the real sensor data with LibRaw (camera white balance, demosaic, sRGB) in a few seconds, with camera, lens, ISO, shutter and aperture in the details. Checked on files from Canon (CR3), Nikon (NEF), Sony (ARW) and Fujifilm (X-Trans RAF); pictures above 60 MB are developed at half size so that they fit in the phone's memory. Before that the embedded preview is shown at once, and cameras that store an uncompressed thumbnail (some Nikon) get a proper grid tile. LibRaw is LGPL / CDDL and lives in its own library, see [CREDITS](CREDITS.md).
- **Cull a shoot on the phone.** In any folder with pictures, menu > "Cull photos": swipe through the folder, give stars, reject, flag a pick and set a colour label. Filters (all, picked, not rated, 3 stars and up, rejected) and a counter ("123 / 540") keep long shoots manageable, and "save these photos" copies exactly what the filter shows, together with their `.xmp` files.
- **Histogram and location.** The photo info sheet shows an RGB and luminance histogram with a warning when shadows or highlights are clipped, and an "Open in Maps" link next to the GPS position.
- **Ratings travel with the files.** When the drive can be written, stars and labels are saved as `.xmp` sidecars next to the photos (`xmp:Rating`, `xmp:Label`), the same fields Lightroom, Bridge and Capture One read. An existing sidecar is edited in place, so develop settings and keywords in it stay untouched (checked with `exiftool`). On a read-only drive the marks stay in FeldKit.

## For people who make music

- **Real BPM of any audio file.** Open the info of a file and FeldKit measures its tempo on the phone (mp3, m4a/aac, flac, wav, aiff, ogg, opus...). It reads the tempo tags too and shows both, because a tag is often a rounded number. Loops that are cut to whole bars are locked to the exact tempo of the project they came from (typically to 0.01 BPM or better). Tracks without a steady beat say so instead of guessing.
- **Musical key.** Next to the tempo the info sheet shows the key in both notations, for example "F# minor · 11A" (the Camelot code DJs use), the key that shares the same notes, and the next best guess. MIDI files are read from their notes. For audio it is an estimate and a hard one: on 184 loops from sample packs that state their key in the name, about 35 % match exactly and 45 % match or land on the relative key (`tools/make-key-labels.py` + `KeyTest`); on synthetic chord progressions in all 24 keys it is 24 of 24. Loops of one or two notes, bass and drums are the weak spot, so a doubtful reading says "uncertain" and always lists the alternative.
- **Waveform, A-B loop, speed.** The playing row shows the waveform (tap or drag to seek, the sound follows the finger), A and B markers loop a passage without a gap, and the speed goes from 0.5x to 1.5x with the pitch kept. The markers are remembered per file.
- **What is inside a music project.** Tap an Ableton Live set (`.als`), an FL Studio project (`.flp`) or a REAPER project (`.rpp`) and FeldKit lists the DAW and version, tempo, meter, tracks or channels, plug-ins, samples and scenes, without the DAW. Verified on real projects (an 11 MB Live set reads in a few seconds on a Galaxy S21 FE). Logic, Pro Tools, Cubase and Studio One projects are not read yet.
- **Field-recorder and loop metadata in WAV files.** Scene, take, tape, notes and track names (iXML), description, originator and start timecode (broadcast wave), root note and loops (sampler), Acid loop data and marker count appear in the info sheet.
- **MIDI clips play with a piano and a drum kit.** Pitched parts sound as a piano, drum parts (GM channel 10, or a kit pattern on any channel, or a file named "Hi Hat 12" / "Kick 03") as a drum kit. Both are synthesised by FeldKit, so it sounds the same on every phone. The tempo is read from the file.

### How accurate is the BPM?

Measured, not promised: on synthetic tracks with a known tempo the error is at most 0.01 BPM (steady, swung, humanised and noisy grooves). On 450 real loops from sample packs whose names state their tempo (`tools/make-tempo-real.py` builds the check from your own library), about 79 % are exactly right, 9 % land on half / double time (the other reading is shown under "Also reads as"), and the rest are samples with no beat to find (risers, pads, vocals) or a wrong guess. Half / double time is genuinely ambiguous for a machine, so both readings are listed.

## Screenshots

<!-- Screenshots are added once the interface is finished. See docs/screenshots/README.md -->
<p align="center"><i>Screenshots coming soon.</i></p>

## Origin

FeldKit is a fork of [VolumeX](https://github.com/FatalPuppet/VolumeX) by Mirko A. Calvi (FatalPuppet), MIT licensed.
The original author built the USB transport and the first APFS / HFS+ readers; if this app saves you money or a
deadline, consider thanking them. Details in [CREDITS.md](CREDITS.md).

---

## Filesystem support (verified on macOS-made images)

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
| ext2/3/4 | yes | no | block maps and extent trees, 64-bit, flex_bg, hash-tree directories, streamed reads; checked against volumes made by `mkfs.ext4` (`tools/linux/make-luks-lvm.sh`) |
| FileVault (APFS) | yes (password or recovery key) | no | verified against a volume encrypted by macOS (`filevault.img`, tests in `FileVaultTest`); opened read-only |
| NTFS | yes | experimental | MFT, attribute lists, sparse + compressed (LZNT1) files, large directories; verified on an image made by `mkntfs`/ntfs-3g (`tools/make-ntfs-fixture.sh`). Write (off by default): create / rename / delete with MFT growth and B+tree index splits, refuses dirty or hibernated volumes; verified with ntfs-3g and `ntfsresize` (`tools/verify-ntfs.sh`), **not** with Windows chkdsk. Not readable: EFS-encrypted files |
| BitLocker (Windows 7+, To Go) | yes (password or 48-digit recovery key) | no | XTS and CBC ciphers (not the Windows 7 diffuser mode); NTFS / exFAT / FAT32 inside; fixtures opened by cryptsetup and dislocker (`tools/make-bitlocker.py`) |
| ISO 9660 (+ Joliet, Rock Ridge) | yes | no | optical images and installer sticks; open an `.iso` / `.img` from inside a drive and browse it without unpacking. Checked against images from `hdiutil` and `xorriso` (`tools/make-iso-fixtures.sh`, `tools/make-iso-rr.sh`) |
| UDF | yes | no | DVD / Blu-ray images; plain and sparable partitions (not CD-RW virtual partitions). Checked against `hdiutil` images |
| Apple disk images (.dmg) | yes | no | UDRO / UDZO (zlib) / UDBZ (bzip2) holding HFS+ (APM or GPT); LZFSE (ULFO / ULMO) is recognised but not supported yet |
| LUKS 1 / LUKS 2 | yes (passphrase) | no | AES-XTS and AES-CBC (plain / plain64 / ESSIV); PBKDF2, Argon2i and Argon2id (a 1 GiB Argon2id keyslot unlocks in about 50 s on a Galaxy S21 FE); not serpent / twofish. Verified against containers made by `cryptsetup` 2.7 and the RFC 9106 Argon2 vectors |
| LVM 2 | yes | no | physical volumes holding one or more logical volumes (linear); each logical volume is its own volume. Verified against `lvm2` images; no RAID / thin / striped |

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

---

## Architecture

```
app/
├── provider/
│   ├── DriveFileProvider        # ContentProvider pipe-streams files ("Open With" / Share)
│   └── DriveDocumentsProvider   # DocumentsProvider (Android file picker integration)
├── security/
│   └── KeystorePasswordManager  # AES-GCM + Android Keystore + BiometricPrompt
├── services/
│   └── TransferService          # Foreground Service for non-blocking transfers
├── storage/
│   ├── ActiveDriveSession       # Singleton for active reader (used by ContentProviders)
│   ├── FileOperationManager     # copy / delete / mkdir with progress callbacks
│   ├── crypto/
│   │   ├── AesXts               # Manual AES-XTS (GF(2^128) tweak advance)
│   │   ├── ApfsKeyDerivation    # PBKDF2-HMAC-SHA256 KEK, base-32 recovery key decode
│   │   ├── ApfsKeybag           # TLV keybag parser + RFC 3394 AES key unwrap
│   │   └── ApfsCrypto           # FileVault keybag / PBKDF2 / key unwrap
│   ├── disk/                    # BlockDeviceReader, DiskScanner, Partition
│   ├── filesystem/
│   │   ├── apfs/                # APFS container/volume superblock, OMAP, B-tree, reader
│   │   ├── hfsplus/             # HFS+ volume header, catalog B-tree, reader
│   │   ├── fat32/               # FAT32 boot sector, cluster chain, LFN reader
│   │   ├── ext/                 # ext2/3/4 superblock, BGD, extent tree reader
│   │   ├── exfat/               # exFAT boot sector
│   │   └── partition/           # GPT + MBR partition table parsers
│   ├── scsi/                    # SCSI command set (INQUIRY, READ CAPACITY, READ 10)
│   └── usb/                     # USB Bulk-Only Transport, UAS transport, interface scanner
└── ui/
    ├── screens/
    │   ├── HomeScreen            # USB connect flow, device info
    │   ├── VolumePickerScreen    # Multi-partition volume selector
    │   ├── FileBrowserScreen     # Directory listing, search, grid/list, operations
    │   ├── FilePreviewScreen     # Image / audio / video preview
    │   ├── FileVaultUnlockScreen # Password / recovery key entry
    │   ├── TransferScreen        # Transfer queue and progress
    │   └── SettingsScreen        # Hidden files, view mode, saved passwords
    ├── components/               # GlassCard, FileListItem, DeviceVolumeCard, ...
    └── theme/                    # Liquid Glass palette (deep navy + frosted glass)
```

---

## Requirements

- Android 8.0 (API 26) or newer with USB OTG support
- USB-C cable or USB-OTG adapter; large bus-powered drives may need a powered hub
- No root, no Mac, no account required

## Credits
See [CREDITS.md](CREDITS.md).

## License

See [LICENSE](LICENSE).
