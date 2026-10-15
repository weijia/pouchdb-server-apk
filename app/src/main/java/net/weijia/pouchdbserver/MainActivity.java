package net.weijia.pouchdbserver;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 最小验证界面：启动 Node 独立进程，显示其输出（hello world）。 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int REQ_NOTIFICATION = 1001;

    private TextView tvStatus;
    private TextView tvLog;
    private Button btnToggle;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean running;
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (NodeService.ACTION_STATE.equals(intent.getAction())) {
                running = intent.getBooleanExtra(NodeService.EXTRA_RUNNING, false);
                String message = intent.getStringExtra(NodeService.EXTRA_MESSAGE);
                render(running, message);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);
        btnToggle = findViewById(R.id.btnToggle);
        Button btnRefresh = findViewById(R.id.btnRefresh);

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleService();
            }
        });
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshLog();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerStateReceiver();
        refreshLog();
    }

    @Override
    protected void onPause() {
        unregisterStateReceiver();
        super.onPause();
    }

    /**
     * 动态注册状态广播。
     *
     * 关键修复（崩溃根因）：从 Android 14（targetSdk 34）起，应用为「非系统广播」
     * 动态注册接收器时必须显式声明导出标志，否则 registerReceiver 会抛
     * SecurityException。此前本方法在 onResume 里裸调 registerReceiver，
     * 于是 App 一打开就崩，表现为「运行后直接退出」。
     *
     * 现在统一走 ContextCompat.registerReceiver + RECEIVER_NOT_EXPORTED，
     * 并对任何 Throwable 兜底，注册失败也不让界面进程崩溃。
     */
    private void registerStateReceiver() {
        if (receiverRegistered) {
            return;
        }
        try {
            IntentFilter filter = new IntentFilter(NodeService.ACTION_STATE);
            ContextCompat.registerReceiver(this, receiver, filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        } catch (Throwable t) {
            Log.e(TAG, "注册状态广播失败（已忽略，避免界面崩溃）", t);
        }
    }

    private void unregisterStateReceiver() {
        if (!receiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(receiver);
        } catch (Throwable t) {
            Log.w(TAG, "注销状态广播异常（已忽略）", t);
        } finally {
            receiverRegistered = false;
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void toggleService() {
        if (!ensureNotificationPermission()) {
            return;
        }
        Intent intent = new Intent(this, NodeService.class);
        if (running) {
            intent.setAction(NodeService.ACTION_STOP);
            startService(intent);
            Toast.makeText(this, "正在停止", Toast.LENGTH_SHORT).show();
        } else {
            intent.setAction(NodeService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "正在启动 Node", Toast.LENGTH_SHORT).show();
        }
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshLog();
            }
        }, 1500);
    }

    private void refreshLog() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                sb.append("==== node.log（Node 输出）====\n")
                        .append(readTail(new File(getFilesDir(), "node.log"), 8000));
                sb.append("\n\n==== boot.log（启动步骤）====\n")
                        .append(readTail(new File(getFilesDir(), "boot.log"), 4000));
                final String log = sb.toString();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        tvLog.setText(log);
                    }
                });
            }
        });
    }

    /** 读取文件尾部最多 max 字节，文件不存在时返回提示。 */
    private String readTail(File file, int max) {
        if (file == null || !file.exists()) {
            return "（无 " + (file == null ? "" : file.getName()) + "）";
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            int size = (int) Math.min(length, max);
            if (size <= 0) return "（空）";
            raf.seek(length - size);
            byte[] buf = new byte[size];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "读取失败: " + e.getMessage();
        }
    }

    private void render(boolean isRunning, String message) {
        tvStatus.setText(isRunning ? "Node 运行中" : "已停止");
        if (message != null && !message.isEmpty()) {
            tvStatus.setText(tvStatus.getText() + " · " + message);
        }
        btnToggle.setText(isRunning ? "停止" : "启动 Node");
        refreshLog();
    }

    private boolean ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFICATION);
                Toast.makeText(this, "需要先授予通知权限才能启动前台服务", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATION) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleService();
            } else {
                Toast.makeText(this, "未授予通知权限，无法启动", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
