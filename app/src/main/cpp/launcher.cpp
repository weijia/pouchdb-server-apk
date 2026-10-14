// 独立进程启动器：替代原来的 JNI 内联方案。
// 链接 nodejs-mobile 的 libnode.so，在独立进程里调用 node::Start() 跑 main.js，
// 这样 Node 运行时与 App/前台服务进程隔离——Node 崩了也不会拖死整个 App
// （同 go-stock-server 用 libgoss.so + ProcessBuilder 跑独立进程的思路）。
//
// 编译为 libpouchdb.so 放进 jniLibs，安装后落在 nativeLibraryDir，该目录有
// app_library_file SELinux 标签，允许执行（filesDir 下二进制在 Android 10+ 被禁止执行）。
#include <stdlib.h>

namespace node {
extern int Start(int argc, char* argv[]);
}

int main(int argc, char* argv[]) {
    // argv[0] 为启动器自身路径；node::Start 把 argv[1] 当作要执行的脚本，
    // 因此直接把原始 argv 透传即可（无需像 JNI 那样手动塞一个 "node"）。
    return node::Start(argc, argv);
}
