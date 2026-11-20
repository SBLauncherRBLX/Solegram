#!/bin/bash
# Solegram: off-device tests of the link cleaner and the metadata stripper (needs a JDK, python3 with Pillow, ffmpeg).
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"; SRC="$HERE/../../../TMessagesProj/src/main/java/com/solegram/messenger"
W="$(mktemp -d)"; trap 'rm -rf "${W:?}"' EXIT
mkdir -p "$W/cls" "$W/fx"
javac -d "$W/cls" "$SRC/SolegramLinkCleaner.java" "$SRC/SolegramMetadataStripper.java" "$HERE"/*.java
java -cp "$W/cls" LinkTest
(cd "$W/fx" && python3 "$HERE/make_fixtures.py")
java -cp "$W/cls" StripTest "$W"/fx/*
for f in "$W"/out/*; do n=$(grep -c -a -E 'SECRET|55\.7558|SamsungPhone|2026:11' "$f" || true); echo "$(basename "$f"): leftover marks $n"; [ "$n" = 0 ]; done
for v in video.mp4 video.mov; do ffmpeg -v error -i "$W/out/$v" -f null - && echo "$v decodes"; done
(cd "$W" && java -cp cls Fuzz fx/*)
echo PRIVACY_TESTS_OK
