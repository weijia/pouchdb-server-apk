#!/usr/bin/env bash
# 最小验证版：只把 main.js 复制到 assets（不安装 PouchDB 等依赖、不打 node_modules）。
# 仅为验证「Node 能否在 APK 内作为独立进程跑起来」，APK 极小、首次复制极快。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="${ROOT_DIR}/nodejs-project"
ASSETS_DIR="${ROOT_DIR}/app/src/main/assets/nodejs-project"

echo "[prepare-node-project] 复制 main.js 到 assets（最小验证版）..."
rm -rf "${ASSETS_DIR}"
mkdir -p "${ASSETS_DIR}"
cp "${SRC_DIR}/main.js" "${ASSETS_DIR}/"

# 构建标记：App 据此判断是否需要重新复制（升级 APK 后自动刷新）
STAMP="$(git -C "${ROOT_DIR}" rev-parse --short HEAD 2>/dev/null || date +%s)"
echo "${STAMP}" > "${ASSETS_DIR}/.buildstamp"

echo "[prepare-node-project] 完成，buildstamp=${STAMP}"
du -sh "${ASSETS_DIR}"
