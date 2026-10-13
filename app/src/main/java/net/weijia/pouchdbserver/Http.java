package net.weijia.pouchdbserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 极简 HTTP 客户端：只用于访问本机回环地址的控制端口/状态端点。 */
public final class Http {

    private static final int TIMEOUT_MS = 4000;

    private Http() {
    }

    public static String get(String url) throws IOException {
        HttpURLConnection conn = open(url, "GET");
        return read(conn);
    }

    public static String post(String url) throws IOException {
        HttpURLConnection conn = open(url, "POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Length", "0");
        OutputStream os = conn.getOutputStream();
        os.flush();
        os.close();
        return read(conn);
    }

    private static HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestMethod(method);
        conn.setUseCaches(false);
        return conn;
    }

    private static String read(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
