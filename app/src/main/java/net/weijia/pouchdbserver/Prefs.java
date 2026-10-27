package net.weijia.pouchdbserver;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** 简单配置存储（端口、实例名、设备身份、配对开关、peer 凭据、admin 凭据）。 */
public final class Prefs {

    private static final String NAME = "pouchdb_server";
    private static final String KEY_PORT = "port";
    private static final String KEY_CONTROL_PORT = "control_port";
    private static final String KEY_INSTANCE = "instance";
    private static final String KEY_AUTH_USER = "auth_user";
    private static final String KEY_AUTH_PASS = "auth_pass";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_PAIRING_ENABLED = "pairing_enabled";
    private static final String KEY_PEER_CREDENTIALS = "peer_credentials"; // JSON: deviceId -> {user,pass}
    private static final String KEY_PAIRED_DEVICES = "paired_devices"; // JSON: deviceId -> name
    private static final String KEY_BATTERY_PROMPTED = "battery_prompted"; // 电池优化豁免引导是否已提示过

    public static final int DEFAULT_PORT = 5984;
    public static final int DEFAULT_CONTROL_PORT = 5985;

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public int getPort() {
        return sp.getInt(KEY_PORT, DEFAULT_PORT);
    }

    public void setPort(int port) {
        sp.edit().putInt(KEY_PORT, port).apply();
    }

    public int getControlPort() {
        return sp.getInt(KEY_CONTROL_PORT, DEFAULT_CONTROL_PORT);
    }

    public void setControlPort(int port) {
        sp.edit().putInt(KEY_CONTROL_PORT, port).apply();
    }

    /** 设备展示名（mDNS instance 名），可改名。 */
    public String getInstance() {
        return sp.getString(KEY_INSTANCE, "pouchdb-server");
    }

    public void setInstance(String instance) {
        sp.edit().putString(KEY_INSTANCE, instance).apply();
    }

    /**
     * 稳定的设备 id（UUID），首次访问时生成并持久化；改名不影响它。
     * 用于在局域网内唯一标识本机、作为 peer 凭据的索引 key。
     */
    public String getDeviceId() {
        String id = sp.getString(KEY_DEVICE_ID, null);
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            sp.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    /** 配对开关：默认关闭（不可信网络下不接受 /_pair/request）。 */
    public boolean isPairingEnabled() {
        return sp.getBoolean(KEY_PAIRING_ENABLED, false);
    }

    public void setPairingEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_PAIRING_ENABLED, enabled).apply();
    }

    /** admin 账号（用于 WebView/Fauxton 自动登录与本地鉴权）。 */
    public String getAuthUser() {
        return sp.getString(KEY_AUTH_USER, "");
    }

    public void setAuthUser(String user) {
        sp.edit().putString(KEY_AUTH_USER, user == null ? "" : user).apply();
    }

    public String getAuthPass() {
        return sp.getString(KEY_AUTH_PASS, "");
    }

    public void setAuthPass(String pass) {
        sp.edit().putString(KEY_AUTH_PASS, pass == null ? "" : pass).apply();
    }

    /** 取某 peer（deviceId）的凭据；无则返回 null。 */
    public PeerCredential getPeerCredential(String deviceId) {
        Map<String, PeerCredential> all = getAllPeerCredentials();
        return all.get(deviceId);
    }

    public Map<String, PeerCredential> getAllPeerCredentials() {
        Map<String, PeerCredential> map = new HashMap<>();
        String raw = sp.getString(KEY_PEER_CREDENTIALS, null);
        if (raw == null) return map;
        try {
            JSONObject o = new JSONObject(raw);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject v = o.getJSONObject(k);
                map.put(k, new PeerCredential(v.optString("user"), v.optString("pass")));
            }
        } catch (JSONException e) {
            // 损坏则忽略
        }
        return map;
    }

    public void putPeerCredential(String deviceId, String user, String pass) {
        Map<String, PeerCredential> all = getAllPeerCredentials();
        all.put(deviceId, new PeerCredential(user, pass));
        savePeerCredentials(all);
    }

    public void removePeerCredential(String deviceId) {
        Map<String, PeerCredential> all = getAllPeerCredentials();
        all.remove(deviceId);
        savePeerCredentials(all);
    }

    private void savePeerCredentials(Map<String, PeerCredential> all) {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, PeerCredential> e : all.entrySet()) {
                JSONObject v = new JSONObject();
                v.put("user", e.getValue().user);
                v.put("pass", e.getValue().pass);
                o.put(e.getKey(), v);
            }
        } catch (JSONException ignored) {
        }
        sp.edit().putString(KEY_PEER_CREDENTIALS, o.toString()).apply();
    }

    /** peer 凭据数据类。 */
    public static final class PeerCredential {
        public final String user;
        public final String pass;

        public PeerCredential(String user, String pass) {
            this.user = user;
            this.pass = pass;
        }
    }

    /** 已配对设备集合（deviceId -> 展示名），双向（本方发起或对方发起）都记录，用于 UI 标记。 */
    public Map<String, String> getPairedDevices() {
        Map<String, String> map = new HashMap<>();
        String raw = sp.getString(KEY_PAIRED_DEVICES, null);
        if (raw == null) return map;
        try {
            JSONObject o = new JSONObject(raw);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                map.put(k, o.optString(k));
            }
        } catch (JSONException e) {
            // 忽略损坏
        }
        return map;
    }

    public void putPairedDevice(String deviceId, String name) {
        Map<String, String> all = getPairedDevices();
        all.put(deviceId, name == null ? "" : name);
        saveStringMap(KEY_PAIRED_DEVICES, all);
    }

    public void removePairedDevice(String deviceId) {
        Map<String, String> all = getPairedDevices();
        all.remove(deviceId);
        saveStringMap(KEY_PAIRED_DEVICES, all);
    }

    private void saveStringMap(String key, Map<String, String> map) {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, String> e : map.entrySet()) {
                o.put(e.getKey(), e.getValue());
            }
        } catch (JSONException ignored) {
        }
        sp.edit().putString(key, o.toString()).apply();
    }

    /** 电池优化豁免引导是否已提示过（避免每次启动都弹窗打扰）。 */
    public boolean isBatteryPrompted() {
        return sp.getBoolean(KEY_BATTERY_PROMPTED, false);
    }

    public void setBatteryPrompted() {
        sp.edit().putBoolean(KEY_BATTERY_PROMPTED, true).apply();
    }
}
