#!/bin/sh
# Remotion 에 들어 있는 ffmpeg/ffprobe 를 직접 호출 (npx remotion ffmpeg 는 필터 인자를 깨뜨림)
B="$(cd "$(dirname "$0")/.." && pwd)/node_modules/@remotion/compositor-darwin-arm64"
tool="$1"; shift
DYLD_LIBRARY_PATH="$B" exec "$B/$tool" "$@"
