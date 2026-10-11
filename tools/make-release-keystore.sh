#!/bin/bash
# Creates the key FeldKit releases are signed with and prints what to put into the GitHub repository secrets.
# Run it ONCE, on your own machine, and keep the file it creates (~/feldkit-release.jks) and the passwords safe: an app update is
# accepted by phones only when it is signed with the same key as the installed version. Nothing here is committed or uploaded.
set -euo pipefail
KS="${1:-$HOME/feldkit-release.jks}"
[ -e "$KS" ] && { echo "$KS exists already; not overwriting it."; exit 1; }
PW=$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)
keytool -genkeypair -keystore "$KS" -storepass "$PW" -keypass "$PW" -alias feldkit -keyalg RSA -keysize 4096 -validity 36500 \
  -dname "CN=FeldKit, O=FeldKit open source" >/dev/null
chmod 600 "$KS"
echo "Created $KS"
echo
echo "Add four secrets to the repository (Settings > Secrets and variables > Actions), or run these commands with the GitHub CLI:"
echo
echo "  base64 -i \"$KS\" | gh secret set FELDKIT_KEYSTORE_BASE64"
echo "  gh secret set FELDKIT_KEYSTORE_PASSWORD --body '$PW'"
echo "  gh secret set FELDKIT_KEY_ALIAS --body 'feldkit'"
echo "  gh secret set FELDKIT_KEY_PASSWORD --body '$PW'"
echo
echo "Back up $KS and the password (a password manager is fine). If they are lost, installed copies can no longer be updated."
echo "Fingerprint of the key (SHA-256), to publish if you like:"
keytool -list -v -keystore "$KS" -storepass "$PW" -alias feldkit | grep "SHA256:"
