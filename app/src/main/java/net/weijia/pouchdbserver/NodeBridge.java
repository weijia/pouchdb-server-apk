package net.weijia.pouchdbserver;

/**
 * nodejs-mobile 的 JNI 桥：加载 libnode-bridge.so（它依赖 libnode.so），
 * 在【专用线程】上调 node::Start() 跑 main.js。
 *
 * 与上一版"另起可执行文件当独立进程"不同，这是 nodejs-mobile 官方插件的姿势：
 * 进程内嵌 Node。崩溃隔离改由 Android 进程提供——服务跑在 :node 进程（见 manifest）。
 */
public final class NodeBridge {

    static {
        // libnode-bridge.so 的 DT_NEEDED 里有 libnode.so，链接器会自动一起加载
        System.loadLibrary("node-bridge");
    }

    private NodeBridge() {
    }

    /**
     * @param argv     传给 node::Start 的参数，约定 argv[0]="node"，argv[1]=脚本路径
     * @param tmpDir   用作 TMPDIR（Android 无 /tmp，官方插件指向 CacheDir）
     * @param nodePath 用作 NODE_PATH，供 require() 解析 node_modules
     */
    public static native void startNode(String[] argv, String tmpDir, String nodePath);
}
