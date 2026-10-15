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
import android.webkit.HttpAuthHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主界面：默认是内嵌浏览器（加载 Fauxton 管理界面 /_utils/），
 * 可切换到「状态/日志」页启停 Node 服务。
 *
 * 浏览器页必须等待 Node 数据端口（5984）真正监听后才打开网页：
 * 通过轮询控制端口（5985）/status 的 running 字段来判断就绪，
 * 因为广播里的 running 只代表 Node 进程已拉起，不代表 HTTP 已可服务。
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int REQ_NOTIFICATION = 1001;

    private static final int COLOR_TAB_SELECTED = 0xFF3F51B5;
    private static final int COLOR_TAB_NORMAL = 0xFF9E9E9E;

    private Prefs prefs;

    private WebView webView;
    private TextView tvWait;
    private FrameLayout browserPanel;
    private FrameLayout statusPanel;
    private Button tabBrowser;
    private Button tabStatus;

    private TextView tvStatus;
    private TextView tvLog;
    private Button btnToggle;
    private Button btnRefresh;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean browserVisible = true;
    private boolean serverUp;
    private boolean webViewLoaded;
    private boolean receiverRegistered;

    // 服务器就绪轮询：只用一个单线程调度器，串行执行避免并发。
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private ScheduledFuture<?> pollFuture;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (NodeService.ACTION_STATE.equals(intent.getAction())) {
                boolean running = intent.getBooleanExtra(NodeService.EXTRA_RUNNING, false);
                String message = intent.getStringExtra(NodeService.EXTRA_MESSAGE);
                render(running, message);
                // 状态有变化，立即触发一次就绪探测，缩短浏览器等待。
                schedulePoll(300);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);

        webView = findViewById(R.id.webView);
        tvWait = findViewById(R.id.tvWait);
        browserPanel = findViewById(R.id.browserPanel);
        statusPanel = findViewById(R.id.statusPanel);
        tabBrowser = findViewById(R.id.tabBrowser);
        tabStatus = findViewById(R.id.tabStatus);

        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);
        btnToggle = findViewById(R.id.btnToggle);
        btnRefresh = findViewById(R.id.btnRefresh);

        setupWebView();

        tabBrowser.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showBrowser();
            }
        });
        tabStatus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStatus();
            }
        });

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

        // 默认进入浏览器页（未就绪时显示等待提示）。
        showBrowser();
    }

    private void setupWebView() {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setDatabaseEnabled(true);
        webView.getSettings().setAllowFileAccess(false);
        // WebView 调试（chrome://inspect），仅本地调试用，无碍发布。
        WebView.setWebContentsDebuggingEnabled(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // 全部在 WebView 内打开，不甩给外部浏览器。
                return false;
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
                                                  String host, String realm) {
                // 服务端启用了 Basic Auth 时，自动填充配置里的账号密码，
                // 这样 Fauxton 主文档及后续所有静态资源/API 请求都能带鉴权。
                String user = prefs.getAuthUser();
                String pass = prefs.getAuthPass();
                if (user != null && !user.isEmpty()) {
                    handler.proceed(user, pass);
                } else {
                    handler.cancel();
                }
            }
        });
    }

    private void showBrowser() {
        browserVisible = true;
        browserPanel.setVisibility(View.VISIBLE);
        statusPanel.setVisibility(View.GONE);
        tabBrowser.setBackgroundColor(COLOR_TAB_SELECTED);
        tabStatus.setBackgroundColor(COLOR_TAB_NORMAL);
        maybeLoadBrowser();
    }

    private void showStatus() {
        browserVisible = false;
        browserPanel.setVisibility(View.GONE);
        statusPanel.setVisibility(View.VISIBLE);
        tabBrowser.setBackgroundColor(COLOR_TAB_NORMAL);
        tabStatus.setBackgroundColor(COLOR_TAB_SELECTED);
        refreshLog();
    }

    /**
     * 根据服务器就绪状态决定浏览器页显示内容：
     * 就绪且尚未加载过 → 打开 /_utils/；未就绪 → 显示等待提示并覆盖 WebView。
     */
    private void maybeLoadBrowser() {
        if (!browserVisible) {
            return;
        }
        if (serverUp) {
            tvWait.setVisibility(View.GONE);
            if (!webViewLoaded) {
                String url = "http://127.0.0.1:" + prefs.getPort() + "/_utils/";
                webView.loadUrl(url);
                webViewLoaded = true;
            }
        } else {
            tvWait.setVisibility(View.VISIBLE);
            tvWait.setText("等待 Node 服务启动…\n（请先在「状态/日志」页启动服务）");
        }
    }

    /** 服务器就绪状态变化（由轮询得出）。 */
    private void setServerUp(boolean up) {
        if (up == serverUp) {
            return;
        }
        serverUp = up;
        if (!up) {
            // 服务已停，下次就绪需要重新加载。
            webViewLoaded = false;
        }
        if (browserVisible) {
            maybeLoadBrowser();
        }
    }

    // ============ 服务器就绪轮询 ============

    private void startPolling() {
        schedulePoll(0);
    }

    private synchronized void schedulePoll(long delayMs) {
        if (!alive.get()) {
            return;
        }
        if (pollFuture != null && !pollFuture.isDone()) {
            pollFuture.cancel(false);
        }
        pollFuture = scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                if (!alive.get()) {
                    return;
                }
                final boolean up = checkServerUp();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        setServerUp(up);
                    }
                });
                // 就绪后降低探测频率；未就绪时更频繁，缩短等待。
                schedulePoll(up ? 3000 : 1500);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** 查控制端口 /status，running 为 true 才代表数据端口已真正监听。 */
    private boolean checkServerUp() {
        try {
            String s = Http.get("http://127.0.0.1:" + prefs.getControlPort() + "/status");
            JSONObject o = new JSONObject(s);
            return o.optBoolean("running", false);
        } catch (Exception e) {
            return false;
        }
    }

    // ============ 生命周期 ============

    @Override
    protected void onResume() {
        super.onResume();
        registerStateReceiver();
        refreshLog();
        startPolling();
    }

    @Override
    protected void onPause() {
        unregisterStateReceiver();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        alive.set(false);
        scheduler.shutdownNow();
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    /**
     * 动态注册状态广播（Android 14+ 必须显式 RECEIVER_NOT_EXPORTED，否则崩溃）。
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
    public void onBackPressed() {
        if (browserVisible && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    // ============ 启停服务 ============

    private void toggleService() {
        if (!ensureNotificationPermission()) {
            return;
        }
        Intent intent = new Intent(this, NodeService.class);
        if (runningFromRender) {
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
        // 启动后立即加速探测一次，浏览器能更快打开。
        schedulePoll(500);
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

    // 仅用于 toggle 判断当前是否处于运行态（来自广播）。
    private boolean runningFromRender;

    private void render(boolean isRunning, String message) {
        runningFromRender = isRunning;
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
