#!/bin/bash
# Runs inside a privileged Linux container (see tools/bitlocker/run-validate-written.sh): opens the images the app wrote through
# BitLocker with dislocker, runs the file system checkers and compares the written files with the .expect lists.
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null && apt-get install -y -qq util-linux ntfs-3g exfatprogs dislocker fuse3 >/dev/null 2>&1
fail=0
for img in bitlocker-exfat bitlocker-ntfs bitlocker-cbc; do
  f=/fx/bitlocker-written-$img.img; [ -e "$f" ] || continue
  echo "=== $img"
  L=$(losetup -f --show -o $((2048*512)) "$f")
  mkdir -p /mnt/dl /mnt/b; rm -rf /mnt/dl/*
  dislocker -V $L -uVolumeX-Test-1 -- /mnt/dl 2>&1 | tail -1
  L2=$(losetup -f --show /mnt/dl/dislocker-file)
  case $img in
    bitlocker-exfat) fsck.exfat -n $L2 2>&1 | tail -3 ;;
    *) ntfsfix -n $L2 2>&1 | tail -4 ;;
  esac
  (mount -o ro $L2 /mnt/b 2>&1 || ntfs-3g -o ro $L2 /mnt/b 2>&1 || mount.exfat-fuse -o ro $L2 /mnt/b 2>&1) | tail -2
  while IFS=$'\t' read -r sha size path; do
    got=$(sha256sum "/mnt/b/$path" 2>/dev/null | cut -d' ' -f1)
    [ "$got" = "$sha" ] && echo "  ok   $path" || { echo "  FAIL $path"; fail=1; }
  done < /fx/bitlocker-written-$img.expect
  umount /mnt/b 2>/dev/null; losetup -d $L2; umount /mnt/dl 2>/dev/null; losetup -d $L
done
[ $fail = 0 ] && echo "ALL GOOD" || echo "SOME FAILED"
