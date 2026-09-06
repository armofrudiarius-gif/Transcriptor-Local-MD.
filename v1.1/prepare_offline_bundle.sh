#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
LIB_DIR="$ROOT/app/libs"
WHISPER_DIR="$ROOT/app/src/main/assets/models/whisper"
DIAR_DIR="$ROOT/app/src/main/assets/models/diarization"
CACHE="$ROOT/.model-cache"
mkdir -p "$LIB_DIR" "$WHISPER_DIR" "$DIAR_DIR" "$CACHE"

fetch() {
  local url="$1" out="$2"
  if [[ -s "$out" ]]; then return 0; fi
  echo "Downloading build-time asset: $(basename "$out")"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 --connect-timeout 20 "$url" -o "$out"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$out" "$url"
  else
    echo "Need curl or wget" >&2; exit 1
  fi
}

AAR="$LIB_DIR/sherpa-onnx-1.13.7.aar"
fetch "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.7/sherpa-onnx-1.13.7.aar" "$AAR"
EXPECTED_AAR_SHA="c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8"
ACTUAL_AAR_SHA="$(sha256sum "$AAR" | awk '{print $1}')"
[[ "$ACTUAL_AAR_SHA" == "$EXPECTED_AAR_SHA" ]] || { echo "AAR SHA-256 mismatch" >&2; exit 2; }

WHISPER_ARCHIVE="$CACHE/sherpa-onnx-whisper-small.tar.bz2"
fetch "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2" "$WHISPER_ARCHIVE"
if [[ ! -s "$WHISPER_DIR/small-encoder.int8.onnx" || ! -s "$WHISPER_DIR/small-decoder.int8.onnx" || ! -s "$WHISPER_DIR/small-tokens.txt" ]]; then
  TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
  tar -xjf "$WHISPER_ARCHIVE" -C "$TMP"
  BASE="$(find "$TMP" -type f -name small-encoder.int8.onnx -printf '%h\n' | head -1)"
  [[ -n "$BASE" ]] || { echo "Whisper archive layout not recognized" >&2; exit 3; }
  cp "$BASE/small-encoder.int8.onnx" "$WHISPER_DIR/"
  cp "$BASE/small-decoder.int8.onnx" "$WHISPER_DIR/"
  cp "$BASE/small-tokens.txt" "$WHISPER_DIR/"
  rm -rf "$TMP"; trap - EXIT
fi

SEG_ARCHIVE="$CACHE/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
fetch "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2" "$SEG_ARCHIVE"
if [[ ! -s "$DIAR_DIR/segmentation.onnx" ]]; then
  TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
  tar -xjf "$SEG_ARCHIVE" -C "$TMP"
  SEG="$(find "$TMP" -type f -name model.onnx | head -1)"
  [[ -n "$SEG" ]] || { echo "Segmentation model not found in archive" >&2; exit 4; }
  cp "$SEG" "$DIAR_DIR/segmentation.onnx"
  rm -rf "$TMP"; trap - EXIT
fi

fetch "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx" "$DIAR_DIR/embedding.onnx"

for f in \
  "$AAR" \
  "$WHISPER_DIR/small-encoder.int8.onnx" \
  "$WHISPER_DIR/small-decoder.int8.onnx" \
  "$WHISPER_DIR/small-tokens.txt" \
  "$DIAR_DIR/segmentation.onnx" \
  "$DIAR_DIR/embedding.onnx"; do
  [[ -s "$f" ]] || { echo "Missing: $f" >&2; exit 5; }
done

echo "Offline bundle ready. Runtime network access is not used by the app."
