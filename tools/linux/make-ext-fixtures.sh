#!/bin/bash
# ext2 / ext3 / ext4 images in many flavours made with the real mke2fs, for the ext writer tests. Each image gets files, a directory
# with 300 entries (hash tree), nested folders, a sparse file with many extents and a manifest of sha256/size/path.
# usage: tools/linux/make-ext-fixtures.sh [outdir]
set -euo pipefail
OUT="${1:-$(cd "$(dirname "$0")/../.." && pwd)/app/build/fixtures}"; mkdir -p "$OUT"
docker run --rm --privileged -v /dev:/dev -v "$OUT":/out ubuntu:24.04 bash -c '
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null && apt-get install -y -qq e2fsprogs util-linux >/dev/null 2>&1
cd /out
populate() {
  M=$1
  printf "Hello ext\n" > $M/hello.txt; : > $M/empty.txt
  head -c 3000000 /dev/urandom > $M/big.bin; head -c 4097 /dev/urandom > $M/odd4097.bin; head -c 1025 /dev/urandom > $M/odd1025.bin
  mkdir -p $M/dir/sub $M/many; printf "deep\n" > $M/dir/sub/deep.txt; printf "unicode\n" > $M/"Привет мир.txt"
  for i in $(seq 1 300); do printf "f%s\n" $i > $M/many/file_$i.txt; done
  # sparse file: eight separate extents (an extent tree one level deep)
  : > $M/sparse.bin; for i in 1 2 3 4 5 6 7 8; do dd if=/dev/urandom of=$M/sparse.bin bs=4096 count=1 seek=$((i*3)) conv=notrunc status=none; done
  ln -s hello.txt $M/link-to-hello
  (cd $M && find . -type f -print0 | sort -z | while IFS= read -r -d "" f; do printf "%s\t%s\t%s\n" "$(sha256sum "$f" | cut -d" " -f1)" "$(stat -c %s "$f")" "${f#./}"; done)
}
mk() {  # name, size, mke2fs args...
  N=$1; SZ=$2; shift 2
  rm -f $N.img; truncate -s $SZ $N.img
  mke2fs -q -F -L FKEXT "$@" $N.img
  mkdir -p /mnt/$N; mount -o loop $N.img /mnt/$N
  populate /mnt/$N > $N.manifest
  sync; umount /mnt/$N
  e2fsck -fn $N.img >/dev/null 2>&1 && echo "built $N (clean)" || echo "built $N (e2fsck complains!)"
}
mk ext4-1k      64M -t ext4
mk ext4-4k      96M -t ext4 -b 4096
mk ext4-nocsum  64M -t ext4 -O ^metadata_csum
mk ext4-nojrnl  64M -t ext4 -O ^has_journal
mk ext4-32bit   64M -t ext4 -O ^64bit,^metadata_csum -I 128
mk ext4-gdtcsum 64M -t ext4 -O ^metadata_csum,uninit_bg
mk ext3         64M -t ext3
mk ext2         64M -t ext2
mk ext2-4k      96M -t ext2 -b 4096
mk ext4-multi   700M -t ext4 -b 4096 -E lazy_itable_init=1 -N 20000
ls -la *.img | grep ext
'
