#!/usr/bin/env bash
# 安装 node 工程依赖（PouchDB + express-pouchdb），并复制到 app/src/main/assets/nodejs-project/。
# Node 无法直接执行 assets 中的脚本，App 首次启动时会把它复制到私有目录再运行。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="${ROOT_DIR}/nodejs-project"
ASSETS_DIR="${ROOT_DIR}/app/src/main/assets/nodejs-project"

echo "[prepare-node-project] 安装依赖..."
cd "${SRC_DIR}"
# 注意：不能用 npm ci，否则 package.json 与 package-lock.json 不一致会直接失败。
# 改为 npm install 让其自动更新 lock。
npm install --omit=dev --no-audit --no-fund

# ===== 交叉编译 leveldown（nodejs-mobile / Android）=====
# 关键陷阱：宿主是 Node 20，但运行时 libnode 是 nodejs-mobile v18.20.4，ABI 不同。
# 必须用 prebuild-for-nodejs-mobile 针对 v18.20.4 的 headers 交叉编译，
# 否则编出的 .node 是 Node 20 标准 ABI，放进 nodejs-mobile 会加载失败。
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
export ANDROID_SDK_ROOT="${ANDROID_HOME}"
export ANDROID_NDK_HOME="${ANDROID_HOME}/ndk/25.2.9519653"

if [ -d node_modules/leveldown ]; then
  echo "[prepare-node-project] 交叉编译 leveldown (nodejs-mobile v18.20.4) ..."
  for arch in android-arm64 android-arm; do
    echo "[prepare-node-project]   -> ${arch}"
    ( cd node_modules/leveldown && npx --yes "prebuild-for-nodejs-mobile@18.20.4" "${arch}" --sdk34 --verbose )
  done
  # 兜底：把编出的 .node 也放到 build/Release/leveldown.node，
  # 这样即便 prebuild 在运行时的解析失败，require('leveldown') 也能回退到这里加载。
  BUILT="$(find node_modules/leveldown/prebuilds -name '*.node' 2>/dev/null | head -n1)"
  if [ -n "${BUILT}" ]; then
    mkdir -p node_modules/leveldown/build/Release
    cp "${BUILT}" node_modules/leveldown/build/Release/leveldown.node
    echo "[prepare-node-project] 已复制 ${BUILT} -> build/Release/leveldown.node"
  else
    echo "[prepare-node-project] 警告: 未找到编出的 .node，leveldown 可能无法加载"
  fi
else
  echo "[prepare-node-project] 未找到 node_modules/leveldown，跳过 leveldown 编译"
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
