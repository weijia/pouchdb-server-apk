package net.weijia.pouchdbserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import net.weijia.pouchdbserver.MdnsAdvertiser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 最小验证版前台服务：把 assets 里的 node 工程复制到私有目录，然后按
 * nodejs-mobile 官方姿势，用 JNI 在【专用线程】上调 node::Start() 跑 main.js。
 *
 * 本服务跑在独立 Android 进程（manifest 里 android:process=":node"），
 * 因此 Node 崩溃只结束该进程，界面进程不受影响，服务还可被系统重启。
 *
 * 与上一版的关键差别：不再用 ProcessBuilder 另起可执行文件当独立进程。
 * 那个做法依赖 nativeLibraryDir 的执行权限/SELinux 标签，且漏设了 TMPDIR，
 * 是"连 hello world 都打不出来"的最大嫌疑；现在改回官方验证过的进程内嵌姿势。
 */
public class NodeService extends Service {

    private static final String TAG = "NodeService";
    private static final String CHANNEL_ID = App.CHANNEL_ID;
    private static final int NOTIFICATION_ID = 5984;

    public static final String ACTION_START = "net.weijia.pouchdbserver.action.START";
    public static final String ACTION_STOP = "net.weijia.pouchdbserver.action.STOP";
    public static final String ACTION_STATE = "net.weijia.pouchdbserver.action.STATE";

    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_NODE_UP = "node_up";
    public static final String EXTRA_MESSAGE = "message";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MdnsAdvertiser advertiser;
    private final Prefs prefs = new Prefs(this);

    private boolean nodeStarted;
    private volatile boolean starting;
    private volatile boolean stopping;
    private long startAtMs;

