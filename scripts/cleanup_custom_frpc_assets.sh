#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSET_ROOT="${ROOT_DIR}/app/src/main/assets/frpc"
JNILIB_ROOT="${ROOT_DIR}/app/src/main/jniLibs_custom"
META_ROOT="${ROOT_DIR}/build/custom_frpc_meta"

rm -rf "${ASSET_ROOT}"
rm -rf "${JNILIB_ROOT}"
rm -rf "${META_ROOT}"
echo "Removed ${ASSET_ROOT}"
echo "Removed ${JNILIB_ROOT}"
echo "Removed ${META_ROOT}"
