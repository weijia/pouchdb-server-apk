#!/usr/bin/env bash
# 下载 nodejs-mobile 官方预编译的 Android 版 libnode.so，放到 app/src/main/jniLibs/<abi>/。
# 该目录被 .gitignore 忽略，构建前必须执行本脚本（CI 已集成）。
set -euo pipefail

VERSION="${NODEJS_MOBILE_VERSION:-v18.20.4}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_DIR="${ROOT_DIR}/app/src/main/jniLibs"
ABIS=("arm64-v8a" "armeabi-v7a")
TMP_DIR="$(mktemp -d)"
ZIP_NAME="nodejs-mobile-${VERSION}-android.zip"
URL="https://github.com/nodejs-mobile/nodejs-mobile/releases/download/${VERSION}/${ZIP_NAME}"

echo "[fetch-nodejs-mobile] 版本: ${VERSION}"
echo "[fetch-nodejs-mobile] 下载: ${URL}"

curl -fsSL -o "${TMP_DIR}/${ZIP_NAME}" "${URL}"
unzip -q -o "${TMP_DIR}/${ZIP_NAME}" -d "${TMP_DIR}/unzipped"

mkdir -p "${JNI_DIR}"
for abi in "${ABIS[@]}"; do
  src="$(find "${TMP_DIR}/unzipped" -type f -name 'libnode.so' -path "*${abi}*" | head -n 1 || true)"
  if [[ -z "${src}" ]]; then
    echo "[fetch-nodejs-mobile] 警告: 未找到 ${abi} 的 libnode.so"
    continue
  fi
  mkdir -p "${JNI_DIR}/${abi}"
  cp -f "${src}" "${JNI_DIR}/${abi}/libnode.so"
  echo "[fetch-nodejs-mobile] ${abi} <- ${src} ($(du -h "${JNI_DIR}/${abi}/libnode.so" | cut -f1))"
done

if [[ ! -f "${JNI_DIR}/arm64-v8a/libnode.so" ]]; then
  echo "[fetch-nodejs-mobile] 错误: arm64-v8a 的 libnode.so 缺失"
  exit 1
fi

rm -rf "${TMP_DIR}"
echo "[fetch-nodejs-mobile] 完成: ${JNI_DIR}"
