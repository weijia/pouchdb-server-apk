package net.weijia.pouchdbserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 极简 HTTP 客户端：访问本机回环控制端口，以及局域网内其他实例的数据端口。 */
public final class Http {

    private static final int TIMEOUT_MS = 4000;

    private Http() {
    }

    /** GET（无鉴权）。 */
    public static String get(String url) throws IOException {
        return get(url, null, null);
    }

    /** GET（带可选 Basic Auth）。 */
    public static String get(String url, String user, String pass) throws IOException {
        HttpURLConnection conn = open(url, "GET");
        if (user != null && pass != null) {
            conn.setRequestProperty("Authorization", basic(user, pass));
        }
        return read(conn);
    }

    /** POST（无 body、无鉴权），用于本机控制端口如 /start。 */
    public static String post(String url) throws IOException {
        return postJson(url, null, null, null);
    }

    /** POST JSON（带可选 Basic Auth）。 */
    public static String postJson(String url, String jsonBody, String user, String pass) throws IOException {
        HttpURLConnection conn = open(url, "POST");
        if (user != null && pass != null) {
            conn.setRequestProperty("Authorization", basic(user, pass));
        }
        if (jsonBody != null && !jsonBody.isEmpty()) {
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
                os.flush();
            }
        }
        return read(conn);
    }

    private static String basic(String user, String pass) {
        String raw = user + ":" + pass;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
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
