package net.weijia.pouchdbserver;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 仿 go-stock-server 的 StockApp：在 Application 创建时建立通知渠道，
 * 保证前台服务启动前渠道一定存在（含 BootReceiver 在开机时直接拉起服务的场景）。
 *
 * 关键修正：使用新的渠道 ID 并固定为 DEFAULT 重要级。
 * 旧版本把渠道建成了 IMPORTANCE_LOW，而 Android 不允许 App 自行把已建渠道调高，
 * 导致前台服务通知在一些机型上被折叠/隐藏（用户“看不到通知栏”）。
 * 换用新渠道 ID 后，系统会按 DEFAULT 重要级新建渠道，通知正常可见。
 */
public class App extends Application {
    public static final String CHANNEL_ID = "pouchdb_server_fg";
    private static final String TAG = "PouchDBApp";

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashLogger();
        createChannel();
    }

    /**
     * 全局未捕获异常兜底：把堆栈写入 crash.log，方便“程序直接退出”时回看根因。
     * 同时把异常交还给系统默认处理器，保证 AMS 仍按崩溃处理。
     */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                Log.e(TAG, "未捕获异常（已写入 crash.log）", e);
                try {
                    FileWriter w = new FileWriter(new java.io.File(getFilesDir(), "crash.log"), true);
                    w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                            + " thread=" + t.getName() + "\n");
                    w.write(Log.getStackTraceString(e));
                    w.write("\n\n");
                    w.flush();
                    w.close();
                } catch (IOException ignored) {
                }
                if (def != null) {
                    def.uncaughtException(t, e);
                }
            }
        });
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return; // 已存在则不降/升重要级
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "PouchDB Server", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("CouchDB 兼容服务运行状态（常驻通知）");
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
    }
}
