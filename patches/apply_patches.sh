#!/usr/bin/env bash
# Optional helper to apply native Camera2Client & MediaProvider compatibility patches
# to a LineageOS 22.2 source tree.
# Usage (from root of LineageOS tree):
#   ./vendor/lge/joan-camera/patches/apply_patches.sh
set -euo pipefail
patch_dir="$(cd "$(dirname "$0")" && pwd)"
top="$(pwd)"

if [ -d "$top/frameworks/av" ]; then
  echo "[apply_patches] Checking frameworks/av patch..."
  git -C "$top/frameworks/av" apply --check "$patch_dir/02-frameworks-av-camera2client-lg-compat.patch" 2>/dev/null \
    && git -C "$top/frameworks/av" apply "$patch_dir/02-frameworks-av-camera2client-lg-compat.patch" \
    && echo "  -> Applied 02-frameworks-av-camera2client-lg-compat.patch" \
    || echo "  -> Skipped (already applied or custom frameworks/av)"
fi
