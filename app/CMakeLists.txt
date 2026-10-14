cmake_minimum_required(VERSION 3.22.1)

project("pouchdb-server")

# 官方 nodejs-mobile 姿势：把 Node 作为共享库嵌入 App 进程，由 JNI 在【专用线程】
# 上调 node::Start() 跑 main.js（nodejs-mobile-react-native 就是这么做的）。
# 与上一版"编成可执行文件 libpouchdb.so 再用 ProcessBuilder 拉起"的根本差别：
# 不再依赖 nativeLibraryDir 的执行权限 / SELinux 标签，避开 Android 10+ 的 W^X 限制。
add_library(node-bridge SHARED src/main/cpp/native-lib.cpp)

# 预编译的 nodejs-mobile 运行时，由 scripts/fetch-nodejs-mobile.sh 放到 jniLibs
add_library(libnode SHARED IMPORTED)
set_target_properties(libnode PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI}/libnode.so)

find_library(log-lib log)

target_link_libraries(node-bridge libnode ${log-lib})
