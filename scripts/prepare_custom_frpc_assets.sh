#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSET_ROOT="${ROOT_DIR}/app/src/main/assets/frpc"
FRP_REPO="${FRP_REPO:-https://github.com/lovitus/frp.git}"
FRP_REF="${FRP_REF:-codex/mix-transport-release}"
WORK_DIR="$(mktemp -d)"

cleanup() {
  rm -rf "${WORK_DIR}"
}
trap cleanup EXIT

if ! command -v git >/dev/null 2>&1; then
  echo "git is required" >&2
  exit 1
fi

if ! command -v go >/dev/null 2>&1; then
  echo "go is required" >&2
  exit 1
fi

mkdir -p "${ASSET_ROOT}"
rm -rf "${ASSET_ROOT}"
mkdir -p "${ASSET_ROOT}/armeabi-v7a" "${ASSET_ROOT}/arm64-v8a" "${ASSET_ROOT}/x86" "${ASSET_ROOT}/x86_64"

echo "Cloning ${FRP_REPO} (${FRP_REF})"
git clone --depth 1 --branch "${FRP_REF}" "${FRP_REPO}" "${WORK_DIR}/frp-src"
FRP_COMMIT="$(git -C "${WORK_DIR}/frp-src" rev-parse HEAD)"

build_frpc() {
  local abi="$1"
  local goos="$2"
  local goarch="$3"
  local goarm="${4:-}"

  echo "Building frpc for ${abi} (${goos}/${goarch}${goarm:+ GOARM=${goarm}})"
  (
    cd "${WORK_DIR}/frp-src"
    if [[ -n "${goarm}" ]]; then
      CGO_ENABLED=0 GOOS="${goos}" GOARCH="${goarch}" GOARM="${goarm}" \
        go build -trimpath -ldflags "-s -w" -tags "frpc,noweb" -o "${ASSET_ROOT}/${abi}/frpc" ./cmd/frpc
    else
      CGO_ENABLED=0 GOOS="${goos}" GOARCH="${goarch}" \
        go build -trimpath -ldflags "-s -w" -tags "frpc,noweb" -o "${ASSET_ROOT}/${abi}/frpc" ./cmd/frpc
    fi
  )
  chmod +x "${ASSET_ROOT}/${abi}/frpc"
}

# 从指定分支源码编译 4 架构（使用静态 linux 目标，兼容 Android 运行环境）
build_frpc "arm64-v8a" "linux" "arm64"
build_frpc "armeabi-v7a" "linux" "arm" "7"
build_frpc "x86_64" "linux" "amd64"
build_frpc "x86" "linux" "386"

if command -v sha256sum >/dev/null 2>&1; then
  (
    cd "${ASSET_ROOT}"
    sha256sum armeabi-v7a/frpc arm64-v8a/frpc x86/frpc x86_64/frpc > SHA256SUMS.txt
  )
fi

cat > "${ASSET_ROOT}/BUILD_INFO.txt" <<EOF
frp_repo=${FRP_REPO}
frp_ref=${FRP_REF}
frp_commit=${FRP_COMMIT}
source_tree=https://github.com/lovitus/frp/tree/${FRP_REF}
generated_at_utc=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
EOF

echo "Custom frpc assets prepared at ${ASSET_ROOT}"
