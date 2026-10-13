package net.weijia.pouchdbserver;

import android.content.Context;
import android.content.SharedPreferences;

/** 简单配置存储（端口、实例名、可选的 Basic Auth）。 */
public final class Prefs {

    private static final String NAME = "pouchdb_server";
    private static final String KEY_PORT = "port";
    private static final String KEY_CONTROL_PORT = "control_port";
    private static final String KEY_INSTANCE = "instance";
    private static final String KEY_AUTH_USER = "auth_user";
    private static final String KEY_AUTH_PASS = "auth_pass";

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

    public String getInstance() {
        return sp.getString(KEY_INSTANCE, "pouchdb-server");
    }

    public void setInstance(String instance) {
        sp.edit().putString(KEY_INSTANCE, instance).apply();
    }

    public String getAuthUser() {
        return sp.getString(KEY_AUTH_USER, "");
    }

    public void setAuthUser(String user) {
        sp.edit().putString(KEY_AUTH_USER, user).apply();
    }

    public String getAuthPass() {
        return sp.getString(KEY_AUTH_PASS, "");
    }

    public void setAuthPass(String pass) {
        sp.edit().putString(KEY_AUTH_PASS, pass).apply();
    }
}
