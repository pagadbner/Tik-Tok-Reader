#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PIPER="$ROOT/piper-kotlin"
[[ -d "$PIPER" ]] || { echo "Falta piper-kotlin; ejecuta scripts/setup-piper.sh" >&2; exit 1; }

cd "$PIPER"

# Use Google's maintained Maven Central mirror for the pinned ONNX Runtime AAR.
# This avoids the HTTP 404 observed from the Maven Central endpoints in CI,
# without changing Piper's pinned version or any engine/native source.
PIPER_DEPS_SCRIPT="$PIPER/scripts/fetch-native-deps.sh"
if grep -q 'https://repo1.maven.org/maven2/' "$PIPER_DEPS_SCRIPT"; then
  sed -i 's#https://repo1.maven.org/maven2/#https://maven-central.storage-download.googleapis.com/maven2/#g' "$PIPER_DEPS_SCRIPT"
elif grep -q 'https://repo.maven.apache.org/maven2/' "$PIPER_DEPS_SCRIPT"; then
  sed -i 's#https://repo.maven.apache.org/maven2/#https://maven-central.storage-download.googleapis.com/maven2/#g' "$PIPER_DEPS_SCRIPT"
fi

# Retry transient network errors and rate limits; permanent 404s fail immediately.
if grep -q 'curl -fL -o /tmp/onnxruntime-android.aar' "$PIPER_DEPS_SCRIPT"; then
  sed -i 's#curl -fL -o /tmp/onnxruntime-android.aar#curl --retry 6 --retry-delay 5 --retry-max-time 180 --connect-timeout 20 --max-time 240 -fL -o /tmp/onnxruntime-android.aar#' "$PIPER_DEPS_SCRIPT"
fi

./scripts/fetch-native-deps.sh
./scripts/stage-espeak-data.sh

# Piper's Spanish es-MX voice needs the Spanish eSpeak dictionary in addition
# to the common phoneme tables. Keep the generated file with runtime data.
ESPEAK="$PIPER/third-party/espeak-ng"
BUILD="$ESPEAK/build"
BIN="$BUILD/src/espeak-ng"
DICT_TMP="$BUILD/dict-tmp-es"
mkdir -p "$DICT_TMP"
for f in es_rules es_list es_emoji; do
  [[ -f "$ESPEAK/dictsource/$f" ]] && cp "$ESPEAK/dictsource/$f" "$DICT_TMP/"
done
if [[ -x "$BIN" && -f "$DICT_TMP/es_rules" ]]; then
  (cd "$DICT_TMP" && ESPEAK_DATA_PATH="$BUILD" "$BIN" --compile=es >/dev/null)
  find "$DICT_TMP" -maxdepth 1 -type f -name '*dict*' -exec cp {} "$BUILD/espeak-ng-data/" \\;
fi

ASSETS="$ROOT/app/src/main/assets/espeak-ng-data"
rm -rf "$ASSETS"
mkdir -p "$(dirname "$ASSETS")"
cp -a "$BUILD/espeak-ng-data" "$ASSETS"
echo "Piper runtime assets staged at $ASSETS"
du -sh "$ASSETS"
