#!/bin/sh
# Fetches the native dependencies (git submodules of Telegram) at the exact commits Solegram is built with.
set -e
cd "$(dirname "$0")"
grep -v '^#' SUBMODULES.txt | while read -r path url commit; do
  [ -z "$path" ] && continue
  if [ ! -d "$path/.git" ] && [ ! -f "$path/.git" ]; then
    rm -rf "$path"
    git clone --filter=blob:none "$url" "$path"
  fi
  git -C "$path" fetch --depth 1 origin "$commit" 2>/dev/null || git -C "$path" fetch origin
  git -C "$path" checkout -q "$commit"
  git -C "$path" submodule update --init --recursive -q || true
  echo "ok  $path"
done
