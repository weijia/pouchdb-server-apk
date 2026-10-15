#!/usr/bin/env bash
# 安装 node 工程依赖（PouchDB + express-pouchdb），并复制到 app/src/main/assets/nodejs-project/。
# Node 无法直接执行 assets 中的脚本，App 首次启动时会把它复制到私有目录再运行。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="${ROOT_DIR}/nodejs-project"
ASSETS_DIR="${ROOT_DIR}/app/src/main/assets/nodejs-project"

echo "[prepare-node-project] 安装依赖..."
cd "${SRC_DIR}"
if [[ -f package-lock.json ]]; then
  npm ci --omit=dev --no-audit --no-fund
else
  npm install --omit=dev --no-audit --no-fund
fi

echo "[prepare-node-project] 复制到 assets..."
rm -rf "${ASSETS_DIR}"
mkdir -p "${ASSETS_DIR}"
cp package.json main.js storage.js "${ASSETS_DIR}/"
cp -R node_modules "${ASSETS_DIR}/node_modules"

# 构建标记：App 据此判断是否需要重新复制（升级 APK 后自动刷新）。
# 注意文件名不要用点开头（如 .buildstamp）：aapt 打包 assets 时不一定收录点文件，
# 取不到标记会导致 App 每次启动都重拷 node_modules。
STAMP="$(git -C "${ROOT_DIR}" rev-parse --short HEAD 2>/dev/null || date +%s)"
echo "${STAMP}" > "${ASSETS_DIR}/buildstamp"

echo "[prepare-node-project] 完成，buildstamp=${STAMP}"
du -sh "${ASSETS_DIR}"
