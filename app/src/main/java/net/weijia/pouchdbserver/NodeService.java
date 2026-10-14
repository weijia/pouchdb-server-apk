package net.weijia.pouchdbserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 承载 Node 运行时的前台服务。
 *
 * 生命周期要点：
 * - Node 运行在 App 进程内的线程上，进程被杀就没了，因此必须用前台服务保活
 * - 数据端口可以关闭/重开，但控制端口常驻，避免"关掉后再也起不来"
 * - 停止服务前先 /flush 落盘，降低快照丢失窗口
 */
public class NodeService extends Service {

    private static final String TAG = "NodeService";
    private static final String CHANNEL_ID = "pouchdb_server";
    private static final int NOTIFICATION_ID = 5984;
    private static final long POLL_INTERVAL_MS = 5000;

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

    private Thread nodeThread;
    private boolean nodeStarted;
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
        prefs = new Prefs(this);
        port = prefs.getPort();
        controlPort = prefs.getControlPort();
        createChannel();
        // 参照 go-stock-server：service 一创建就立即 startForeground，
        // 避免“服务已创建但还没挂前台通知”的窗口（Android 12+ 会直接杀进程，
        // 表现为 App 闪退/最小化、通知栏看不到）。
        startForegroundNotification("正在启动 PouchDB 服务…");
        mdnsAdvertiser = new MdnsAdvertiser(this, new MdnsAdvertiser.Callback() {
            @Override
            public void onState(String state, String detail) {
                Log.i(TAG, "mDNS: " + state + " " + detail);
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
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
        if (mdnsAdvertiser != null) {
            mdnsAdvertiser.stop();
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    // ==================== 启动流程 ====================

    private void startServer() {
        startForegroundNotification("正在启动 Node 运行时...");
        mainHandler.removeCallbacks(pollRunnable);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!nodeStarted) {
                        broadcast(false, false, "正在准备 Node 工程...");
                        File projectDir = prepareNodeProject();
                        startNodeThread(projectDir);
                    }
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

    private void startNodeThread(final File projectDir) {
        final File mainJs = new File(projectDir, "main.js");
        final File dataDir = new File(getFilesDir(), "pouchdb-data");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            Log.w(TAG, "数据目录创建失败: " + dataDir);
        }

        final String[] args = new String[]{
                "node",
                mainJs.getAbsolutePath(),
                "--data-dir=" + dataDir.getAbsolutePath(),
                "--port=" + port,
                "--control-port=" + controlPort,
                "--instance=" + prefs.getInstance(),
        };

        nodeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "启动 Node: " + mainJs.getAbsolutePath());
                int code = NodeBridge.startNodeWithArguments(args, projectDir.getAbsolutePath(), true);
                Log.i(TAG, "Node 退出，code=" + code);
            }
        }, "node-runtime");
        nodeThread.setDaemon(true);
        nodeThread.start();
        nodeStarted = true;
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
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // 先落盘，再停止监听，避免丢数据
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

    // ==================== 通知 / 广播 ====================

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "PouchDB Server", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("CouchDB 兼容服务运行状态");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void startForegroundNotification(String message) {
        Notification notification = buildNotification(message, null, port, false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0);
        }
    }

    private void updateNotification(boolean running, String ip, int httpPort, String databases) {
        String message = running
                ? "运行中 " + (ip != null ? "http://" + ip + ":" + httpPort : "端口 " + httpPort)
                : "已停止监听";
        Notification notification = buildNotification(message, ip, httpPort, running);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(String message, String ip, int httpPort, boolean running) {
        Intent intent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, flags);

        String sub = running
                ? "mDNS: " + prefs.getInstance() + "." + MdnsAdvertiser.SERVICE_TYPE
                : "点击打开管理界面";

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("PouchDB Server")
                .setContentText(message)
                .setSubText(sub)
                .setSmallIcon(R.drawable.ic_stat_server)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
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
}
