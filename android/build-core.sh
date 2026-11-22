#!/usr/bin/env bash
# Builds libhexcore (Hex's engine and text steps) for arm64-v8a in two CPU
# variants. HexCore.kt loads the best one the phone supports:
#   libhexcore_dotprod.so  dotprod + fp16 (most phones since 2018)
#   libhexcore_base.so     plain ARMv8-A, for anything else (about 40% slower)
# An i8mm variant measured no faster for this model on a Snapdragon 8 Gen 2.
# ggml can't pick its kernels at run time in a static build, so each variant
# is a separate build with its own target directory.
# Gradle's preBuild runs this; running it by hand is fine too.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/env.sh"

command -v cargo-ndk >/dev/null || { echo "cargo-ndk is missing: cargo install cargo-ndk" >&2; exit 1; }
[ -f "$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" ] || { echo "No NDK at $ANDROID_NDK_HOME" >&2; exit 1; }

jobs="${CARGO_BUILD_JOBS:-$(( $(nproc) / 2 > 0 ? $(nproc) / 2 : 1 ))}"
export CARGO_BUILD_JOBS="$jobs" CMAKE_BUILD_PARALLEL_LEVEL="${CMAKE_BUILD_PARALLEL_LEVEL:-$jobs}"
# cmake-rs alone knows no NDK; the NDK's own toolchain file sets it up.
export CMAKE_TOOLCHAIN_FILE_aarch64_linux_android="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
export CMAKE_GENERATOR="${CMAKE_GENERATOR:-Ninja}"

out="$here/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$out"
rm -f "$out"/libhexcore*.so
cd "$here/core"
for variant in dotprod:armv8.2-a+dotprod+fp16 base:armv8-a; do
    name="${variant%%:*}"
    arch="${variant#*:}"
    export TRANSCRIBE_CMAKE_ARGS="-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DGGML_OPENMP=OFF -DGGML_CPU_ARM_ARCH=$arch"
    cargo ndk -t arm64-v8a -P 30 build --release --locked --lib --target-dir "target/$name"
    cp "target/$name/aarch64-linux-android/release/libhexcore.so" "$out/libhexcore_$name.so"
done
