#!/bin/bash
# Run inside a privileged Ubuntu container: for every written-ext-*.img the app produced, e2fsck must find nothing to fix and a real kernel
# mount must show exactly the files in the .expect list (sha256, size, path).
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null && apt-get install -y -qq e2fsprogs util-linux >/dev/null 2>&1
fail=0
for f in /fx/written-ext-*.img; do
  [ -e "$f" ] || continue
  n=$(basename "$f" .img); echo "=== $n"
  e2fsck -fn "$f" > /tmp/fsck.txt 2>&1; rc=$?
  if [ $rc -eq 0 ]; then echo "  e2fsck: clean"; else echo "  e2fsck: exit $rc"; head -25 /tmp/fsck.txt; fail=1; fi
  mkdir -p /mnt/w; if mount -o loop,ro "$f" /mnt/w 2>/tmp/mnt.txt; then
    (cd /mnt/w && find . -path ./lost+found -prune -o \( -type f -o -type l \) -print0 | sort -z | while IFS= read -r -d "" p; do
      if [ -L "$p" ]; then t=$(readlink -n "$p"); printf "%s\t%s\t%s\n" "$(printf %s "$t" | sha256sum | cut -d" " -f1)" "${#t}" "${p#./}"   # a symlink is listed by its target text
      else printf "%s\t%s\t%s\n" "$(sha256sum "$p" | cut -d" " -f1)" "$(stat -c %s "$p")" "${p#./}"; fi
    done) > /tmp/got.txt
    sort -t$'\t' -k3 "/fx/$n.expect" > /tmp/want.txt; sort -t$'\t' -k3 /tmp/got.txt > /tmp/got.s
    if diff /tmp/want.txt /tmp/got.s > /tmp/diff.txt; then echo "  mount: all $(wc -l < /tmp/got.s) files match"; else echo "  mount: MISMATCH"; head -12 /tmp/diff.txt; fail=1; fi
    umount /mnt/w
  else echo "  mount FAILED: $(cat /tmp/mnt.txt)"; dmesg 2>/dev/null | tail -3; fail=1; fi
done
[ $fail = 0 ] && echo "ALL GOOD" || echo "SOME FAILED"
