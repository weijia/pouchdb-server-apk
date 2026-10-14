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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 管理界面：启停服务、查看地址与数据库列表、查看 Node 日志。 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int REQ_NOTIFICATION = 1001;

    private TextView tvStatus;
    private TextView tvAddress;
    private TextView tvMdns;
    private TextView tvDatabases;
    private TextView tvLog;
    private Button btnToggle;

    private Prefs prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean running;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (NodeService.ACTION_STATE.equals(intent.getAction())) {
                running = intent.getBooleanExtra(NodeService.EXTRA_RUNNING, false);
                String ip = intent.getStringExtra(NodeService.EXTRA_IP);
                int port = intent.getIntExtra(NodeService.EXTRA_PORT, prefs.getPort());
                String mdns = intent.getStringExtra(NodeService.EXTRA_MDNS);
                String message = intent.getStringExtra(NodeService.EXTRA_MESSAGE);
                render(running, ip, port, mdns,
                        intent.getStringExtra(NodeService.EXTRA_DATABASES), message);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);

        tvStatus = findViewById(R.id.tvStatus);
        tvAddress = findViewById(R.id.tvAddress);
        tvMdns = findViewById(R.id.tvMdns);
        tvDatabases = findViewById(R.id.tvDatabases);
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
                refreshStatus();
                refreshLog();
            }
        });

        // 打开即尝试拉起前台服务：已授权直接启动，未授权先弹申请，授权后再启动
        maybeAutoStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(NodeService.ACTION_STATE);
        registerReceiver(receiver, filter);
        refreshStatus();
        refreshLog();
    }

    @Override
    protected void onPause() {
        unregisterReceiver(receiver);
        super.onPause();
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
            Toast.makeText(this, "正在停止服务", Toast.LENGTH_SHORT).show();
        } else {
            intent.setAction(NodeService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "正在启动服务", Toast.LENGTH_SHORT).show();
        }
        // 状态由广播回传，这里先做个即时反馈
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshStatus();
            }
        }, 1500);
    }

    private void refreshStatus() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject status = new JSONObject(
                            Http.get("http://127.0.0.1:" + prefs.getControlPort() + "/status"));
                    final boolean up = status.optBoolean("running", false);
                    final int port = status.optInt("port", prefs.getPort());
                    StringBuilder dbs = new StringBuilder();
                    JSONArray dbArray = status.optJSONArray("databases");
                    if (dbArray != null) {
                        for (int i = 0; i < dbArray.length(); i++) {
                            JSONObject item = dbArray.optJSONObject(i);
                            if (item == null) continue;
                            if (dbs.length() > 0) dbs.append(", ");
                            dbs.append(item.optString("name", "?"));
                        }
                    }
                    final String ip = NetUtils.getLanIp(MainActivity.this);
                    final String dbText = dbs.toString();
                    running = up;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            render(up, ip, port,
                                    prefs.getInstance() + "." + MdnsAdvertiser.SERVICE_TYPE,
                                    dbText, up ? "" : "服务未运行");
                        }
                    });
                } catch (Exception e) {
                    Log.d(TAG, "状态查询失败: " + e.getMessage());
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            render(false, null, prefs.getPort(),
                                    prefs.getInstance() + "." + MdnsAdvertiser.SERVICE_TYPE,
                                    "", "Node 运行时未启动");
                        }
                    });
                }
            }
        });
    }

    private void refreshLog() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                sb.append("==== boot.log（启动步骤）====\n")
                        .append(readTail(new File(getFilesDir(), "boot.log"), 6000));
                sb.append("\n==== crash.log（崩溃堆栈）====\n")
                        .append(readTail(new File(getFilesDir(), "crash.log"), 6000));
                sb.append("\n==== server.log（Node 输出）====\n").append(tailLog());
                sb.append("\n==== node.log（Node 原生输出）====\n")
                        .append(readTail(new File(getFilesDir(), "node.log"), 6000));
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
            return "（无）";
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

    private String tailLog() {
        File file = new File(new File(getFilesDir(), "pouchdb-data"), "server.log");
        if (!file.exists()) {
            return "（暂无日志）";
        }
        final int max = 8000;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            int size = (int) Math.min(length, max);
            raf.seek(length - size);
            byte[] buf = new byte[size];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "读取日志失败: " + e.getMessage();
        }
    }

    private void render(boolean isRunning, String ip, int port, String mdns,
                        String databases, String message) {
        tvStatus.setText(isRunning ? "运行中" : "已停止");
        tvAddress.setText(ip != null
                ? "http://" + ip + ":" + port + "/  （控制端口 " + prefs.getControlPort() + "）"
                : "未连接到局域网（端口 " + port + "）");
        tvMdns.setText(mdns);
        tvDatabases.setText(databases == null || databases.isEmpty() ? "（无）" : databases);
        if (message != null && !message.isEmpty()) {
            tvStatus.setText(tvStatus.getText() + "  ·  " + message);
        }
        btnToggle.setText(isRunning ? "停止服务" : "启动服务");
    }

    /** 已获通知权限（Android 12 及以下默认视为已授权）。 */
    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /** 打开应用时尝试拉起服务：已授权直接启动，未授权先弹申请。 */
    private void maybeAutoStart() {
        if (hasNotificationPermission()) {
            autoStartService();
        } else {
            requestNotificationPermissionIfNeeded();
        }
    }

    private void autoStartService() {
        Intent intent = new Intent(this, NodeService.class);
        intent.setAction(NodeService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "正在启动服务（状态栏将出现常驻通知）", Toast.LENGTH_SHORT).show();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFICATION);
            }
        }
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
                // 授权后立刻拉起前台服务：状态栏出现常驻通知，可随时点开回到应用
                autoStartService();
            } else {
                Toast.makeText(this, "未授予通知权限，前台服务通知不可见，无法自动启动",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }
}
