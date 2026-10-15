package net.weijia.pouchdbserver;

import android.util.Log;

/**
 * nodejs-mobile 的 JNI 桥：加载 libnode-bridge.so（它依赖 libnode.so），
 * 在【专用线程】上调 node::Start() 跑 main.js。
 *
 * 与上一版"另起可执行文件当独立进程"不同，这是 nodejs-mobile 官方插件的姿势：
 * 进程内嵌 Node。崩溃隔离改由 Android 进程提供——服务跑在 :node 进程（见 manifest）。
 *
 * 异常退出防护：native 侧在 node::Start 返回（Node 结束）时会回调 {@link #onNodeExit}，
 * Java 侧据此做自愈重启，避免服务"静默死掉"。
 */
public final class NodeBridge {

    private static final String TAG = "NodeBridge";

    static {
        // libnode-bridge.so 的 DT_NEEDED 里有 libnode.so，链接器会自动一起加载
        System.loadLibrary("node-bridge");
    }

    private NodeBridge() {
    }

    /** Node 线程退出（node::Start 返回）时的回调。 */
    public interface NodeExitListener {
        void onNodeExit(int code);
    }

    private static volatile NodeExitListener exitListener;

    public static void setNodeExitListener(NodeExitListener listener) {
        exitListener = listener;
    }

    public static void clearNodeExitListener() {
        exitListener = null;
    }

    /**
     * 由 native 在 Node 专用线程上调用（即 node::Start 返回后）。
     * 这里只做转发，异常一律吞掉，绝不让回调把 Node 线程再搞崩。
     */
    public static void onNodeExit(int code) {
        NodeExitListener listener = exitListener;
        if (listener == null) {
            Log.w(TAG, "Node 已退出 code=" + code + "，但未注册 exitListener");
            return;
        }
        try {
            listener.onNodeExit(code);
        } catch (Throwable t) {
            Log.e(TAG, "onNodeExit 回调异常（已忽略）", t);
        }
    }

    /**
     * @param argv     传给 node::Start 的参数，约定 argv[0]="node"，argv[1]=脚本路径
     * @param tmpDir   用作 TMPDIR（Android 无 /tmp，官方插件指向 CacheDir）
     * @param nodePath 用作 NODE_PATH，供 require() 解析 node_modules
     */
    public static native void startNode(String[] argv, String tmpDir, String nodePath);
}
