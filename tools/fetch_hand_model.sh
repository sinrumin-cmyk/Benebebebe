#!/bin/sh
# Downloads MediaPipe's hand landmarker model into the app's assets (not kept in git: ~7.5 MB).
set -e
dir="$(dirname "$0")/../app/src/main/assets"
mkdir -p "$dir"
curl -L --fail --retry 3 -o "$dir/hand_landmarker.task" \
  https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task
test "$(wc -c < "$dir/hand_landmarker.task")" -gt 1000000
