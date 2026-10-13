package net.weijia.pouchdbserver;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/** 获取局域网 IPv4 地址，用于展示访问地址和 mDNS 宣告。 */
public final class NetUtils {

    private NetUtils() {
    }

    /** 优先取 WiFi 地址，其次遍历网卡找局域网 IPv4。 */
    public static String getLanIp(Context context) {
        String wifiIp = getWifiIp(context);
        if (wifiIp != null) {
            return wifiIp;
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // 无网络时返回 null
        }
        return null;
    }

    private static String getWifiIp(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) {
                return null;
            }
            int ip = wifi.getConnectionInfo() == null ? 0 : wifi.getConnectionInfo().getIpAddress();
            if (ip == 0) {
                return null;
            }
            return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
        } catch (Exception e) {
            return null;
        }
    }
}
