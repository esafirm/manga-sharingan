#!/usr/bin/env bash
# Creates the release signing key and (optionally) stores it as GitHub Actions secrets.
#
#   scripts/create-keystore.sh                 # create sharingan-release.jks, print secrets
#   scripts/create-keystore.sh --upload        # ...and set them with the gh CLI
#
# BACK UP THE .jks AND ITS PASSWORD. Android only installs an update if it is signed with the
# same key as the installed app, so losing this file means users must uninstall to update.
set -euo pipefail

OUT="sharingan-release.jks"
ALIAS="sharingan"
UPLOAD=0
for arg in "$@"; do
  case "$arg" in
    --upload) UPLOAD=1 ;;
    *.jks) OUT="$arg" ;;
    *) ALIAS="$arg" ;;
  esac
done

[ -e "$OUT" ] && { echo "Refusing to overwrite existing $OUT" >&2; exit 1; }
command -v keytool >/dev/null || { echo "keytool not found (install a JDK)" >&2; exit 1; }

PASS="$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 28)"

keytool -genkeypair -keystore "$OUT" -storetype PKCS12 \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Sharingan, O=Sharingan" >/dev/null

B64="$(base64 < "$OUT" | tr -d '\n')"

echo "Created $OUT (alias: $ALIAS)"
echo
if [ "$UPLOAD" = 1 ]; then
  command -v gh >/dev/null || { echo "gh CLI not found; run without --upload" >&2; exit 1; }
  printf '%s' "$B64"   | gh secret set ANDROID_KEYSTORE_BASE64
  printf '%s' "$PASS"  | gh secret set ANDROID_KEYSTORE_PASSWORD
  printf '%s' "$ALIAS" | gh secret set ANDROID_KEY_ALIAS
  printf '%s' "$PASS"  | gh secret set ANDROID_KEY_PASSWORD
  echo "Secrets uploaded to the current repository."
else
  echo "Add these as repository secrets (Settings > Secrets and variables > Actions):"
  echo "  ANDROID_KEYSTORE_PASSWORD = $PASS"
  echo "  ANDROID_KEY_PASSWORD      = $PASS"
  echo "  ANDROID_KEY_ALIAS         = $ALIAS"
  echo "  ANDROID_KEYSTORE_BASE64   = (contents of ${OUT}.base64.txt)"
  printf '%s' "$B64" > "${OUT}.base64.txt"
fi
echo
echo "Keep $OUT and the password somewhere safe. Do NOT commit them."
