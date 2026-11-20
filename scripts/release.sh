#!/usr/bin/env bash
# Verify and package a release APK. Run after `./gradlew check assembleRelease`.
# Usage: scripts/release.sh 2.0.0
set -euo pipefail

version="${1:-}"
if [[ -z "$version" ]]; then
  echo "usage: $0 <version>   e.g. $0 2.0.0" >&2
  exit 2
fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
apk_dir="$root/app/build/outputs/apk/release"
apk="$apk_dir/app-release.apk"
out="$apk_dir/handlebar-link-$version.apk"

[[ -f "$apk" ]] || { echo "no release APK: build with ./gradlew assembleRelease" >&2; exit 1; }

# The identity is injected at build time (docs/LESSONS.md 128). An APK without it cannot
# start CarPlay, which makes it useless as a public release.
entries="$(unzip -l "$apk")"
if ! grep -q 'assets/offline-mfi/identity.pk8' <<< "$entries" ||
   ! grep -q 'assets/offline-mfi/certificate.p7b' <<< "$entries"; then
  echo "APK has no CarPlay identity assets; set carplay.mfi.dir and rebuild" >&2
  exit 1
fi
if ! grep -q 'assets/licenses/LICENSE-AGPL.txt' <<< "$entries"; then
  echo "APK has no AGPL text under assets/licenses/" >&2
  exit 1
fi

# Confirm which key signed it. The fingerprint must match the README before publishing.
# v2/v3 signatures are not readable by keytool -printcert -jarfile, so use apksigner.
if [[ -z "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
signer="$(find "$sdk/build-tools" -type f -name apksigner | sort -V | tail -1)"
[[ -n "$signer" ]] || { echo "no apksigner under $sdk/build-tools" >&2; exit 1; }
"$signer" verify --print-certs "$apk" 2>/dev/null | grep 'SHA-256 digest'

cp "$apk" "$out"
(cd "$apk_dir" && shasum -a 256 "$(basename "$out")" > "$(basename "$out").sha256")
cat "$out.sha256"

echo "Release package ready: $out"
echo "Next: tag v$version and push; see docs/RELEASING.md"
