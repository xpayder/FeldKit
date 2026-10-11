# Architecture (overview)

A map of the source tree. Names change faster than this page; the code is the reference.

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
