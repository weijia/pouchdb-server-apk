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
# 设备实测失败：
#   dlopen failed: cannot locate symbol "napi_get_global" referenced by
#   .../leveldown/prebuilds/android-arm64/node.napi.armv8.node
# 根因：leveldown 随包发布的 Android 预编译 .node 只声明依赖 liblog / libc++_shared /
# libm / libdl / libc，没有把 libnode.so 写进 DT_NEEDED。Android 链接器不会把已加载
# libnode 的符号暴露给之后 dlopen 的原生模块，因此 napi_* 全部解析不到。
# nodejs-mobile 官方的 common.gypi 正因如此强制把 libnode.so 链接进每个原生模块
# （原文注释：Android needs to have a (NEEDED) entry for libnode.so, or it won't be
# able to locate referenced symbols）。
#
# 修复：给预编译产物补上 DT_NEEDED=libnode.so（其 SONAME 已确认为 libnode.so），
# 加载时即可从应用已加载的 libnode.so 解析 napi_* 符号。仅改写 ELF 动态节，不改动
# 指令与架构；N-API 依旧 ABI 稳定，与本机/设备的 Node 版本差异无关。
#
# 切勿再把 .node 复制到 node_modules/leveldown/build/Release/：node-gyp-build 会
# 优先加载 build/Release，一旦那里放了宿主二进制（如 linux-x64/armv7），设备上必然
# 加载失败（ELF 架构/ABI 不符）。让它自然回落到 prebuilds/ 目录才是正确路径。

ensure_patchelf() {
  command -v patchelf >/dev/null 2>&1 && return 0
  echo "[prepare-node-project] 未检测到 patchelf，尝试安装..."
  if command -v apt-get >/dev/null 2>&1; then
    sudo apt-get update -qq >/dev/null 2>&1 || true
    sudo apt-get install -y -qq patchelf >/dev/null 2>&1 || true
  fi
  if ! command -v patchelf >/dev/null 2>&1; then
    if command -v pip3 >/dev/null 2>&1; then
      pip3 install --quiet --break-system-packages patchelf >/dev/null 2>&1 \
        || pip3 install --quiet --user patchelf >/dev/null 2>&1 || true
      export PATH="$(python3 -m site --user-base 2>/dev/null || echo "${HOME}/.local")/bin:${PATH}"
    fi
  fi
  command -v patchelf >/dev/null 2>&1 || {
    echo "[prepare-node-project] 错误: 需要 patchelf 但无法安装，预编译产物无法修复"; exit 1; }
}

patch_leveldown_binding() {
  local f="$1"
  [[ -f "${f}" ]] || return 0
  if patchelf --print-needed "${f}" | grep -qx 'libnode.so'; then
    echo "[prepare-node-project] 已含 libnode.so 依赖: ${f}"
    return 0
  fi
  patchelf --add-needed libnode.so "${f}"
  echo "[prepare-node-project] 已补上 DT_NEEDED=libnode.so: ${f}"
}

echo "[prepare-node-project] 修复 leveldown Android 预编译产物的 libnode.so 依赖..."
ensure_patchelf
for f in node_modules/leveldown/prebuilds/android-arm64/*.node \
         node_modules/leveldown/prebuilds/android-arm/*.node; do
  patch_leveldown_binding "${f}"
done

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
