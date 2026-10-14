cmake_minimum_required(VERSION 3.22.1)

project("pouchdb-server")

# 独立进程启动器：链接 nodejs-mobile 的 libnode.so，调用 node::Start() 跑 main.js。
# 编成可执行文件，再由 POST_BUILD 复制为 libpouchdb.so 放进 jniLibs/<abi>/，
# 这样 Gradle 把它和 libnode.so 一起作为原生库打包进 APK 的 lib/<abi>/，
# 安装后落在 nativeLibraryDir（该目录有 app_library_file SELinux 标签，允许执行）。
# 用 jniLibs 而非依赖 cmake 输出打包，可避免“同名文件既在 cmake 输出又在 jniLibs”的冲突。
add_executable(pouchdb src/main/cpp/launcher.cpp)

# 预编译的 nodejs-mobile 运行时，由 scripts/fetch-nodejs-mobile.sh 放到 jniLibs
add_library(libnode SHARED IMPORTED)
set_target_properties(libnode PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI}/libnode.so)

find_library(log-lib log)

target_link_libraries(pouchdb libnode ${log-lib})

# 把可执行文件以 libpouchdb.so 之名塞进 jniLibs，供 Gradle 打包
add_custom_command(TARGET pouchdb POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy
                $<TARGET_FILE:pouchdb>
                ${CMAKE_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI}/libpouchdb.so)
