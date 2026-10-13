cmake_minimum_required(VERSION 3.22.1)

project("pouchdb-server")

# JNI 桥接库：内部调用 libnode 导出的 node::Start()
add_library(node-bridge SHARED src/main/cpp/native-lib.cpp)

# 预编译的 nodejs-mobile 运行时，由 scripts/fetch-nodejs-mobile.sh 放到 jniLibs
add_library(libnode SHARED IMPORTED)
set_target_properties(libnode PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI}/libnode.so)

find_library(log-lib log)

target_link_libraries(node-bridge libnode ${log-lib})
