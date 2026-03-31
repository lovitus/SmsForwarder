#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSET_ROOT="${ROOT_DIR}/app/src/main/assets/frpc"
JNILIB_ROOT="${ROOT_DIR}/app/src/main/jniLibs_custom"
META_ROOT="${ROOT_DIR}/build/custom_frpc_meta"
FRP_REPO="${FRP_REPO:-https://github.com/lovitus/frp.git}"
FRP_REF="${FRP_REF:-v0.68.1-mix.26}"
WITH_FRPC_EXEC_FALLBACK="${WITH_FRPC_EXEC_FALLBACK:-false}"
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

rm -rf "${ASSET_ROOT}"
rm -rf "${META_ROOT}"
mkdir -p "${META_ROOT}"
if [[ "${WITH_FRPC_EXEC_FALLBACK}" == "true" ]]; then
  mkdir -p "${ASSET_ROOT}/armeabi-v7a" "${ASSET_ROOT}/arm64-v8a" "${ASSET_ROOT}/x86" "${ASSET_ROOT}/x86_64"
fi
rm -rf "${JNILIB_ROOT}"
mkdir -p "${JNILIB_ROOT}/armeabi-v7a" "${JNILIB_ROOT}/arm64-v8a" "${JNILIB_ROOT}/x86" "${JNILIB_ROOT}/x86_64"

echo "Cloning ${FRP_REPO} (${FRP_REF})"
git clone --depth 1 --branch "${FRP_REF}" "${FRP_REPO}" "${WORK_DIR}/frp-src"
FRP_COMMIT="$(git -C "${WORK_DIR}/frp-src" rev-parse HEAD)"
FRP_VERSION_OVERRIDE="${FRP_VERSION_OVERRIDE:-}"
if [[ -z "${FRP_VERSION_OVERRIDE}" && "${FRP_REF}" == v* ]]; then
  FRP_VERSION_OVERRIDE="${FRP_REF#v}"
fi

FRP_LDFLAGS="-s -w"
if [[ -n "${FRP_VERSION_OVERRIDE}" ]]; then
  FRP_LDFLAGS="${FRP_LDFLAGS} -X github.com/fatedier/frp/pkg/util/version.version=${FRP_VERSION_OVERRIDE}"
fi

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
        go build -trimpath -ldflags "${FRP_LDFLAGS}" -tags "frpc,noweb" -o "${JNILIB_ROOT}/${abi}/libfrpc.so" ./cmd/frpc
    else
      CGO_ENABLED=0 GOOS="${goos}" GOARCH="${goarch}" \
        go build -trimpath -ldflags "${FRP_LDFLAGS}" -tags "frpc,noweb" -o "${JNILIB_ROOT}/${abi}/libfrpc.so" ./cmd/frpc
    fi
  )
  chmod +x "${JNILIB_ROOT}/${abi}/libfrpc.so"
  if [[ "${WITH_FRPC_EXEC_FALLBACK}" == "true" ]]; then
    cp "${JNILIB_ROOT}/${abi}/libfrpc.so" "${ASSET_ROOT}/${abi}/frpc"
    chmod +x "${ASSET_ROOT}/${abi}/frpc"
  fi
}

# 从指定分支源码编译 4 架构（使用静态 linux 目标，兼容 Android 运行环境）
build_frpc "arm64-v8a" "linux" "arm64"
build_frpc "armeabi-v7a" "linux" "arm" "7"
build_frpc "x86_64" "linux" "amd64"
build_frpc "x86" "linux" "386"

compress_frpc_asset() {
  local abi="$1"
  local raw_file="${ASSET_ROOT}/${abi}/frpc"
  local compressed_file="${ASSET_ROOT}/${abi}/frpc.bin"
  gzip -n -9 -c "${raw_file}" > "${compressed_file}"
  rm -f "${raw_file}"
  echo "Packed ${abi} -> $(basename "${compressed_file}")"
}

if [[ "${WITH_FRPC_EXEC_FALLBACK}" == "true" ]]; then
  compress_frpc_asset "arm64-v8a"
  compress_frpc_asset "armeabi-v7a"
  compress_frpc_asset "x86_64"
  compress_frpc_asset "x86"
fi

if [[ "${WITH_FRPC_EXEC_FALLBACK}" == "true" ]] && command -v sha256sum >/dev/null 2>&1; then
  (
    cd "${ASSET_ROOT}"
    sha256sum armeabi-v7a/frpc.bin arm64-v8a/frpc.bin x86/frpc.bin x86_64/frpc.bin > SHA256SUMS.txt
  )
fi

cat > "${META_ROOT}/BUILD_INFO.txt" <<EOF
frp_repo=${FRP_REPO}
frp_ref=${FRP_REF}
frp_commit=${FRP_COMMIT}
frp_version_override=${FRP_VERSION_OVERRIDE}
with_frpc_exec_fallback=${WITH_FRPC_EXEC_FALLBACK}
source_tree=https://github.com/lovitus/frp/tree/${FRP_REF}
generated_at_utc=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
EOF

if [[ "${WITH_FRPC_EXEC_FALLBACK}" == "true" ]]; then
  cp "${META_ROOT}/BUILD_INFO.txt" "${ASSET_ROOT}/BUILD_INFO.txt"
else
  rm -rf "${ASSET_ROOT}"
fi

if [[ "${WITH_FRPC_EXEC_FALLBACK}" != "true" ]]; then
  : > "${META_ROOT}/SHA256SUMS.txt"
elif [[ ! -f "${ASSET_ROOT}/SHA256SUMS.txt" ]]; then
  : > "${META_ROOT}/SHA256SUMS.txt"
else
  cp "${ASSET_ROOT}/SHA256SUMS.txt" "${META_ROOT}/SHA256SUMS.txt"
fi

echo "Prepared asset files:"
find "${ASSET_ROOT}" -maxdepth 2 -type f 2>/dev/null | sort || true

echo "Prepared native binary files:"
find "${JNILIB_ROOT}" -maxdepth 2 -type f | sort

echo "Custom frpc metadata prepared at ${META_ROOT}"
