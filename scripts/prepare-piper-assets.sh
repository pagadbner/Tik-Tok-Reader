#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PIPER="$ROOT/piper-kotlin"
[[ -d "$PIPER" ]] || { echo "Falta piper-kotlin; ejecuta scripts/setup-piper.sh" >&2; exit 1; }

cd "$PIPER"
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
  find "$DICT_TMP" -maxdepth 1 -type f -name '*dict*' -exec cp {} "$BUILD/espeak-ng-data/" \;
fi

ASSETS="$ROOT/app/src/main/assets/espeak-ng-data"
rm -rf "$ASSETS"
mkdir -p "$(dirname "$ASSETS")"
cp -a "$BUILD/espeak-ng-data" "$ASSETS"
echo "Piper runtime assets staged at $ASSETS"
du -sh "$ASSETS"
