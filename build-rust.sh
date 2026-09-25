#!/usr/bin/env bash
# Build the Rust bridge for the phone into app/src/main/jniLibs.
set -euo pipefail
cd "$(dirname "$0")/rust"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/ndk/* | sort -V | tail -1)}"
cargo ndk -t arm64-v8a -P 29 -o ../app/src/main/jniLibs build --release -p bridge
