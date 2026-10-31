package net.weijia.pouchdbserver;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 局域网服务发现（Android NSD）。
 *
 * 监听 _couchdb._tcp，解析出每个对端的 host/IP、port、TXT（device_id / instance / version）。
 * 排除本机自身，并通过回调把当前 peer 列表推给 UI。
 *
 * 两个必须遵守的约束（都踩过）：
 *  1) NsdManager 同一时间只能有一个 resolve 在进行，并发调用会失败或回调错乱，
 *     因此这里用队列串行解析 + 失败重试 + 超时兜底。
 *  2) getServiceType() 在不同 ROM 上可能是 "_couchdb._tcp" / "_couchdb._tcp." /
 *     带域名后缀，用 equals 精确比较会把所有结果丢掉（且完全静默）——改用前缀匹配。
 */
public final class MdnsDiscovery {

    public static final String SERVICE_TYPE = "_couchdb._tcp";
    private static final String TAG = "MdnsDiscovery";
    private static final long RESCAN_INTERVAL_MS = 60_000;

    // 解析串行化参数：间隔太短在部分 ROM 上会 FAILURE_ALREADY_ACTIVE
    private static final long RESOLVE_GAP_MS = 400;
    private static final long RESOLVE_RETRY_DELAY_MS = 1_200;
    private static final int RESOLVE_RETRY_MAX = 2;
    private static final long RESOLVE_TIMEOUT_MS = 5_000;

    public interface Callback {
        void onPeers(List<Peer> peers);
    }

    /** 诊断日志出口（接到 boot.log，App 日志页可见）。 */
    public interface LogSink {
        void log(String msg);
    }

    /** 发现到的对端实例。 */
    public static final class Peer {
        public final String deviceId;  // TXT device_id，可能为空
        public final String instance;  // TXT instance（展示名）
        public final String host;      // 解析出的 IP
        public final int port;         // 数据端口
        public final String version;

        public Peer(String deviceId, String instance, String host, int port, String version) {
            this.deviceId = deviceId == null ? "" : deviceId;
            this.instance = instance == null ? "" : instance;
            this.host = host;
            this.port = port;
            this.version = version == null ? "" : version;
        }

        public String key() {
            return (deviceId != null && !deviceId.isEmpty()) ? deviceId : (instance + "@" + host + ":" + port);
        }

        @Override
        public String toString() {
            return instance + (deviceId.isEmpty() ? "" : "[" + deviceId + "]") + " " + host + ":" + port;
        }
    }

    private final Context appContext;
    private final Callback callback;
    private final LogSink logSink;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String, Peer> peers = new ConcurrentHashMap<>();
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private WifiManager.MulticastLock multicastLock;

    // 解析队列（串行化，规避 NsdManager 并发限制）
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private final Set<String> queuedNames = new HashSet<>();
    private final HashMap<String, Integer> resolveAttempts = new HashMap<>();
    private boolean resolveInFlight;
    private String resolveCurrent = "";

