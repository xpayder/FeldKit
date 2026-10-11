#!/bin/bash
# LUKS containers holding exFAT and FAT32 (file systems FeldKit can write), for the "write through LUKS" tests.
# Passphrase: FeldKit-Test-1.  usage: tools/linux/make-luks-writable.sh [outdir]
set -euo pipefail
OUT="${1:-$(cd "$(dirname "$0")/../.." && pwd)/app/build/fixtures}"; mkdir -p "$OUT"
docker run --rm --privileged -v /dev:/dev -v "$OUT":/out ubuntu:24.04 bash -c '
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null && apt-get install -y -qq cryptsetup exfatprogs dosfstools e2fsprogs util-linux udev exfat-fuse >/dev/null 2>&1
cd /out; PASS=FeldKit-Test-1
mk() {  # name, mkfs command, luksFormat args...
  N=$1; MK=$2; shift 2
  rm -f $N.img; truncate -s 64M $N.img
  L=$(losetup -f --show $N.img)
  echo -n "$PASS" | cryptsetup luksFormat --batch-mode "$@" $L -
  echo -n "$PASS" | cryptsetup open $L fk_$N -
  $MK /dev/mapper/fk_$N >/dev/null
  mkdir -p /mnt/$N; mount /dev/mapper/fk_$N /mnt/$N
  printf "Hello LUKS write\n" > /mnt/$N/hello.txt; mkdir -p /mnt/$N/dir; head -c 200000 /dev/urandom > /mnt/$N/dir/old.bin
  sync; umount /mnt/$N; cryptsetup close fk_$N; losetup -d $L
}
mk luksw-exfat-xts "mkfs.exfat -L FKLE" --type luks2 --pbkdf pbkdf2 --pbkdf-force-iterations 1000 --cipher aes-xts-plain64 --key-size 512
mk luksw-fat32-cbc "mkfs.vfat -F 32 -n FKLF" --type luks1 --cipher aes-cbc-essiv:sha256 --key-size 256 --hash sha1 --pbkdf-force-iterations 1000
mk luksw-exfat-4k "mkfs.exfat -L FKL4" --type luks2 --pbkdf pbkdf2 --pbkdf-force-iterations 1000 --cipher aes-xts-plain64 --key-size 512 --sector-size 4096
ls -la luksw-*.img
'
