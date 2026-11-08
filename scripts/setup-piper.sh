#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ ! -d "$ROOT/piper-kotlin/.git" ]]; then
  git clone --recurse-submodules https://github.com/piper-tts-apps/piper-kotlin.git "$ROOT/piper-kotlin"
else
  git -C "$ROOT/piper-kotlin" submodule update --init --recursive
fi
"$ROOT/scripts/prepare-piper-assets.sh"
echo "Piper preparado. Compila con Gradle 8.11.1, JDK 17, Android SDK 35, NDK 27 y CMake 3.22.1."
