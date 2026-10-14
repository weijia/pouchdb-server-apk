package net.weijia.pouchdbserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

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

    private boolean nodeStarted;
    private volatile boolean stopping;

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
        executor.shutdownNow();
        super.onDestroy();
    }

    // ==================== 启动流程 ====================

    private void startServer() {
        stopping = false;
        startForegroundNotification("正在启动 Node 运行时…");
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File projectDir = prepareNodeProject();
                    startNode(projectDir);
                    broadcast(true, true, "Node 运行中（hello world）");
                } catch (Throwable e) {
                    Log.e(TAG, "启动失败", e);
                    bootLog("启动失败: " + Log.getStackTraceString(e));
                    broadcast(false, false, "启动失败: " + e.getMessage());
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
     * 按官方姿势启动 Node：JNI 在专用线程上调 node::Start()。
     * 关键点：
     *   1) argv[0] 约定为 "node"，argv[1] 为脚本路径；
     *   2) TMPDIR 指向 CacheDir（Android 无 /tmp，官方插件明确设置，之前我们漏了）；
     *   3) NODE_PATH 指向工程目录，供 require() 解析模块；
     *   4) 通过 --log-file 让 Node 把输出写进文件，界面才能看到（stdout 在安卓上不可见）。
     */
    private void startNode(File projectDir) throws IOException {
        File mainJs = new File(projectDir, "main.js");
        if (!mainJs.exists()) {
            throw new IOException("main.js 不存在: " + mainJs.getAbsolutePath());
        }

        File logFile = new File(getFilesDir(), "node.log");
        if (logFile.exists()) {
            logFile.delete();
        }

        String[] argv = new String[]{
                "node",
                mainJs.getAbsolutePath(),
                "--log-file=" + logFile.getAbsolutePath(),
        };

        String tmpDir = getCacheDir().getAbsolutePath();
        String nodePath = projectDir.getAbsolutePath();

        bootLog("startNode: mainJs=" + mainJs.getAbsolutePath()
                + " tmpDir=" + tmpDir + " nodePath=" + nodePath);

        try {
            NodeBridge.startNode(argv, tmpDir, nodePath);
        } catch (Throwable t) {
            throw new IOException("加载/启动 node-bridge 失败: " + t.getMessage(), t);
        }

        nodeStarted = true;
        bootLog("Node 已在专用线程启动");
    }

    // ==================== 停止流程 ====================

    private void stopServer() {
        stopping = true;
        nodeStarted = false;
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
                    .setContentTitle("Node APK Demo")
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
