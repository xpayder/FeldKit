#!/bin/bash
# Run inside a privileged Ubuntu container (tools/linux/run-validate.sh): opens the LUKS images the app wrote (written-luksw-*.img) with
# the real cryptsetup, runs fsck on the file system inside and compares the written files with the .expect lists.
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null && apt-get install -y -qq cryptsetup exfatprogs dosfstools e2fsprogs util-linux udev exfat-fuse >/dev/null 2>&1
fail=0
for f in /fx/written-luksw-*.img /fx/written-ext-*-luks*.img; do
  [ -e "$f" ] || continue
  n=$(basename "$f" .img); echo "=== $n"
  L=$(losetup -f --show "$f")
  echo -n "FeldKit-Test-1" | cryptsetup open $L v_$n - || { echo "  FAIL cannot open"; fail=1; losetup -d $L; continue; }
  D=/dev/mapper/v_$n
  case $n in
    *exfat*) fsck.exfat -n $D 2>&1 | tail -2 ;;
    *fat32*) fsck.vfat -n $D 2>&1 | tail -2 ;;
    *) e2fsck -fn $D 2>&1 | tail -3 ;;
  esac
  mkdir -p /mnt/v; (mount -o ro $D /mnt/v 2>&1 || mount.exfat-fuse -o ro $D /mnt/v 2>&1) | tail -1
  while IFS=$'\t' read -r sha size path; do
    got=$(sha256sum "/mnt/v/$path" 2>/dev/null | cut -d' ' -f1)
    [ "$got" = "$sha" ] && echo "  ok   $path" || { echo "  FAIL $path"; fail=1; }
  done < "/fx/$n.expect"
  umount /mnt/v 2>/dev/null; cryptsetup close v_$n; losetup -d $L
done
[ $fail = 0 ] && echo "ALL GOOD" || echo "SOME FAILED"
