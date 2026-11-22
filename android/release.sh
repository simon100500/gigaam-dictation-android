#!/usr/bin/env bash
# Builds a signed release APK into android/dist/ and prints its signing
# certificate's SHA-256, which people can check before installing.
# The keystore stays outside the repo; its password comes from the GNOME
# Keyring (service hex-android-release, key password). Override with
# HEX_RELEASE_KEYSTORE and HEX_RELEASE_PASSWORD.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/env.sh"
export HEX_RELEASE_KEYSTORE="${HEX_RELEASE_KEYSTORE:-$HOME/.local/share/hex-android/release.jks}"
export HEX_RELEASE_PASSWORD="${HEX_RELEASE_PASSWORD:-$(secret-tool lookup service hex-android-release key password)}"
[ -f "$HEX_RELEASE_KEYSTORE" ] && [ -n "$HEX_RELEASE_PASSWORD" ] || { echo "No release keystore or password" >&2; exit 1; }
cd "$here"
./gradlew --console=plain -q assembleRelease
version=$(sed -n 's/.*versionName = "\(.*\)"/\1/p' app/build.gradle.kts)
mkdir -p dist
apk="dist/hex-android-$version.apk"
cp app/build/outputs/apk/release/app-release.apk "$apk"
apksigner=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)apksigner
"$apksigner" verify --print-certs "$apk" | grep -E 'SHA-256'
sha256sum "$apk"
