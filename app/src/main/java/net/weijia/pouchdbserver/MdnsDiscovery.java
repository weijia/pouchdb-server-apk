package net.weijia.pouchdbserver;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 局域网服务发现（Android NSD）。
 *
 * 仅做「发现」，不复用 MdnsAdvertiser 的宣告逻辑：监听 _couchdb._tcp，
 * 解析出每个对端的 host/IP、port、TXT（device_id / instance / version / http_port）。
 * 排除本机自身，并通过回调把当前 peer 列表推给 UI。
 *
 * 设计参考 go-stock-server 的 discovery（同服务类型、同 TXT 字段），但 Android 上
 * 必须用 NsdManager（5353 被系统占用、且需 MulticastLock）。
 */
public final class MdnsDiscovery {

    public static final String SERVICE_TYPE = "_couchdb._tcp";
    private static final String TAG = "MdnsDiscovery";
    private static final long RESCAN_INTERVAL_MS = 60_000;

    public interface Callback {
        void onPeers(List<Peer> peers);
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
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String, Peer> peers = new ConcurrentHashMap<>();
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private WifiManager.MulticastLock multicastLock;
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
        this.appContext = context.getApplicationContext();
        this.callback = callback;
        this.nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
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
            Log.w(TAG, "NsdManager 不可用");
            return;
        }
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
                Log.i(TAG, "发现已启动: " + regType);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "启动发现失败 code=" + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "停止发现失败 code=" + errorCode);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                // 只关注 _couchdb._tcp
                if (!SERVICE_TYPE.equals(serviceInfo.getServiceType())) return;
                nsdManager.resolveService(serviceInfo, resolveListener);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                // 无法仅凭 serviceInfo 精确定位 key（解析后才知 device_id），
                // 直接全量重扫刷新。
                Log.i(TAG, "服务丢失: " + serviceInfo.getServiceName());
                restart();
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(TAG, "发现已停止");
            }
        };
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            Log.w(TAG, "discoverServices 异常: " + e.getMessage());
        }
    }

    private final NsdManager.ResolveListener resolveListener = new NsdManager.ResolveListener() {
        @Override
        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
            Log.w(TAG, "解析失败 code=" + errorCode);
        }

        @Override
        public void onServiceResolved(NsdServiceInfo info) {
            InetAddress host = info.getHost();
            String ip = host != null ? host.getHostAddress() : "";
            int port = info.getPort();
            if (ip.isEmpty() || port <= 0) return;

            // 排除自身：IP 与数据端口都一致
            if (ip.equals(localIp) && port == localDataPort) {
                Log.i(TAG, "跳过自身: " + ip + ":" + port);
                return;
            }

            String deviceId = attr(info, "device_id");
            String instance = attr(info, "instance");
            if (instance.isEmpty()) instance = info.getServiceName();
            String version = attr(info, "version");
            Peer peer = new Peer(deviceId, instance, ip, port, version);
            peers.put(peer.key(), peer);
            Log.i(TAG, "发现对端: " + peer);
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
