package net.weijia.pouchdbserver;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.app.AlertDialog;
import android.view.View;
import android.webkit.HttpAuthHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主界面：默认是内嵌浏览器（加载本机 admin 管理页面 /_admin/），
 * 可切换到「状态/日志」页启停 Node 服务。
 *
 * 浏览器页必须等待 Node 数据端口（5984）真正监听后才打开网页：
 * 通过轮询控制端口（5985）/status 的 running 字段来判断就绪，
 * 因为广播里的 running 只代表 Node 进程已拉起，不代表 HTTP 已可服务。
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int REQ_NOTIFICATION = 1001;
    private static final int REQ_IGNORE_BATTERY = 1002;

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
    private TextView tvDb;
    private Button btnToggle;
    private Button btnRefresh;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean browserVisible = true;
    private boolean serverUp;
    private boolean webViewLoaded;
    private boolean receiverRegistered;
    private org.json.JSONArray lastDatabases;

    // ===== 局域网发现 / 配对 / 同步 =====
    private MdnsDiscovery mdns;
    private final Map<String, MdnsDiscovery.Peer> peerMap = new HashMap<>();
    private EditText etAdminUser;
    private EditText etAdminPass;
    private Switch swPairing;
    private android.widget.LinearLayout peerContainer;
    private android.widget.LinearLayout approvalContainer;
    private final ScheduledExecutorService approvalScheduler =
            Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> approvalFuture;

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
        tvDb = findViewById(R.id.tvDb);
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
                schedulePoll(0);
            }
        });

        // ===== 局域网发现 / 配对 / 同步 视图绑定 =====
        prefs = new Prefs(this);
        etAdminUser = findViewById(R.id.etAdminUser);
        etAdminPass = findViewById(R.id.etAdminPass);
        swPairing = findViewById(R.id.swPairing);
        peerContainer = findViewById(R.id.peerContainer);
        approvalContainer = findViewById(R.id.approvalContainer);

        etAdminUser.setText(prefs.getAuthUser());
        etAdminPass.setText(prefs.getAuthPass());
        swPairing.setChecked(prefs.isPairingEnabled());

        findViewById(R.id.btnAdminSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAdmin();
            }
        });
        swPairing.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                setPairingEnabled(isChecked);
            }
        });

        // 默认进入浏览器页（未就绪时显示等待提示）。
        maybeRequestBatteryOptimizationExemption();
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
        renderDatabases();
        refreshLog();
    }

    /**
     * 根据服务器就绪状态决定浏览器页显示内容：
     * 就绪且尚未加载过 → 打开本机 admin 管理页 /_admin/（与网页管理端完全一致）；
     * 未就绪 → 显示等待提示并覆盖 WebView。
     */
    private void maybeLoadBrowser() {
        if (!browserVisible) {
            return;
        }
        if (serverUp) {
            tvWait.setVisibility(View.GONE);
            if (!webViewLoaded) {
                String url = "http://127.0.0.1:" + prefs.getPort() + "/_admin/";
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

    /** 把每个数据库名与其 leveldb 目录占盘大小渲染到状态页。 */
    private void renderDatabases() {
        if (tvDb == null) {
            return;
        }
        if (lastDatabases == null || lastDatabases.length() == 0) {
            tvDb.setText("（无数据库 / 服务未启动）");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lastDatabases.length(); i++) {
            org.json.JSONObject d = lastDatabases.optJSONObject(i);
            if (d == null) {
                continue;
            }
            String name = d.optString("name", "?");
            long size = d.optLong("size", -1);
            sb.append(name).append("    ");
            sb.append(size >= 0 ? formatSize(size) : "大小未知");
            sb.append("\n");
        }
        tvDb.setText(sb.toString());
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double v = bytes;
        String[] units = {"KB", "MB", "GB"};
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format("%.1f %s", v, units[i]);
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
                        renderDatabases();
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
            lastDatabases = o.optJSONArray("databases");
            return o.optBoolean("running", false);
        } catch (Exception e) {
            lastDatabases = null;
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
        startDiscovery();
        startApprovalPolling();
    }

    @Override
    protected void onPause() {
        unregisterStateReceiver();
        stopDiscovery();
        stopApprovalPolling();
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

    // ============ 局域网发现 / 配对 / 同步 ============

    private String controlUrl(String path) {
        return "http://127.0.0.1:" + prefs.getControlPort() + path;
    }

    /** 把 Java mDNS 发现到的对端列表上报给 Node，供 admin 管理页 /peers 展示。 */
    private void reportDiscoveredPeers(List<MdnsDiscovery.Peer> peers) {
        try {
            JSONArray devices = new JSONArray();
            for (MdnsDiscovery.Peer p : peers) {
                devices.put(new JSONObject()
                        .put("deviceId", p.deviceId == null ? "" : p.deviceId)
                        .put("instance", p.instance == null ? "" : p.instance)
                        .put("host", p.host)
                        .put("port", p.port)
                        .put("scheme", "http"));
            }
            JSONObject body = new JSONObject().put("devices", devices);
            Http.postJson(controlUrl("/peers/report"), body.toString(), null, null);
        } catch (Throwable t) {
            // 上报失败不影响 App 自身发现/配对。
        }
    }

    /** 配对成功后把对端写入 Node peers.json（含凭据），网页端与 App 端保持一致。 */
    private void reportPairResult(MdnsDiscovery.Peer peer, String user, String pass) {
        try {
            JSONObject body = new JSONObject()
                    .put("deviceId", peer.deviceId == null ? "" : peer.deviceId)
                    .put("instance", peer.instance == null ? "" : peer.instance)
                    .put("host", peer.host)
                    .put("port", peer.port)
                    .put("scheme", "http")
                    .put("user", user == null ? "" : user)
                    .put("pass", pass == null ? "" : pass);
            Http.postJson(controlUrl("/peer/register"), body.toString(), null, null);
        } catch (Throwable t) {
            // 忽略：下次仍可同步，仅网页端暂时看不到该对端。
        }
    }

    private void startDiscovery() {
        if (mdns == null) {
            mdns = new MdnsDiscovery(this, new MdnsDiscovery.Callback() {
                @Override
                public void onPeers(List<MdnsDiscovery.Peer> peers) {
                    peerMap.clear();
                    for (MdnsDiscovery.Peer p : peers) {
                        peerMap.put(p.key(), p);
                    }
                    // 发现靠安卓：把 mDNS 结果上报给 Node，admin 管理页 /peers 才能显示。
                    reportDiscoveredPeers(peers);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            renderPeers();
                        }
                    });
                }
            });
        }
        mdns.start(NetUtils.getLanIp(this), prefs.getPort());
    }

    private void stopDiscovery() {
        if (mdns != null) {
            mdns.stop();
        }
    }

    /** 渲染发现的设备列表：未配对显示「配对」，已配对显示「已配对 ✓」+「同步」。 */
    private void renderPeers() {
        if (peerContainer == null) return;
        peerContainer.removeAllViews();
        Map<String, String> paired = prefs.getPairedDevices();
        for (final MdnsDiscovery.Peer peer : peerMap.values()) {
            boolean isPaired = paired.containsKey(peer.key());
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setPadding(0, 8, 0, 8);

            TextView label = new TextView(this);
            label.setText(peer.toString());
            label.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            row.addView(label);

            if (isPaired) {
                Button syncBtn = new Button(this);
                syncBtn.setText("同步");
                syncBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        doSync(peer);
                    }
                });
                row.addView(syncBtn);
            } else {
                Button pairBtn = new Button(this);
                pairBtn.setText("配对");
                pairBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        doPair(peer);
                    }
                });
                row.addView(pairBtn);
            }
            peerContainer.addView(row);
        }
        if (peerMap.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("（未发现其他 pouchdb-server 实例）");
            peerContainer.addView(empty);
        }
    }

    /** 向对端发起配对请求，轮询审批结果，获批后保存凭据。 */
    private void doPair(final MdnsDiscovery.Peer peer) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String body = new JSONObject()
                            .put("device_id", prefs.getDeviceId())
                            .put("name", prefs.getInstance())
                            .toString();
                    String resp = Http.postJson(
                            "http://" + peer.host + ":" + peer.port + "/_pair/request", body, null, null);
                    String token = new JSONObject(resp).optString("token", "");
                    if (token.isEmpty()) {
                        toast("配对请求失败：未返回 token");
                        return;
                    }
                    // 轮询状态，最多 ~60s
                    String user = "", pass = "";
                    for (int i = 0; i < 30; i++) {
                        Thread.sleep(2000);
                        String s = Http.get("http://" + peer.host + ":" + peer.port
                                + "/_pair/status?token=" + token);
                        String state = new JSONObject(s).optString("state", "");
                        if ("approved".equals(state)) {
                            user = new JSONObject(s).optString("user", "");
                            pass = new JSONObject(s).optString("pass", "");
                            break;
                        } else if ("denied".equals(state) || "notfound".equals(state)) {
                            toast("配对被拒绝");
                            return;
                        }
                    }
                    if (user.isEmpty() || pass.isEmpty()) {
                        toast("配对超时（对方未审批）");
                        return;
                    }
                    prefs.putPeerCredential(peer.key(), user, pass);
                    prefs.putPairedDevice(peer.key(), peer.instance);
                    // 把配对结果上报给 Node（写入 peers.json），admin 管理页才能按 peer 同步、
                    // /sync/status 才能聚合，网页端与 App 端保持一致。
                    reportPairResult(peer, user, pass);
                    toast("已与 " + peer.instance + " 配对");
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            renderPeers();
                        }
                    });
                } catch (Throwable t) {
                    toast("配对出错: " + t.getMessage());
                }
            }
        });
    }

    /** 拉取对端数据库列表（带配对凭据），勾选后触发同步。 */
    private void doSync(final MdnsDiscovery.Peer peer) {
        final Prefs.PeerCredential cred = prefs.getPeerCredential(peer.key());
        if (cred == null) {
            toast("尚未与该设备配对");
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String resp = Http.get("http://" + peer.host + ":" + peer.port + "/_all_dbs",
                            cred.user, cred.pass);
                    final JSONArray dbs = new JSONArray(resp);
                    final List<String> dbList = new ArrayList<>();
                    for (int i = 0; i < dbs.length(); i++) dbList.add(dbs.optString(i));

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (dbList.isEmpty()) {
                                toast("对方没有数据库");
                                return;
                            }
                            final boolean[] checked = new boolean[dbList.size()];
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("同步 " + peer.instance + " 的数据库")
                                    .setMultiChoiceItems(dbList.toArray(new String[0]), checked,
                                            new android.content.DialogInterface.OnMultiChoiceClickListener() {
                                                @Override
                                                public void onClick(android.content.DialogInterface dialog, int which, boolean isChecked) {
                                                    checked[which] = isChecked;
                                                }
                                            })
                                    .setPositiveButton("同步选中", new android.content.DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(android.content.DialogInterface dialog, int which) {
                                            for (int i = 0; i < dbList.size(); i++) {
                                                if (checked[i]) {
                                                    startReplicate(peer, dbList.get(i));
                                                }
                                            }
                                            toast("已提交同步任务");
                                        }
                                    })
                                    .setNegativeButton("取消", null)
                                    .show();
                        }
                    });
                } catch (Throwable t) {
                    toast("获取对方表失败: " + t.getMessage());
                }
            }
        });
    }

    private void startReplicate(final MdnsDiscovery.Peer peer, final String db) {
        final Prefs.PeerCredential cred = prefs.getPeerCredential(peer.key());
        if (cred == null) return;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String body = new JSONObject()
                            .put("db", db)
                            .put("remoteHost", peer.host)
                            .put("remotePort", peer.port)
                            .put("remoteUser", cred.user)
                            .put("remotePass", cred.pass)
                            .put("live", true)
                            .toString();
                    Http.postJson(controlUrl("/replicate"), body, null, null);
                } catch (Throwable t) {
                    toast("同步失败: " + t.getMessage());
                }
            }
        });
    }

    private void saveAdmin() {
        final String user = etAdminUser.getText().toString().trim();
        final String pass = etAdminPass.getText().toString();
        if (user.isEmpty() || pass.isEmpty()) {
            toast("用户名与密码均不能为空");
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String body = new JSONObject().put("user", user).put("pass", pass).toString();
                    Http.postJson(controlUrl("/admin/set"), body, null, null);
                    prefs.setAuthUser(user);
                    prefs.setAuthPass(pass);
                    toast("管理账号已保存");
                } catch (Throwable t) {
                    toast("保存失败: " + t.getMessage());
                }
            }
        });
    }

    private void setPairingEnabled(final boolean enabled) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String body = new JSONObject().put("enabled", enabled).toString();
                    Http.postJson(controlUrl("/pairing/set"), body, null, null);
                    prefs.setPairingEnabled(enabled);
                } catch (Throwable t) {
                    toast("设置失败: " + t.getMessage());
                }
            }
        });
    }

    private void startApprovalPolling() {
        if (approvalFuture != null && !approvalFuture.isDone()) return;
        approvalFuture = approvalScheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                pollApprovals();
            }
        }, 0, 3, TimeUnit.SECONDS);
    }

    private void stopApprovalPolling() {
        if (approvalFuture != null) {
            approvalFuture.cancel(true);
            approvalFuture = null;
        }
    }

    /** 拉取本机待审批的配对请求并渲染。 */
    private void pollApprovals() {
        try {
            String resp = Http.get(controlUrl("/pair/requests"));
            final JSONArray reqs = new JSONObject(resp).optJSONArray("requests");
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    renderApprovals(reqs);
                }
            });
        } catch (Throwable t) {
            // 服务未就绪时静默忽略
        }
    }

    private void renderApprovals(JSONArray reqs) {
        if (approvalContainer == null) return;
        approvalContainer.removeAllViews();
        if (reqs == null || reqs.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("（无待审批请求）");
            approvalContainer.addView(empty);
            return;
        }
        for (int i = 0; i < reqs.length(); i++) {
            final JSONObject r = reqs.optJSONObject(i);
            if (r == null) continue;
            final String token = r.optString("token", "");
            final String name = r.optString("name", "未知设备");

            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setPadding(0, 8, 0, 8);

            TextView label = new TextView(this);
            label.setText(name + " 请求访问");
            label.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            row.addView(label);

            Button allow = new Button(this);
            allow.setText("允许");
            final String devId = r.optString("deviceId", "");
            allow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    doApprove(token, devId, name);
                }
            });
            row.addView(allow);

            Button deny = new Button(this);
            deny.setText("拒绝");
            deny.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    doDeny(token);
                }
            });
            row.addView(deny);

            approvalContainer.addView(row);
        }
    }

    private void doApprove(final String token, final String deviceId, final String name) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String body = new JSONObject().put("token", token).toString();
                    Http.postJson(controlUrl("/pair/approve"), body, null, null);
                    // 记录对方为已配对（反向凭据由对方经其轮询 /_pair/status 获取后写入 peer_credentials）。
                    // 以 deviceId 为 key，与 peer 的 key() 一致，UI 才能标记「已配对」。
                    if (!deviceId.isEmpty()) prefs.putPairedDevice(deviceId, name);
                    toast("已允许 " + name);
                    pollApprovals();
                } catch (Throwable t) {
                    toast("审批失败: " + t.getMessage());
                }
            }
        });
    }

    private void doDeny(final String token) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String body = new JSONObject().put("token", token).toString();
                    Http.postJson(controlUrl("/pair/deny"), body, null, null);
                    pollApprovals();
                } catch (Throwable t) {
                    toast("拒绝失败: " + t.getMessage());
                }
            }
        });
    }

    /**
     * 引导用户把本应用从电池优化中豁免（Android 6+，仅提示一次）。
     * 黑屏后系统进入 Doze，若应用被电池优化限制，进程会被冻结、网络被掐断，
     * 即使连有线网络也一样；豁免后才能作为局域网常驻服务器稳定运行。
     */
    private void maybeRequestBatteryOptimizationExemption() {
        if (prefs.isBatteryPrompted()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(i, REQ_IGNORE_BATTERY);
                    // 无论用户是否允许，都不再重复打扰（仍可去系统设置里手动豁免）。
                    prefs.setBatteryPrompted();
                }
            } catch (Throwable t) {
                Log.w(TAG, "无法请求电池优化豁免（可去系统设置手动豁免）: " + t.getMessage());
            }
        }
    }

    private void toast(final String msg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
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
