package net.weijia.pouchdbserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 承载 Node 运行时的前台服务（薄壳，对标 go-stock-server 的 StockForegroundService）。
 *
 * 与旧版的关键差别：Node 不再以 JNI 方式塞进 App 进程，而是作为【独立原生进程】
 * 由本服务用 ProcessBuilder 拉起（启动器 libpouchdb.so 位于 nativeLibraryDir，可执行）。
 * 好处：
 *   1) Node 崩溃只结束它自己，App/前台服务进程不受影响，可在服务内单独重启；
 *   2) 不再有“同进程 native 崩溃拖死整个 App → 秒退”的问题；
 *   3) 前台服务只负责拉起/监控进程 + 通知 + mDNS，逻辑简单稳健。
 *
 * Android 14+ 要求 startForeground 前必须已获 POST_NOTIFICATIONS 权限，否则直接抛异常强杀进程
 * （表现就是“秒退 + 一直弹权限”）。因此 onCreate 里先判权限，无权限则立刻 stopSelf，
 * 交由 UI 引导用户授权后再启动（与 go-stock-server 一致：用户点“启动”时才要权限）。
 */
public class NodeService extends Service {

    private static final String TAG = "NodeService";
    private static final String CHANNEL_ID = App.CHANNEL_ID;
    private static final int NOTIFICATION_ID = 5984;
    private static final long POLL_INTERVAL_MS = 5000;
    private static final String MDNS_SERVICE_TYPE = "_couchdb._tcp";

    public static final String ACTION_START = "net.weijia.pouchdbserver.action.START";
    public static final String ACTION_STOP = "net.weijia.pouchdbserver.action.STOP";
    public static final String ACTION_STATE = "net.weijia.pouchdbserver.action.STATE";

    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_NODE_UP = "node_up";
    public static final String EXTRA_IP = "ip";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_MDNS = "mdns";
    public static final String EXTRA_DATABASES = "databases";
    public static final String EXTRA_MESSAGE = "message";

    private Prefs prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MdnsAdvertiser mdnsAdvertiser;
    private WifiManager.MulticastLock multicastLock;