    // ==================== 异常退出防护（自愈）====================
    // Node 现在按官方姿势进程内嵌在 :node 进程里，node::Start 一旦返回（Node 崩溃/退出）
    // 或 Node 卡死（心跳停止），都必须能被发现并自愈重启，否则服务会"静默死掉"。
    //   1) native 侧在 node::Start 返回时回调 NodeBridge.onNodeExit；
    //   2) Java 侧 Watchdog 周期检查 Node 写出的 heartbeat 文件是否过期。
    // 两条路都汇聚到 handleNodeDown()：结束当前 :node 进程，交给系统按 START_STICKY 重建。
    private static final String WATCHDOG_PREFS = "node_watchdog";
    private static final String KEY_QUICK_EXIT_STREAK = "quick_exit_streak";
    /** 启动后多久内退出算"秒退"（用于崩溃循环保护） */
    private static final long QUICK_EXIT_MS = 20_000;
    /** 连续秒退达到该次数即暂停自愈，避免无限重启 */
    private static final int MAX_QUICK_EXITS = 6;
    private static final long WATCHDOG_INTERVAL_MS = 30_000;
    /** 心跳过期阈值：远大于 Node 10s 一次的心跳间隔 */
    private static final long HEARTBEAT_STALE_MS = 90_000;
    /** 启动后等待数据端口就绪的上限：Node 线程起来 ≠ HTTP 可用，必须探测确认 */
    private static final long READY_TIMEOUT_MS = 30_000;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!nodeStarted || stopping) {
                return;
            }
            try {
                File hb = new File(nodeDataDir(), "heartbeat");
                long age = System.currentTimeMillis() - hb.lastModified();
                if (hb.exists() && age > HEARTBEAT_STALE_MS) {
                    handleNodeDown("心跳过期(" + age + "ms)", -1);
                    return;
                }
            } catch (Throwable t) {
                bootLog("Watchdog 检查异常: " + t);
            }
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            bootLog("onCreate: process=:node");
            createChannel();
            // Android 14+ 必须先有通知权限才能 startForeground，否则系统强杀 → 秒退。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "无通知权限，stopSelf");
                bootLog("无通知权限，stopSelf");
                stopSelf();
                return;
            }
            startForegroundNotification("正在启动 Node 运行时…");
            bootLog("startForeground attempted");
        } catch (Throwable t) {
            Log.e(TAG, "onCreate 异常", t);
            bootLog("onCreate EXCEPTION: " + Log.getStackTraceString(t));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopServer();
            return START_NOT_STICKY;
        }
        // 崩溃循环保护：若 Node 在极短时间内连续"秒退"多次，说明存在需要人工排查
        // 的问题（依赖缺失、libnode 不兼容等）。此时不再自愈重启，避免无限重启把
        // 电量/CPU 打满，同时把计数清零，让用户手动重开时还能再试一次。
        int streak = watchdogPrefs().getInt(KEY_QUICK_EXIT_STREAK, 0);
        if (streak >= MAX_QUICK_EXITS) {
            bootLog("Node 连续快速退出 streak=" + streak + "，暂停自愈（请排查依赖/libnode）");
            watchdogPrefs().edit().putInt(KEY_QUICK_EXIT_STREAK, 0).apply();
            broadcast(false, false, "服务反复启动失败，已暂停自愈，请排查日志");
            stopSelf();
            return START_NOT_STICKY;
        }
        // Node 是进程级单例：nodejs-mobile 官方明确「只有第一次 start 有效，后续调用
        // 不会再启动新的 Node 线程」。因此必须忽略重复的启动请求——否则第二次调用会
        // 变成空操作，而服务仍上报「Node 已在专用线程启动」，排查时极具误导性。
        if (starting || nodeStarted) {
            bootLog("忽略重复启动请求(starting=" + starting + ", nodeStarted=" + nodeStarted + ")");
            broadcast(true, nodeStarted, nodeStarted
                    ? "CouchDB 兼容服务运行中（:5984）" : "正在启动…");
            return START_STICKY;
        }
        startServer();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        nodeStarted = false;
        mainHandler.removeCallbacks(watchdog);
        NodeBridge.clearNodeExitListener();
        executor.shutdownNow();
        super.onDestroy();
    }

    // ==================== 启动流程 ====================

    private void startServer() {
        stopping = false;
        starting = true;
        startAtMs = SystemClock.elapsedRealtime();
        startForegroundNotification("正在启动 CouchDB 兼容服务…");
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File projectDir = prepareNodeProject();
                    startNode(projectDir);
                    // node::Start 返回只代表 Node 线程已被拉起，不代表 HTTP 已在监听。
                    // 必须探测控制端口 /status 的 running 才能确认服务真的可用；否则界面会
                    // 显示「运行中」而页面打不开——这正是历史上最误导的一处。
                    starting = false;
                    boolean ready = waitUntilServing(READY_TIMEOUT_MS);
                    if (ready) {
                        bootLog("数据端口已就绪: " + Prefs.DEFAULT_PORT);
                        broadcast(true, true, "CouchDB 兼容服务运行中（:5984）");
                    } else if (!stopping) {
                        bootLog("Node 线程已启动，但 " + READY_TIMEOUT_MS + "ms 内控制端口未返回 running");
                        dumpNodeLogToBoot("启动后数据端口未就绪");
                        broadcast(true, false, "Node 线程已启动，但数据端口未就绪（详见日志）");
                    }
                    // Node 起来后才开 Watchdog，且延后一轮再检查心跳，
                    // 避免 Node 首次写心跳前被误判为死亡。
                    mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
                } catch (Throwable e) {
                    Log.e(TAG, "启动失败", e);
                    bootLog("启动失败: " + Log.getStackTraceString(e));
                    broadcast(false, false, "启动失败: " + e.getMessage());
                    // 启动阶段就失败也要计入秒退，交给崩溃循环保护兜底。
                    handleNodeDown("启动失败", -2);
                }
            }
        });
    }

    /** Node 数据目录（与 --data-dir 保持一致），Node 在其中写 server.json / heartbeat。 */
    private File nodeDataDir() {
        return new File(getFilesDir(), "data");
    }

    private SharedPreferences watchdogPrefs() {
        return getSharedPreferences(WATCHDOG_PREFS, MODE_PRIVATE);
    }

    /** 把 assets 里的 node 工程复制到私有目录（Node 无法直接执行 assets 里的脚本）。 */
    private File prepareNodeProject() throws IOException {
        File projectDir = new File(getFilesDir(), "nodejs-project");
        // 注意：构建标记不要用 .buildstamp 这类点开头文件名，aapt 打包 assets 时
        // 不保证收录点文件；一旦取不到标记就会每次启动都重拷 node_modules（很慢）。
        String assetStamp = readAssetText("nodejs-project/buildstamp");
        File stampFile = new File(projectDir, "buildstamp");
        if (stampFile.exists() && assetStamp != null
                && assetStamp.equals(readFileText(stampFile))) {
            return projectDir;
        }
        deleteRecursive(projectDir);
        projectDir.mkdirs();
        copyAssetFolder("nodejs-project", projectDir);
        return projectDir;
    }

    /**
     * 按官方姿势启动 Node：JNI 在专用线程上调 node::Start()。
     * 关键点：
     *   1) argv[0] 约定为 "node"，argv[1] 为脚本路径；
     *   2) TMPDIR 指向 CacheDir（Android 无 /tmp，官方插件明确设置）；
     *   3) NODE_PATH 指向工程目录，供 require() 解析 node_modules；
     *   4) 通过 argv 把完整服务参数传进去（安卓侧 JNI 无法设置环境变量）：
     *      --data-dir 数据目录、--port/--control-port 监听端口、--log-file 日志文件；
     *   5) 注册 Node 退出回调，node::Start 一旦返回即触发自愈。
     */
    private void startNode(File projectDir) throws IOException {
        File mainJs = new File(projectDir, "main.js");
        if (!mainJs.exists()) {
            throw new IOException("main.js 不存在: " + mainJs.getAbsolutePath());
        }

        File dataDir = nodeDataDir();
        dataDir.mkdirs();

        File logFile = new File(getFilesDir(), "node.log");
        if (logFile.exists()) {
            logFile.delete();
        }
        // 清掉上一轮遗留的心跳，避免 Watchdog 把旧心跳误判为"当前存活/或已过期"。
        File heartbeat = new File(dataDir, "heartbeat");
        if (heartbeat.exists()) {
            heartbeat.delete();
        }

        // node::Start 返回（Node 进程结束）→ 回调 → 自愈重启
        NodeBridge.setNodeExitListener(new NodeBridge.NodeExitListener() {
            @Override
            public void onNodeExit(int code) {
                handleNodeDown("node::Start 返回", code);
            }
        });

        String[] argv = new String[]{
                "node",
                mainJs.getAbsolutePath(),
                "--data-dir=" + dataDir.getAbsolutePath(),
                "--port=" + Prefs.DEFAULT_PORT,
                "--control-port=" + Prefs.DEFAULT_CONTROL_PORT,
                "--instance=" + prefs.getInstance(),
                "--device-id=" + prefs.getDeviceId(),
                "--log-file=" + logFile.getAbsolutePath(),
        };

        String tmpDir = getCacheDir().getAbsolutePath();
        String nodePath = projectDir.getAbsolutePath();

        bootLog("startNode: mainJs=" + mainJs.getAbsolutePath()
                + " dataDir=" + dataDir.getAbsolutePath()
                + " tmpDir=" + tmpDir + " nodePath=" + nodePath);

        try {
            NodeBridge.startNode(argv, tmpDir, nodePath);
        } catch (Throwable t) {
            throw new IOException("加载/启动 node-bridge 失败: " + t.getMessage(), t);
        }

        nodeStarted = true;
        bootLog("Node 已在专用线程启动");

        // 启动 mDNS 宣告，使本机在局域网内可被其他 pouchdb-server 发现（互为配对需要双向）。
        // 端口固定为数据端口，device_id 取自 Prefs（稳定身份）。
        try {
            if (advertiser == null) {
                advertiser = new MdnsAdvertiser(this, null);
            }
            advertiser.start(prefs.getInstance(), Prefs.DEFAULT_PORT, prefs.getDeviceId());
        } catch (Throwable t) {
            bootLog("MdnsAdvertiser 启动失败（不影响数据服务）: " + t.getMessage());
        }
    }

    // ==================== 就绪探测 / 日志 ====================

    /**
     * 轮询控制端口 /status，直到 running=true 或超时。
     * Node 线程起来 ≠ HTTP 可用：只有这个探测才能确认数据端口真的在服务。
     */
    private boolean waitUntilServing(long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (true) {
            if (stopping) {
                return false;
            }
            if (isServing()) {
                return true;
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** 控制端口 /status 的 running 字段：true 才代表数据端口已真正监听。 */
    private boolean isServing() {
        try {
            String body = Http.get("http://127.0.0.1:" + Prefs.DEFAULT_CONTROL_PORT + "/status");
            return new org.json.JSONObject(body).optBoolean("running", false);
        } catch (Throwable t) {
            return false; // 连不上或响应不可解析都算未就绪
        }
    }

    /**
     * 把 node.log 末尾追加到 boot.log。Node 的 stdout/stderr 在安卓上不可见（不进
     * node.log 的话界面就读不到），只有写进文件界面才看得到；否则「线程起来了但
     * 服务没起」只能靠猜。
     */
    private void dumpNodeLogToBoot(String why) {
        try {
            String tail = readTail(new File(getFilesDir(), "node.log"), 2000);
            if (tail == null || tail.trim().isEmpty()) {
                tail = "（空：main.js 可能没被执行，或 Node 无法写日志）";
            }
            bootLog("---- node.log 末尾（" + why + "）----\n" + tail);
        } catch (Throwable t) {
            bootLog("读取 node.log 失败: " + t);
        }
    }

    /** 读取文件尾部最多 max 字节，失败或不存在返回 null。 */
    private static String readTail(File file, int max) {
        if (file == null || !file.exists()) {
            return null;
        }
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(file, "r");
            long length = raf.length();
            int size = (int) Math.min(length, (long) max);
            if (size <= 0) {
                return "";
            }
            raf.seek(length - size);
            byte[] buf = new byte[size];
            raf.readFully(buf);
            return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ==================== 停止流程 ====================

    private void stopServer() {
        stopping = true;
        starting = false;
        nodeStarted = false;
        if (advertiser != null) {
            try { advertiser.stop(); } catch (Throwable ignored) { }
        }
        mainHandler.removeCallbacks(watchdog);
        NodeBridge.clearNodeExitListener();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                } else {
                    stopForeground(true);
                }
                stopSelf();
            }
        });
    }

    // ==================== 自愈 ====================

    /**
     * 统一的「Node 不可用」处理入口（native 退出回调 / Watchdog 心跳过期 / 启动失败）。
     * 记录 uptime 与连续秒退次数，然后结束当前 :node 进程：
     * 由于 onStartCommand 返回 START_STICKY，系统会以新进程重建服务，
     * 从而重新调用 node::Start（同一进程内不可二次调用 node::Start，故必须重启进程）。
     */
    private void handleNodeDown(String reason, int code) {
        if (stopping) {
            return;
        }
        stopping = true;
        starting = false;
        nodeStarted = false;
        if (advertiser != null) {
            try { advertiser.stop(); } catch (Throwable ignored) { }
        }

        long uptime = startAtMs > 0 ? (SystemClock.elapsedRealtime() - startAtMs) : 0;
        int streak = uptime < QUICK_EXIT_MS ? (readStreak() + 1) : 0;
        writeStreak(streak);

        bootLog("Node 不可用[" + reason + "] code=" + code
                + " uptime=" + uptime + "ms streak=" + streak);
        // 顺带保留 Node 侧日志末尾：界面只读 boot.log 就能看到根因（含 require 失败堆栈）。
        dumpNodeLogToBoot(reason);

        mainHandler.removeCallbacks(watchdog);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (streak >= MAX_QUICK_EXITS) {
                    watchdogPrefs().edit().putInt(KEY_QUICK_EXIT_STREAK, 0).apply();
                    broadcast(false, false, "服务反复异常，已暂停自愈，请排查日志");
                } else {
                    broadcast(false, false, "服务异常，正在自愈重启…");
                }
                stopSelf();
            }
        });
    }

    private int readStreak() {
        return watchdogPrefs().getInt(KEY_QUICK_EXIT_STREAK, 0);
    }

    private void writeStreak(int streak) {
        watchdogPrefs().edit().putInt(KEY_QUICK_EXIT_STREAK, streak).apply();
    }

    // ==================== 通知 ====================

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Node Demo", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
    }

    private void startForegroundNotification(String message) {
        Notification notification = buildSafeNotification(message);
        callStartForeground(notification);
    }

    /**
     * 调用 startForeground。
     * 1) API 34+ 传 dataSync 前台服务类型（与 manifest 声明一致）；
     * 2) 任何异常都吞掉并记录，绝不向上抛——否则系统 5 秒超时强杀整个进程。
     */
    private void callStartForeground(Notification notification) {
        if (notification == null) {
            bootLog("callStartForeground: notification 为 null，无法建立前台服务");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                bootLog("startForeground(dataSync) ok");
                return;
            }
        } catch (Throwable t) {
            Log.e(TAG, "startForeground(dataSync) 失败，回退无类型", t);
            bootLog("startForeground(dataSync) failed: " + t);
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0);
            bootLog("startForeground(no-type) ok");
        } catch (Throwable t) {
            Log.e(TAG, "startForeground 仍失败（系统可能 5 秒强杀进程）", t);
            bootLog("startForeground no-type failed: " + Log.getStackTraceString(t));
        }
    }

    private Notification buildSafeNotification(String message) {
        try {
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("PouchDB Server")
                    .setContentText(message)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setOnlyAlertOnce(true)
                    .setSmallIcon(android.R.drawable.ic_dialog_info);
            return b.build();
        } catch (Throwable t) {
            Log.e(TAG, "buildSafeNotification 失败", t);
            bootLog("buildSafeNotification failed: " + t);
            return null;
        }
    }

    private void broadcast(boolean running, boolean nodeUp, String message) {
        Intent intent = new Intent(ACTION_STATE);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_RUNNING, running);
        intent.putExtra(EXTRA_NODE_UP, nodeUp);
        intent.putExtra(EXTRA_MESSAGE, message == null ? "" : message);
        sendBroadcast(intent);
    }

    // ==================== assets 复制 / 日志 ====================

    /** 把启动/异常关键步骤写入 filesDir/boot.log，便于“打开就退”时回看根因。 */
    private void bootLog(String msg) {
        try {
            java.io.FileWriter w = new java.io.FileWriter(new java.io.File(getFilesDir(), "boot.log"), true);
            w.write(new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date())
                    + " " + msg + "\n");
            w.flush();
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private void copyAssetFolder(String path, File outDir) throws IOException {
        String[] items = getAssets().list(path);
        if (items == null || items.length == 0) {
            outDir.mkdirs();
            return;
        }
        outDir.mkdirs();
        for (String item : items) {
            String subPath = path + "/" + item;
            String[] subItems = getAssets().list(subPath);
            File outFile = new File(outDir, item);
            if (subItems != null && subItems.length > 0) {
                copyAssetFolder(subPath, outFile);
            } else {
                copyAssetFile(subPath, outFile);
            }
        }
    }

    private void copyAssetFile(String assetPath, File outFile) throws IOException {
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.w(TAG, "创建目录失败: " + parent);
        }
        InputStream is = null;
        OutputStream os = null;
        try {
            is = getAssets().open(assetPath);
            os = new FileOutputStream(outFile);
            byte[] buffer = new byte[64 * 1024];
            int len;
            while ((len = is.read(buffer)) > 0) {
                os.write(buffer, 0, len);
            }
        } finally {
            if (is != null) is.close();
            if (os != null) os.close();
        }
    }

    private String readAssetText(String assetPath) {
        InputStream is = null;
        try {
            is = getAssets().open(assetPath);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) > 0) {
                bos.write(buf, 0, len);
            }
            return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String readFileText(File file) {
        try {
            byte[] data = new byte[(int) file.length()];
            InputStream is = new java.io.FileInputStream(file);
            try {
                int read = 0;
                while (read < data.length) {
                    int n = is.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
            } finally {
                is.close();
            }
            return new String(data, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
}
