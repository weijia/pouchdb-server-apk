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

# ===== 关于 leveldown（pouchdb-adapter-leveldb 依赖的原生模块）=====
# 不需要交叉编译，也不需要 NDK：leveldown 通过 node-gyp-build 加载，npm 包内
# 自带 Android 的 N-API 预编译产物（prebuilds/android-arm64/node.napi.armv8.node、
# prebuilds/android-arm/node.napi.armv7.node，由 leveldown 的 prebuild-android-arm
# 脚本用 prebuildify-cross --napi 产出）。N-API 是 ABI 稳定的，可直接在
# nodejs-mobile 的 libnode 上加载，宿主与设备端的 Node 版本差异不影响它。
#
# 切勿再把 .node 复制到 node_modules/leveldown/build/Release/：node-gyp-build 会
# 优先加载 build/Release，一旦那里放了宿主二进制（如 linux-x64/armv7），设备上必然
# 加载失败（ELF 架构/ABI 不符）。让它自然回落到 prebuilds/ 目录才是正确路径。

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
