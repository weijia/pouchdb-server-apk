package net.weijia.pouchdbserver;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * mDNS/Bonjour 服务宣告（Android NSD）。
 *
 * 设计参考 go-stock-server 的 discovery_mdns.go：
 * - 服务类型固定为 _couchdb._tcp，便于 CouchDB/PouchDB 客户端直接发现
 * - TXT 里带上 version / http_port / instance，查询方无需额外请求即可拿到端口
 * - 除了被动响应查询（NSD 内部处理），还做周期性自检：
 *   IP 变化 / 注册失败后自动重新宣告，避免换网、休眠后服务消失
 */
public final class MdnsAdvertiser {

    public static final String SERVICE_TYPE = "_couchdb._tcp";
    private static final String TAG = "MdnsAdvertiser";
    private static final long SELF_HEAL_INTERVAL_MS = 60_000; // 自检兜底周期

    public interface Callback {
        void onState(String state, String detail);
    }

    private final Context appContext;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private WifiManager.MulticastLock multicastLock;
    private final Runnable selfHeal = new Runnable() {
        @Override
        public void run() {
            selfHealCheck();
            handler.postDelayed(this, SELF_HEAL_INTERVAL_MS);
        }
    };

    private String instanceName;
    private int port;
    private String deviceId = "";
    private boolean registered;
    private boolean started;
    private int retryCount;

    public MdnsAdvertiser(Context context, Callback callback) {
        this.appContext = context.getApplicationContext();
        this.callback = callback;
        this.nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
    }

    public synchronized void start(String name, int httpPort, String deviceId) {
        if (started) {
            stop();
        }
        started = true;
        instanceName = sanitize(name);
        port = httpPort;
        this.deviceId = deviceId != null ? deviceId : "";
        retryCount = 0;
        acquireMulticastLock();
        register();
        handler.removeCallbacks(selfHeal);
        handler.postDelayed(selfHeal, SELF_HEAL_INTERVAL_MS);
    }

    public synchronized void stop() {
        started = false;
        handler.removeCallbacks(selfHeal);
        unregister();
        releaseMulticastLock();
        notifyState("stopped", "");
    }

    /** 端口变化时调用，内部会重新宣告。 */
    public synchronized void updatePort(int httpPort) {
        if (port == httpPort && registered) {
            return;
        }
        port = httpPort;
        if (started) {
            unregister();
            register();
        }
    }

    private void register() {
        if (nsdManager == null) {
            notifyState("error", "NsdManager 不可用");
            return;
        }
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(instanceName);
        info.setServiceType(SERVICE_TYPE);
        info.setPort(port);
        info.setAttribute("service", "pouchdb-server");
        info.setAttribute("version", "1.0");
        info.setAttribute("http_port", String.valueOf(port));
        info.setAttribute("instance", instanceName);
        if (!deviceId.isEmpty()) {
            info.setAttribute("device_id", deviceId);
        }

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.w(TAG, "注册失败: " + errorCode);
                registered = false;
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && retryCount < 5) {
                    // 名字冲突：加后缀重试
                    retryCount++;
                    instanceName = instanceName + "-" + retryCount;
                    notifyState("conflict", "实例名冲突，改用 " + instanceName);
                    register();
                    return;
                }
                notifyState("error", "注册失败 code=" + errorCode);
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.w(TAG, "注销失败: " + errorCode);
            }

            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                registered = true;
                Log.i(TAG, "已注册: " + serviceInfo.getServiceName() + " " + SERVICE_TYPE + ":" + port);
                notifyState("registered", serviceInfo.getServiceName() + "." + SERVICE_TYPE);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                registered = false;
            }
        };

        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener);
            notifyState("registering", instanceName + "." + SERVICE_TYPE);
        } catch (Exception e) {
            notifyState("error", e.getMessage());
        }
    }

    private void unregister() {
        if (nsdManager != null && registrationListener != null && registered) {
            try {
                nsdManager.unregisterService(registrationListener);
            } catch (Exception e) {
                Log.w(TAG, "注销异常: " + e.getMessage());
            }
        }
        registered = false;
        registrationListener = null;
    }

    /** 周期性自检：网络变化后重新宣告，保证局域网内始终可发现。 */
    private synchronized void selfHealCheck() {
        if (!started) {
            return;
        }
        if (!registered) {
            Log.i(TAG, "自检发现未注册，重新宣告");
            register();
        }
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && multicastLock == null) {
                multicastLock = wifi.createMulticastLock("pouchdb-server-mdns");
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

    // 注意：不要命名为 notify()/notifyAll()，会与 Object 的 final 方法在内部类中冲突
    private void notifyState(String state, String detail) {
        if (callback != null) {
            callback.onState(state, detail);
        }
    }

    private static String sanitize(String name) {
        String safe = name == null ? "" : name.trim();
        // 只处理会破坏 DNS-SD 名称结构的字符：'.' 是标签分隔符，控制字符直接去掉。
        // 其余字符（含中文手机型号，如「小米 14」）保留——DNS-SD 名称按 UTF-8 编码，
        // 系统 NsdManager 支持，替换成 '-' 反而让对端看到一串横线。
        safe = safe.replace('.', '_').replaceAll("\\p{Cntrl}", "");
        if (safe.isEmpty()) {
            safe = "pouchdb-server";
        }
        return truncateUtf8(safe, 63);
    }

    /** DNS-SD 单个标签上限 63 字节；按 UTF-8 字节长度安全截断（不切断多字节字符）。 */
    private static String truncateUtf8(String s, int maxBytes) {
        if (s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= maxBytes) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int len = new String(Character.toChars(cp))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes + len > maxBytes) break;
            sb.appendCodePoint(cp);
            bytes += len;
            i += Character.charCount(cp);
        }
        return sb.toString();
    }
}
