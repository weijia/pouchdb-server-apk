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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 最小验证版前台服务：只负责把 Node 作为【独立原生进程】拉起（执行 assets 里的 main.js，
 * 当前仅打印 hello world），并把其标准输出写入 filesDir/node.log。
 * 不依赖任何 mDNS / HTTP / 控制端口——只为验证「Node 能否在 APK 内跑起来」。
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

    private Process nodeProcess;
    private boolean nodeStarted;
    private volatile boolean stopping;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            bootLog("onCreate");
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
        stopProcess();
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
                    startNodeProcess(projectDir);
                    broadcast(true, true, "Node 运行中（hello world）");
                } catch (Exception e) {
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
     * 作为【独立进程】拉起 Node：libpouchdb.so（链接 libnode.so，调用 node::Start 跑 main.js）。
     * 与“JNI 内联在 App 进程里”的根本差别在于此——崩溃隔离，Node 崩了不会拖死 App。
     */
    private void startNodeProcess(File projectDir) throws IOException {
        File mainJs = new File(projectDir, "main.js");
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

        // 清空旧日志，方便本次观察
        File logFile = new File(getFilesDir(), "node.log");
        if (logFile.exists()) {
            logFile.delete();
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(projectDir);
        // NODE_PATH 指向工程目录，保证 require() 能找到模块（当前 hello world 不需要）
        pb.environment().put("NODE_PATH", projectDir.getAbsolutePath());
        // libnode.so 与启动器同在 nativeLibraryDir，告知动态链接器去那里找
        pb.environment().put("LD_LIBRARY_PATH", getApplicationInfo().nativeLibraryDir);
        pb.redirectErrorStream(true);

        nodeProcess = pb.start();
        nodeStarted = true;
        bootLog("Node 进程已启动 launcher=" + launcher.getAbsolutePath()
                + " mainJs=" + mainJs.getAbsolutePath());

        // 把标准输出（已含 stderr）持续写入 node.log。用线程手动拷贝，避免
        // ProcessBuilder.redirectOutput(File) 仅在 API 26+ 可用的问题。
        final OutputStream fos = new FileOutputStream(logFile, true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[4096];
                int n;
                try (InputStream in = nodeProcess.getInputStream()) {
                    while ((n = in.read(buf)) >= 0) {
                        fos.write(buf, 0, n);
                    }
                } catch (IOException e) {
                    Log.w(TAG, "读取 Node 输出失败", e);
                } finally {
                    try {
                        fos.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }).start();

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

    // ==================== 停止流程 ====================

    private void stopServer() {
        stopping = true;
        stopProcess();
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