    private final Runnable rescan = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            // 周期性触发一次「停止再开始」以刷新可能僵死的发现会话
            restart();
            handler.postDelayed(this, RESCAN_INTERVAL_MS);
        }
    };

    private String localIp = "";
    private int localDataPort;
    private boolean started;

    public MdnsDiscovery(Context context, Callback callback) {
        this(context, callback, null);
    }

    public MdnsDiscovery(Context context, Callback callback, LogSink logSink) {
        this.appContext = context.getApplicationContext();
        this.callback = callback;
        this.logSink = logSink;
        this.nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        if (logSink != null) {
            try {
                logSink.log("mDNS " + msg);
            } catch (Throwable ignored) {
            }
        }
    }

    public synchronized void start(String localIp, int localDataPort) {
        if (started) return;
        this.localIp = localIp != null ? localIp : "";
        this.localDataPort = localDataPort;
        started = true;
        acquireMulticastLock();
        beginDiscovery();
        handler.removeCallbacks(rescan);
        handler.postDelayed(rescan, RESCAN_INTERVAL_MS);
    }

    public synchronized void stop() {
        started = false;
        handler.removeCallbacks(rescan);
        stopDiscovery();
        releaseMulticastLock();
        clearResolveState();
        peers.clear();
        notifyPeers();
    }

    /** 设置（更新）本机信息，用于排除自身。 */
    public synchronized void setLocal(String localIp, int localDataPort) {
        this.localIp = localIp != null ? localIp : "";
        this.localDataPort = localDataPort;
    }

    private void restart() {
        stopDiscovery();
        beginDiscovery();
    }

    private void beginDiscovery() {
        if (nsdManager == null) {
            log("发现不可用: NsdManager 为空");
            return;
        }
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
                log("发现已启动: " + regType);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                log("启动发现失败 code=" + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                log("停止发现失败 code=" + errorCode);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                String type = serviceInfo.getServiceType();
                // 不用 equals：部分 ROM 会带尾点或域名后缀，精确比较会静默丢掉全部结果
                if (type == null || !type.startsWith(SERVICE_TYPE)) {
                    log("忽略非目标服务: " + serviceInfo.getServiceName() + " type=" + type);
                    return;
                }
                log("发现服务: " + serviceInfo.getServiceName() + " type=" + type);
                enqueueResolve(serviceInfo);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                // 无法仅凭 serviceInfo 精确定位 key（解析后才知 device_id），
                // 直接全量重扫刷新。
                log("服务丢失: " + serviceInfo.getServiceName());
                restart();
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                log("发现已停止");
            }
        };
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            log("discoverServices 异常: " + e.getMessage());
        }
    }

    // ==================== 串行解析（NsdManager 一次只能 resolve 一个） ====================

    private void enqueueResolve(NsdServiceInfo info) {
        String name = info.getServiceName();
        if (name == null || name.isEmpty()) return;
        synchronized (this) {
            if (!started || queuedNames.contains(name)) return;
            queuedNames.add(name);
            resolveQueue.addLast(info);
        }
        pumpResolve();
    }

    private void pumpResolve() {
        final NsdServiceInfo next;
        synchronized (this) {
            if (resolveInFlight || !started || resolveQueue.isEmpty()) return;
            next = resolveQueue.pollFirst();
            if (next == null) return;
            resolveInFlight = true;
            resolveCurrent = next.getServiceName();
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!started || nsdManager == null) {
                        finishResolve(next, false);
                        return;
                    }
                    nsdManager.resolveService(next, resolveListener);
                    scheduleResolveTimeout(next.getServiceName());
                } catch (Throwable t) {
                    log("解析调用异常: " + t.getMessage());
                    finishResolve(next, true);
                }
            }
        }, RESOLVE_GAP_MS);
    }

    /** 回调丢失兜底：NsdManager 偶尔不回调，不兜底会永久卡住解析队列。 */
    private void scheduleResolveTimeout(final String name) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (MdnsDiscovery.this) {
                    if (!resolveInFlight || !name.equals(resolveCurrent)) return;
                    resolveInFlight = false;
                    resolveCurrent = "";
                    queuedNames.remove(name);
                }
                log("解析超时: " + name);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        pumpResolve();
                    }
                }, RESOLVE_GAP_MS);
            }
        }, RESOLVE_TIMEOUT_MS);
    }

    private void finishResolve(NsdServiceInfo info, boolean failed) {
        String name = info != null ? info.getServiceName() : "";
        synchronized (this) {
            if (name.equals(resolveCurrent)) {
                resolveInFlight = false;
                resolveCurrent = "";
            }
            queuedNames.remove(name);
        }
        if (failed && info != null) {
            int attempt = resolveAttempts.containsKey(name) ? resolveAttempts.get(name) : 0;
            if (attempt < RESOLVE_RETRY_MAX) {
                resolveAttempts.put(name, attempt + 1);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        enqueueResolve(info);
                    }
                }, RESOLVE_RETRY_DELAY_MS);
            } else {
                resolveAttempts.remove(name);
            }
        } else if (info != null) {
            resolveAttempts.remove(name);
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                pumpResolve();
            }
        }, RESOLVE_GAP_MS);
    }

    private void clearResolveState() {
        synchronized (this) {
            resolveQueue.clear();
            queuedNames.clear();
            resolveAttempts.clear();
            resolveInFlight = false;
            resolveCurrent = "";
        }
    }

    private final NsdManager.ResolveListener resolveListener = new NsdManager.ResolveListener() {
        @Override
        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
            log("解析失败 code=" + errorCode + " name="
                    + (serviceInfo != null ? serviceInfo.getServiceName() : "?"));
            finishResolve(serviceInfo, true);
        }

        @Override
        public void onServiceResolved(NsdServiceInfo info) {
            finishResolve(info, false);
            InetAddress host = info.getHost();
            String ip = host != null ? host.getHostAddress() : "";
            int port = info.getPort();
            if (ip.isEmpty() || port <= 0) {
                log("解析结果无效: ip=" + ip + " port=" + port);
                return;
            }

            // 排除自身：IP 与数据端口都一致
            if (ip.equals(localIp) && port == localDataPort) {
                log("跳过自身: " + ip + ":" + port);
                return;
            }

            String deviceId = attr(info, "device_id");
            String instance = attr(info, "instance");
            if (instance.isEmpty()) instance = info.getServiceName();
            String version = attr(info, "version");
            Peer peer = new Peer(deviceId, instance, ip, port, version);
            peers.put(peer.key(), peer);
            log("发现对端: " + peer);
            notifyPeers();
        }
    };

    private static String attr(NsdServiceInfo info, String key) {
        try {
            byte[] v = info.getAttributes().get(key);
            return v != null ? new String(v, java.nio.charset.StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void stopDiscovery() {
        if (nsdManager != null && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception ignored) {
            }
        }
        discoveryListener = null;
    }

    private synchronized void notifyPeers() {
        if (callback != null) {
            final List<Peer> snapshot = new ArrayList<>(peers.values());
            Collections.sort(snapshot, (a, b) -> a.toString().compareTo(b.toString()));
            handler.post(new Runnable() {
                @Override
                public void run() {
                    callback.onPeers(snapshot);
                }
            });
        }
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && multicastLock == null) {
                multicastLock = wifi.createMulticastLock("pouchdb-discovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
                log("组播锁已获取");
            }
        } catch (Exception e) {
            log("获取组播锁失败: " + e.getMessage());
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
