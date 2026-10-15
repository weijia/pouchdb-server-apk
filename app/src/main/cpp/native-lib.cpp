// JNI 桥接：照 nodejs-mobile 官方（nodejs-mobile-react-native）的姿势实现——
// 把 Node 作为共享库嵌入 App 进程，由【专用线程】调用 node::Start() 跑 main.js。
//
// 与上一版"另起一个可执行文件当独立进程（libpouchdb.so + ProcessBuilder）"的差别：
//   1) 这是社区验证过的正式姿势，官方插件就是这么做的；
//   2) 不再依赖 nativeLibraryDir 里文件的执行权限 / SELinux app_library_file 标签，
//      Android 10+ 的 W^X（data 目录禁止执行）限制也不再适用，
//      因此不会出现"启动器起不来、连 hello world 都打不出来"的静默失败；
//   3) 崩溃隔离改由 Android 进程提供：服务跑在 :node 进程（见 AndroidManifest），
//      Node 崩了只死该进程，界面进程不受影响，且服务可被系统重启。
//
// 关键修正：官方插件会显式把 TMPDIR 设为 App 的 CacheDir（Android 没有 /tmp）。
// 我们之前从来没设过，Node/V8 初始化阶段很可能因此静默失败——这次补上。
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>

#define LOG_TAG "NodeBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace node {
extern int Start(int argc, char* argv[]);
}

static char** g_argv = nullptr;
static int g_argc = 0;

// 缓存 JavaVM 与 NodeBridge 类的全局引用：Node 专用线程结束（node::Start 返回）时
// 需要从 native 回调 Java 侧做自愈。该线程不是 JVM 已知线程，必须先 AttachCurrentThread。
static JavaVM* g_vm = nullptr;
static jclass g_bridge_class = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_VERSION_1_6;
    }
    jclass local = env->FindClass("net/weijia/pouchdbserver/NodeBridge");
    if (local != nullptr) {
        g_bridge_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
    } else {
        LOGW("JNI_OnLoad: 未找到 NodeBridge 类，Node 退出回调不可用");
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    return JNI_VERSION_1_6;
}

// Node 线程退出后通知 Java 侧（触发自愈重启）。任何失败都只记日志，不影响收尾。
static void notify_node_exit(int code) {
    if (g_vm == nullptr || g_bridge_class == nullptr) {
        return;
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    // 注意 NDK 的 jni.h 里这两个包装函数签名并不一致：
    // GetEnv 取 void**，AttachCurrentThread 取 JNIEnv**（别硬转 void**，会编译不过）。
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGW("notify_node_exit: AttachCurrentThread 失败");
            return;
        }
        attached = true;
    }
    jmethodID mid = env->GetStaticMethodID(g_bridge_class, "onNodeExit", "(I)V");
    if (mid != nullptr) {
        env->CallStaticVoidMethod(g_bridge_class, mid, static_cast<jint>(code));
    } else {
        LOGW("notify_node_exit: 未找到 onNodeExit(I)V");
    }
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    if (attached) {
        g_vm->DetachCurrentThread();
    }
}

static void* node_thread_entry(void*) {
    LOGI("node thread start: argc=%d argv0=%s", g_argc, (g_argv && g_argv[0]) ? g_argv[0] : "(null)");
    int code = node::Start(g_argc, g_argv);
    LOGW("node::Start 返回 code=%d", code);
    notify_node_exit(code);
    return nullptr;
}

extern "C"
JNIEXPORT void JNICALL
Java_net_weijia_pouchdbserver_NodeBridge_startNode(
        JNIEnv* env, jclass, jobjectArray argv, jstring tmpDir, jstring nodePath) {

    // TMPDIR：Android 无 /tmp，官方插件设为 App 的 CacheDir。缺它可能直接导致 Node 起不来。
    if (tmpDir != nullptr) {
        const char* tmp = env->GetStringUTFChars(tmpDir, nullptr);
        if (tmp != nullptr) {
            setenv("TMPDIR", tmp, 1);
            LOGI("setenv TMPDIR=%s", tmp);
            env->ReleaseStringUTFChars(tmpDir, tmp);
        }
    }

    // NODE_PATH：供 require() 解析 node_modules 模块
    if (nodePath != nullptr) {
        const char* np = env->GetStringUTFChars(nodePath, nullptr);
        if (np != nullptr) {
            setenv("NODE_PATH", np, 1);
            LOGI("setenv NODE_PATH=%s", np);
            env->ReleaseStringUTFChars(nodePath, np);
        }
    }

    if (argv == nullptr) {
        LOGE("argv 为空，无法启动 Node");
        return;
    }

    g_argc = env->GetArrayLength(argv);
    g_argv = (char**) calloc((size_t) g_argc + 1, sizeof(char*));
    for (int i = 0; i < g_argc; i++) {
        jstring item = (jstring) env->GetObjectArrayElement(argv, i);
        if (item == nullptr) {
            g_argv[i] = strdup("");
            continue;
        }
        const char* s = env->GetStringUTFChars(item, nullptr);
        g_argv[i] = strdup(s != nullptr ? s : "");
        if (s != nullptr) env->ReleaseStringUTFChars(item, s);
        env->DeleteLocalRef(item);
    }
    g_argv[g_argc] = nullptr;

    pthread_t tid;
    if (pthread_create(&tid, nullptr, node_thread_entry, nullptr) != 0) {
        LOGE("pthread_create 失败");
        return;
    }
    pthread_detach(tid);
    LOGI("已创建 Node 专用线程");
}