    private Process nodeProcess;
    private boolean nodeStarted;
    private volatile boolean stopping;
    private int port;
    private int controlPort;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollStatus();
            mainHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            bootLog("onCreate: super done");
            prefs = new Prefs(this);
            port = prefs.getPort();
            controlPort = prefs.getControlPort();
            createChannel();
            // Android 14+ 必须先有通知权限才能 startForeground，否则系统强杀 → 秒退。
            // 无权限时立即退出，让 UI 引导授权后重启。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "无通知权限，无法建立前台服务，stopSelf");
                bootLog("onCreate: 无通知权限，stopSelf");
                stopSelf();
                return;
            }
            startForegroundNotification("正在启动 PouchDB 服务…");
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
        startServer();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(pollRunnable);
        stopProcess();
        if (mdnsAdvertiser != null) {
            mdnsAdvertiser.stop();
        }
        releaseMulticastLock();
        executor.shutdownNow();
        super.onDestroy();
    }

    // ==================== 启动流程 ====================

    private void startServer() {
        stopping = false;
        startForegroundNotification("正在启动 Node 运行时...");
        mainHandler.removeCallbacks(pollRunnable);
        try {
            if (mdnsAdvertiser == null) {
                mdnsAdvertiser = new MdnsAdvertiser(this, new MdnsAdvertiser.Callback() {
                    @Override
                    public void onState(String state, String detail) {
                        Log.i(TAG, "mDNS: " + state + " " + detail);
                    }
                });
            }
        } catch (Throwable t) {
            Log.e(TAG, "mDNS 初始化失败（不影响主服务）", t);
        }

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File projectDir = prepareNodeProject();
                    startNodeProcess(projectDir);
                    waitForControlPort(60);
                    ensureHttpStarted();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mainHandler.post(pollRunnable);
                        }
                    });
                    pollStatus();
                } catch (Exception e) {
                    Log.e(TAG, "启动失败", e);
                    bootLog("启动失败: " + Log.getStackTraceString(e));
                    broadcast(false, nodeStarted, "启动失败: " + e.getMessage());
                }
            }
        });
    }

    /** 把 assets 里的 node 工程复制到私有目录（Node 无法直接执行 assets 里的脚本）。 */
    private File prepareNodeProject() throws IOException {
        File projectDir = new File(getFilesDir(), "nodejs-project");
        String assetStamp = readAssetText("nodejs-project/.buildstamp");
        File stampFile = new File(projectDir, ".buildstamp");
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
     * 作为【独立进程】拉起 Node：libpouchdb.so（链接 libnode.so，调用 node::Start 跑 main.js）。
     * 与旧版“JNI 内联在 App 进程里”的根本差别在于此——崩溃隔离。
     */
    private void startNodeProcess(File projectDir) throws IOException {
        File mainJs = new File(projectDir, "main.js");
        File dataDir = new File(getFilesDir(), "pouchdb-data");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            Log.w(TAG, "数据目录创建失败: " + dataDir);
        }

        File launcher = new File(getApplicationInfo().nativeLibraryDir, "libpouchdb.so");
        if (!launcher.exists()) {
            throw new IOException("Node 启动器缺失: " + launcher.getAbsolutePath());
        }
        // nativeLibraryDir 下的文件安装后即带执行权限；这里再保险一次
        if (!launcher.canExecute() && !launcher.setExecutable(true)) {
            throw new IOException("Node 启动器不可执行: " + launcher.getAbsolutePath());
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(launcher.getAbsolutePath());
        cmd.add(mainJs.getAbsolutePath());
        cmd.add("--data-dir=" + dataDir.getAbsolutePath());
        cmd.add("--port=" + port);
        cmd.add("--control-port=" + controlPort);
        cmd.add("--instance=" + prefs.getInstance());

        File logFile = new File(getFilesDir(), "node.log");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(projectDir);
        // NODE_PATH 指向工程目录，保证 require() 能找到 node_modules
        pb.environment().put("NODE_PATH", projectDir.getAbsolutePath());
        // libnode.so 与启动器同在 nativeLibraryDir，告知动态链接器去那里找
        pb.environment().put("LD_LIBRARY_PATH", getApplicationInfo().nativeLibraryDir);
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile);

        nodeProcess = pb.start();
        nodeStarted = true;
        bootLog("Node 进程已启动 launcher=" + launcher.getAbsolutePath()
                + " mainJs=" + mainJs.getAbsolutePath());

        acquireMulticastLock();

        // 监控进程退出：非正常退出（非 0）且非主动停止时，延迟自动重启
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    int code = nodeProcess.waitFor();
                    Log.w(TAG, "Node 进程退出 code=" + code);
                    nodeStarted = false;
                    bootLog("Node 进程退出 code=" + code);
                    if (!stopping && code != 0) {
                        mainHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!stopping) startServer();
                            }
                        }, 3000);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    private void waitForControlPort(int maxTries) throws IOException {
        String url = "http://127.0.0.1:" + controlPort + "/status";
        for (int i = 0; i < maxTries; i++) {
            try {
                String body = Http.get(url);
                if (body != null && body.contains("\"running\"")) {
                    Log.i(TAG, "控制端口就绪");
                    return;
                }
            } catch (IOException ignored) {
                // 还没起来，继续等
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IOException("控制端口在 " + (maxTries / 2) + " 秒内未就绪");
    }

    /** 数据端口默认是开启的；若上次被 stop 过，这里负责重新开启。 */
    private void ensureHttpStarted() {
        try {
            JSONObject status = new JSONObject(Http.get(controlUrl("/status")));
            if (!status.optBoolean("running", false)) {
                Http.post(controlUrl("/start"));
            }
            port = status.optInt("port", port);
        } catch (Exception e) {
            Log.w(TAG, "确保 HTTP 启动时出错: " + e.getMessage());
        }
    }

    // ==================== 状态轮询 ====================

    private void pollStatus() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject status = new JSONObject(Http.get(controlUrl("/status")));
                    boolean running = status.optBoolean("running", false);
                    int httpPort = status.optInt("port", port);
                    port = httpPort;
                    JSONArray dbs = status.optJSONArray("databases");
                    StringBuilder dbText = new StringBuilder();
                    if (dbs != null) {
                        for (int i = 0; i < dbs.length(); i++) {
                            JSONObject item = dbs.optJSONObject(i);
                            if (item == null) continue;
                            if (dbText.length() > 0) dbText.append(", ");
                            dbText.append(item.optString("name", "?"));
                        }
                    }
                    String ip = NetUtils.getLanIp(NodeService.this);
                    updateMdns(running, httpPort);
                    updateNotification(running, ip, httpPort, dbText.toString());
                    broadcast(running, true, dbText.toString(), ip, httpPort);
                } catch (Exception e) {
                    broadcast(false, nodeStarted, "无法连接控制端口: " + e.getMessage());
                }
            }
        });
    }

    private void updateMdns(boolean running, int httpPort) {
        if (mdnsAdvertiser == null) return;
        if (running) {
            mdnsAdvertiser.start(prefs.getInstance(), httpPort);
        } else {
            mdnsAdvertiser.stop();
        }
    }

    // ==================== 停止流程 ====================

    private void stopServer() {
        stopping = true;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Http.post(controlUrl("/flush"));
                    Http.post(controlUrl("/stop"));
                } catch (Exception e) {
                    Log.w(TAG, "停止时出错: " + e.getMessage());
                }
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mainHandler.removeCallbacks(pollRunnable);
                        if (mdnsAdvertiser != null) {
                            mdnsAdvertiser.stop();
                        }
                        broadcast(false, nodeStarted, "已停止", null, port);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            stopForeground(STOP_FOREGROUND_REMOVE);
                        } else {
                            stopForeground(true);
                        }
                        stopSelf();
                    }
                });
            }
        });
    }

    private void stopProcess() {
        if (nodeProcess != null) {
            try {
                nodeProcess.destroy();
            } catch (Throwable ignored) {
            }
            nodeProcess = null;
        }
        nodeStarted = false;
    }

    // ==================== 通知 / 广播 ====================

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "PouchDB Server", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("CouchDB 兼容服务运行状态");
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
     * 1) API 34+ 传 dataSync 前台服务类型（与 manifest 声明一致，参照 go-stock-server）；
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

    private void updateNotification(boolean running, String ip, int httpPort, String databases) {
        String message = running
                ? "运行中 " + (ip != null ? "http://" + ip + ":" + httpPort : "端口 " + httpPort)
                : "已停止监听";
        Notification notification = buildSafeNotification(message);
        if (notification == null) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, notification);
        }
    }

    private PendingIntent buildContentIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(this, 0, intent, flags);
    }

    private PendingIntent buildStopIntent() {
        Intent intent = new Intent(this, NodeService.class).setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, 1, intent, flags);
    }

    /**
     * 三级兜底构建前台通知，保证 build() 绝不抛异常、绝不返回 null：
     * 1) compat + 自定义 vector 图标；
     * 2) compat + 系统图标；
     * 3) 平台原生 Builder + 系统图标（终极兜底）。
     */
    private Notification buildSafeNotification(String message) {
        try {
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("PouchDB Server")
                    .setContentText(message)
                    .setSubText("点击打开管理界面")
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setOnlyAlertOnce(true)
                    .setSmallIcon(R.drawable.ic_stat_server)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", buildStopIntent());
            try { b.setContentIntent(buildContentIntent()); } catch (Throwable ignored) {}
            return b.build();
        } catch (Throwable t1) {
            Log.e(TAG, "buildSafeNotification(自定义图标) 失败，回退系统图标", t1);
            bootLog("buildSafeNotification custom icon failed: " + t1);
        }
        try {
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("PouchDB Server")
                    .setContentText(message)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setOnlyAlertOnce(true)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", buildStopIntent());
            try { b.setContentIntent(buildContentIntent()); } catch (Throwable ignored) {}
            return b.build();
        } catch (Throwable t2) {
            Log.e(TAG, "buildSafeNotification(系统图标) 也失败，用平台原生兜底", t2);
            bootLog("buildSafeNotification system icon failed: " + t2);
        }
        try {
            Notification.Builder nb = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("PouchDB Server")
                    .setContentText(message)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                return nb.build();
            }
            return nb.getNotification();
        } catch (Throwable t3) {
            Log.e(TAG, "连平台原生通知都失败", t3);
            bootLog("buildSafeNotification platform fallback failed: " + t3);
            return null;
        }
    }

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

    private void broadcast(boolean running, boolean nodeUp, String message) {
        broadcast(running, nodeUp, message, null, port);
    }

    private void broadcast(boolean running, boolean nodeUp, String message, String ip, int httpPort) {
        Intent intent = new Intent(ACTION_STATE);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_RUNNING, running);
        intent.putExtra(EXTRA_NODE_UP, nodeUp);
        intent.putExtra(EXTRA_IP, ip);
        intent.putExtra(EXTRA_PORT, httpPort);
        intent.putExtra(EXTRA_MDNS, prefs.getInstance() + "." + MdnsAdvertiser.SERVICE_TYPE);
        intent.putExtra(EXTRA_MESSAGE, message == null ? "" : message);
        sendBroadcast(intent);
    }

    // ==================== assets 复制 ====================

    private String controlUrl(String path) {
        return "http://127.0.0.1:" + controlPort + path;
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

    // ==================== WiFi 组播锁（mDNS 需要）====================

    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && multicastLock == null) {
                multicastLock = wifi.createMulticastLock("pouchdb-server-mdns");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "获取组播锁失败: " + e.getMessage());
        }
    }

    private void releaseMulticastLock() {
        if (multicastLock != null) {
            try {
                multicastLock.release();
            } catch (Exception ignored) {
            }
            multicastLock = null;
        }
    }
}
