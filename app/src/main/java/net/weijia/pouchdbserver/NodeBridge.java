package net.weijia.pouchdbserver;

/**
 * JNI 桥接：启动 nodejs-mobile 运行时。
 * native 实现见 app/src/main/cpp/native-lib.cpp，
 * 内部调用 libnode.so 导出的 node::Start()。
 */
public final class NodeBridge {

    static {
        // node-bridge 依赖 libnode.so，系统会自动加载同目录下的依赖库
        System.loadLibrary("node-bridge");
    }

    private NodeBridge() {
    }

    /**
     * 启动 Node，阻塞直到 Node 退出。必须在后台线程调用。
     *
     * @param arguments                 命令行参数，例如 {"node", "/path/to/main.js", "--port=5984"}
     * @param nodePath                  NODE_PATH，指向 App 私有目录
     * @param redirectOutputToLogcat    是否把 stdout/stderr 重定向到 logcat
     * @return Node 进程的退出码
     */
    public static native int startNodeWithArguments(
            String[] arguments,
            String nodePath,
            boolean redirectOutputToLogcat,
            String logFilePath);
}
