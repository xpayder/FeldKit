# Credits

## Origin
This project started as a fork of **VolumeX** by Mirko A. Calvi (MIT licence, see [LICENSE](LICENSE)). The original USB
detection, the app skeleton and the first APFS / HFS+ reading code come from that project; roughly a quarter of the
original lines remain. Thank you for the foundation.

## Open-source libraries
| Library | Used for | Licence |
|---|---|---|
| AndroidX Media3 (ExoPlayer) | audio / video playback | Apache 2.0 |
| libVLC (VideoLAN) | software decoding of ProRes, DNxHD / MXF, FFV1, WMV, FLV… | LGPL 2.1 |
| Haze (Chris Banes) | liquid-glass blur and refraction | Apache 2.0 |
| Coil | image loading (GIF, SVG) | Apache 2.0 |
| Apache Commons Compress, XZ for Java | 7z / tar / gz / bz2 / xz archives | Apache 2.0 / public domain |
| junrar | RAR extraction | UnRAR licence (extraction only) |
| AndroidX ExifInterface | photo metadata | Apache 2.0 |
| LibRaw 0.21.3 | developing camera RAW files (Canon CR2/CR3, Nikon NEF, Sony ARW, Fujifilm RAF and several hundred more) | LGPL 2.1 / CDDL 1.0 (dual); built into its own library `libfeldraw.so`, source in `app/src/main/cpp/third_party/libraw` |

## References and independent checkers
The BitLocker, FileVault (APFS) and NTFS implementations were written from the public format documentation and by studying
the behaviour and source of these projects, which are also used (never shipped) to verify our output:
**cryptsetup** (`bitlk`), **dislocker**, **ntfs-3g / ntfsprogs**, **Apple's APFS reference** and macOS `fsck_apfs`.
