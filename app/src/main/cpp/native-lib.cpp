#include <jni.h>
#include <string>
#include <stdlib.h>
#include <stdio.h>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>

// libnode.so 导出的入口，启动 Node 运行时（阻塞直到 Node 退出）
namespace node {
    extern int Start(int argc, char *argv[]);
}

extern "C" int startNode(int argc, char *argv[]) {
    return node::Start(argc, argv);
}

static const char *TAG = "PouchDBServer";

// 可选：把 node 的 stdout/stderr 同时落盘到文件，便于崩溃排查
static FILE *g_logfp = NULL;

int start_redirecting_stdout_stderr();

extern "C"
JNIEXPORT jint JNICALL
Java_net_weijia_pouchdbserver_NodeBridge_startNodeWithArguments(
        JNIEnv *env,
        jobject /* this */,
        jobjectArray arguments,
        jstring nodePath,
        jboolean redirectOutputToLogcat,
        jstring logFile) {

    // 若传入日志文件路径，则打开追加写入；node 输出会同时写 logcat 与该文件
    if (logFile != NULL) {
        const char *lf = env->GetStringUTFChars(logFile, 0);
        g_logfp = fopen(lf, "a");
        if (g_logfp == NULL) {
            __android_log_print(ANDROID_LOG_WARN, TAG, "无法打开日志文件: %s", lf);
        }
        env->ReleaseStringUTFChars(logFile, lf);
    }

    // Node 的 libuv 要求参数位于连续内存
    const char *path = env->GetStringUTFChars(nodePath, 0);
    setenv("NODE_PATH", path, 1);
    env->ReleaseStringUTFChars(nodePath, path);

    jsize argc = env->GetArrayLength(arguments);

    int c_arguments_size = 0;
    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring) env->GetObjectArrayElement(arguments, i);
        const char *str = env->GetStringUTFChars(arg, 0);
        c_arguments_size += strlen(str) + 1; // + '\0'
        env->ReleaseStringUTFChars(arg, str);
    }

    char *args_buffer = (char *) calloc(c_arguments_size, sizeof(char));
    char **argv = (char **) calloc(argc, sizeof(char *));

    char *current_args_position = args_buffer;
    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring) env->GetObjectArrayElement(arguments, i);
        const char *str = env->GetStringUTFChars(arg, 0);
        strncpy(current_args_position, str, strlen(str));
        argv[i] = current_args_position;
        current_args_position += strlen(str) + 1;
        env->ReleaseStringUTFChars(arg, str);
    }

    if (redirectOutputToLogcat == JNI_TRUE || g_logfp != NULL) {
        if (start_redirecting_stdout_stderr() == -1) {
            __android_log_write(ANDROID_LOG_ERROR, TAG,
                                "Couldn't start redirecting stdout and stderr to logcat.");
        }
    }

    int result = startNode(argc, argv);

    free(args_buffer);
    free(argv);

    __android_log_print(ANDROID_LOG_INFO, TAG, "node exited with code %d", result);
    if (g_logfp != NULL) {
        fflush(g_logfp);
        fclose(g_logfp);
        g_logfp = NULL;
    }
    return jint(result);
}

/**
 * 把 stdout / stderr 重定向到 logcat（以及可选文件）
 */
int pipe_stdout[2];
int pipe_stderr[2];
pthread_t thread_stdout;
pthread_t thread_stderr;

void redirect(int pipe, int log_level) {
    ssize_t redirect_size;
    char buf[10240];
    while ((redirect_size = read(pipe, buf, sizeof buf - 1)) > 0) {
        if (buf[redirect_size - 1] == '\n') {
            --redirect_size;
        }
        buf[redirect_size] = 0;
        __android_log_write(log_level, TAG, buf);
        if (g_logfp != NULL) {
            fputs(buf, g_logfp);
            fputc('\n', g_logfp);
            fflush(g_logfp);
        }
    }
}

void *thread_stderr_func(void *) {
    redirect(pipe_stderr[0], ANDROID_LOG_ERROR);
    return 0;
}

void *thread_stdout_func(void *) {
    redirect(pipe_stdout[0], ANDROID_LOG_INFO);
    return 0;
}

int start_redirecting_stdout_stderr() {
    setvbuf(stdout, 0, _IOLBF, 0);
    pipe(pipe_stdout);
    dup2(pipe_stdout[1], STDOUT_FILENO);

    setvbuf(stderr, 0, _IOLBF, 0);
    pipe(pipe_stderr);
    dup2(pipe_stderr[1], STDERR_FILENO);

    if (pthread_create(&thread_stdout, 0, thread_stdout_func, 0) == -1) {
        return -1;
    }
    pthread_detach(thread_stdout);

    if (pthread_create(&thread_stderr, 0, thread_stderr_func, 0) == -1) {
        return -1;
    }
    pthread_detach(thread_stderr);

    return 0;
}
