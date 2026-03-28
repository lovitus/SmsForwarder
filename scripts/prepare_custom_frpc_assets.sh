#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSET_ROOT="${ROOT_DIR}/app/src/main/assets/frpc"
FRP_TAG="${FRP_TAG:-v0.68.1-mix.19}"
FRP_VERSION="${FRP_TAG#v}"
WORK_DIR="$(mktemp -d)"

cleanup() {
  rm -rf "${WORK_DIR}"
}
trap cleanup EXIT

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required" >&2
  exit 1
fi

if ! command -v tar >/dev/null 2>&1; then
  echo "tar is required" >&2
  exit 1
fi

mkdir -p "${ASSET_ROOT}"
rm -rf "${ASSET_ROOT}"
mkdir -p "${ASSET_ROOT}/armeabi-v7a" "${ASSET_ROOT}/arm64-v8a" "${ASSET_ROOT}/x86" "${ASSET_ROOT}/x86_64"

download_and_extract_frpc() {
  local archive_name="$1"
  local abi="$2"
  local archive_path="${WORK_DIR}/${archive_name}"
  local extract_dir="${WORK_DIR}/extract_${abi}"
  local download_url="https://github.com/lovitus/frp/releases/download/${FRP_TAG}/${archive_name}"

  echo "Downloading ${download_url}"
  curl -fsSL "${download_url}" -o "${archive_path}"
  mkdir -p "${extract_dir}"
  tar -xzf "${archive_path}" -C "${extract_dir}"

  local frpc_bin
  frpc_bin="$(find "${extract_dir}" -type f -name frpc | head -n 1)"
  if [[ -z "${frpc_bin}" ]]; then
    echo "frpc binary not found in ${archive_name}" >&2
    exit 1
  fi

  cp "${frpc_bin}" "${ASSET_ROOT}/${abi}/frpc"
  chmod +x "${ASSET_ROOT}/${abi}/frpc"
}

# arm64-v8a: 官方 Android 构建
download_and_extract_frpc "frp_${FRP_VERSION}_android_arm64.tar.gz" "arm64-v8a"
# armeabi-v7a: 使用静态 linux arm 构建
download_and_extract_frpc "frp_${FRP_VERSION}_linux_arm.tar.gz" "armeabi-v7a"
# x86_64: 使用静态 linux amd64 构建
download_and_extract_frpc "frp_${FRP_VERSION}_linux_amd64.tar.gz" "x86_64"

# x86: lovitus 发布未提供，按同一tag从源码交叉编译
if ! command -v go >/dev/null 2>&1; then
  echo "go is required to build x86 frpc binary" >&2
  exit 1
fi

echo "Building x86 frpc from source tag ${FRP_TAG}"
git clone --depth 1 --branch "${FRP_TAG}" https://github.com/lovitus/frp.git "${WORK_DIR}/frp-src"
(
  cd "${WORK_DIR}/frp-src"
  CGO_ENABLED=0 GOOS=linux GOARCH=386 go build -trimpath -ldflags "-s -w" -tags "frpc,noweb" -o "${ASSET_ROOT}/x86/frpc" ./cmd/frpc
)
chmod +x "${ASSET_ROOT}/x86/frpc"

if command -v sha256sum >/dev/null 2>&1; then
  (
    cd "${ASSET_ROOT}"
    sha256sum armeabi-v7a/frpc arm64-v8a/frpc x86/frpc x86_64/frpc > SHA256SUMS.txt
  )
fi

cat > "${ASSET_ROOT}/BUILD_INFO.txt" <<EOF
frp_tag=${FRP_TAG}
frp_version=${FRP_VERSION}
source_release=https://github.com/lovitus/frp/releases/tag/${FRP_TAG}
generated_at_utc=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
EOF

echo "Custom frpc assets prepared at ${ASSET_ROOT}"
