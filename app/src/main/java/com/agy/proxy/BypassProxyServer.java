package com.agy.proxy;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Local HTTP CONNECT proxy listening on 127.0.0.1:45162.
 * Intercepts Google gate endpoints and tunnels them through the configured AGU2 relay
 * using the AGU2 authenticated protocol with the device hardware ID.
 */
public final class BypassProxyServer {

    private static final String TAG = "BypassProxy";
    public static final int DEFAULT_PORT = 45162;

    private static final String PREF_NAME = "antigravity_proxy_prefs";
    private static final String KEY_ENABLED = "bypass_proxy_enabled";

    // Build-injected AES/HMAC secret for agk1 token unwrapping (empty in public clones)
    private static final String BAKED_T_HEX = com.agy.BuildConfig.AGU2_WRAP_KEY;

    private static final String[] DEFAULT_PROXY_SERVERS = com.agy.BuildConfig.AGU2_PROXY_SERVERS;
    private static final int DEFAULT_PROXY_PORT = 443;
    private static final String DEFAULT_ALPN = "agu/2";
    private static final String[] DEFAULT_GATE_HOSTS = new String[] {
            "cloudcode-pa.googleapis.com",
            "daily-cloudcode-pa.googleapis.com",
            "aicode.googleapis.com"
    };

    private volatile String[] proxyServers = DEFAULT_PROXY_SERVERS;
    private volatile int proxyPort = DEFAULT_PROXY_PORT;
    private volatile String alpnProtocol = DEFAULT_ALPN;
    private volatile String[] gateHosts = DEFAULT_GATE_HOSTS;
    private volatile String bypassVersion = "2.34.0";

    private static volatile BypassProxyServer instance;

    private final Context context;
    private final int port;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final SecureRandom random = new SecureRandom();

    private volatile boolean running = false;
    private volatile boolean enabled = true;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    private long tg = 0L;
    private String code = null;
    private long issued = 0L;
    private byte[] userKey = null;

    public static synchronized void start(Context context) {
        if (instance == null) {
            instance = new BypassProxyServer(context.getApplicationContext(), DEFAULT_PORT);
            instance.loadDynamicConfig();
            instance.startServer();
        }
    }

    public static synchronized void reloadConfig(Context context) {
        if (instance != null) {
            instance.loadDynamicConfig();
        }
    }

    public String getBypassVersion() {
        return bypassVersion;
    }

    private void loadDynamicConfig() {
        String jsonStr = null;
        File overrideFile = new File(context.getFilesDir(), "bypass_config.json");
        if (overrideFile.exists() && overrideFile.isFile()) {
            try (FileInputStream fis = new FileInputStream(overrideFile)) {
                byte[] buf = new byte[(int) Math.min(overrideFile.length(), 131072)];
                int n = fis.read(buf);
                if (n > 0) jsonStr = new String(buf, 0, n, StandardCharsets.UTF_8);
            } catch (Exception e) {
                Log.w(TAG, "Failed reading override bypass_config.json: " + e.getMessage());
            }
        }
        if (jsonStr == null) {
            try (InputStream is = context.getAssets().open("bypass_config.json")) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                jsonStr = baos.toString("UTF-8");
            } catch (Exception ignored) {
            }
        }
        if (jsonStr == null) return;
        try {
            org.json.JSONObject obj = new org.json.JSONObject(jsonStr);
            if (obj.has("version")) {
                String fileVer = obj.optString("version", "");
                if (!fileVer.isEmpty() && com.agy.updater.UpdatePrefs.compareVersions(fileVer, bypassVersion) < 0) {
                    Log.i(TAG, "Ignoring stale bypass_config.json v" + fileVer + " (built-in v" + bypassVersion + ")");
                    return;
                }
                if (!fileVer.isEmpty()) {
                    bypassVersion = fileVer;
                }
            }
            if (obj.has("proxy_port")) {
                int p = obj.optInt("proxy_port", DEFAULT_PROXY_PORT);
                if (p > 0 && p <= 65535) proxyPort = p;
            }
            if (obj.has("alpn")) {
                String a = obj.optString("alpn", DEFAULT_ALPN);
                if (!a.isEmpty()) alpnProtocol = a;
            }
            if (obj.has("proxy_servers")) {
                org.json.JSONArray arr = obj.getJSONArray("proxy_servers");
                if (arr.length() > 0) {
                    String[] list = new String[arr.length()];
                    for (int i = 0; i < arr.length(); i++) list[i] = arr.getString(i);
                    proxyServers = list;
                }
            }
            if (obj.has("gate_hosts")) {
                org.json.JSONArray arr = obj.getJSONArray("gate_hosts");
                if (arr.length() > 0) {
                    String[] list = new String[arr.length()];
                    for (int i = 0; i < arr.length(); i++) list[i] = arr.getString(i).toLowerCase().trim();
                    gateHosts = list;
                }
            }
            Log.i(TAG, "Loaded bypass config v" + bypassVersion + " (" + proxyServers.length + " servers, " + gateHosts.length + " gate hosts)");
        } catch (Exception e) {
            Log.w(TAG, "Invalid bypass_config.json: " + e.getMessage());
        }
    }

    public static synchronized void stop() {
        if (instance != null) {
            instance.stopServer();
            instance = null;
        }
    }

    public static BypassProxyServer getInstance() {
        return instance;
    }

    public static boolean isProxyEnabled(Context context) {
        if (context == null) return true;
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    public static void setProxyEnabled(Context context, boolean enabled) {
        if (context != null) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_ENABLED, enabled)
                    .apply();
        }
        if (instance != null) {
            instance.enabled = enabled;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean hasValidKey() {
        return userKey != null && userKey.length == 32 && code != null && !code.isEmpty() && tg > 0;
    }

    public String getActiveCode() {
        return code;
    }

    public static String getHardwareCode(Context context) {
        return getDeviceCode(context);
    }

    public static String getDeviceCode(Context context) {
        try {
            String aid = android.provider.Settings.Secure.getString(
                    context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            if (aid == null || aid.isEmpty()) {
                aid = "unknown";
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(("AGU-HWID-v1\nandroid-id:" + aid).getBytes(StandardCharsets.UTF_8));
            return crockfordBase32(h, 5);
        } catch (Exception e) {
            return "unknown";
        }
    }

    public static String getBotCommand(Context context) {
        return "/ag+" + getDeviceCode(context);
    }

    public static String getStatusSummary(Context context) {
        if (!isProxyEnabled(context)) {
            return "🔴 Отключено";
        }
        File lic = new File(context.getFilesDir(), "license_key.txt");
        if ((instance != null && instance.hasValidKey()) || lic.exists()) {
            return "🟢 Активно";
        }
        return "⚪ Ключ не установлен";
    }

    public static boolean verifyLicenseKey(String key) {
        if (key == null) return false;
        StringBuilder sb = new StringBuilder();
        for (char c : key.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toUpperCase(c));
            }
        }
        String k = sb.toString();
        if (k.length() != 24) return false;

        String nonce = k.substring(0, 12);
        String sig = k.substring(12);

        java.util.LinkedHashSet<String> versions = new java.util.LinkedHashSet<>();
        if (instance != null && instance.bypassVersion != null && !instance.bypassVersion.isEmpty()) {
            String bv = instance.bypassVersion.trim();
            String[] parts = bv.split("\\.");
            if (parts.length >= 2) {
                versions.add(parts[0] + "." + parts[1]);
            }
            versions.add(bv);
        }
        versions.add("2.34");
        versions.add("2.34.0");
        versions.add("2.32");
        versions.add("2.31.2");
        versions.add("2.31.1");
        versions.add("2.31.0");
        String baseSecret = ")Q.QFyU+oOs#Z:jmtxonGfZi+w#|XG4<";

        for (String v : versions) {
            String secret = baseSecret + "::" + v;
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update(nonce.getBytes(StandardCharsets.UTF_8));
                md.update(secret.getBytes(StandardCharsets.UTF_8));
                byte[] digest = md.digest();
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < 6; i++) {
                    hex.append(String.format("%02X", digest[i]));
                }
                if (hex.toString().equals(sig)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    public static synchronized String updateCredentials(Context context, String input) throws Exception {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("Ключ не может быть пустым");
        }
        String clean = input.trim();

        if (clean.length() == 24 || verifyLicenseKey(clean)) {
            if (verifyLicenseKey(clean)) {
                File licFile = new File(context.getFilesDir(), "license_key.txt");
                try (FileOutputStream fos = new FileOutputStream(licFile)) {
                    fos.write(clean.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
                return "Лицензионный ключ 2.34 успешно активирован!";
            } else {
                throw new IllegalArgumentException("Неверный лицензионный ключ (проверьте символы ключа из группы)");
            }
        }

        String plaintext;
        if (clean.startsWith("agk1.")) {
            plaintext = unwrapToken(clean);
        } else if (clean.startsWith("AGU2")) {
            plaintext = clean;
        } else {
            throw new IllegalArgumentException("Неверный формат ключа (ожидается ключ версии 2.34 или токен agk1...)");
        }

        File keyFile = new File(context.getFilesDir(), "bypass_key.txt");
        try (FileOutputStream fos = new FileOutputStream(keyFile)) {
            fos.write(plaintext.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }

        if (instance != null) {
            instance.parseKeyPlaintext(plaintext);
        } else {
            start(context);
        }

        return "Ключ успешно обновлен и применен!";
    }

    private static String crockfordBase32(byte[] data, int length) {
        final char[] ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();
        int val = 0;
        int bits = 0;
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(data.length, length);
        for (int i = 0; i < limit; i++) {
            val = (val << 8) | (data[i] & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(ALPHABET[(val >> bits) & 0x1f]);
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET[(val << (5 - bits)) & 0x1f]);
        }
        return sb.toString();
    }

    private static String unwrapToken(String token) throws Exception {
        String body = token.substring(5).trim();
        byte[] raw = Base64.decode(body, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        if (raw == null || raw.length < 33) {
            throw new IllegalArgumentException("Ключ поврежден (слишком короткий)");
        }
        byte[] nonce = Arrays.copyOfRange(raw, 0, 16);
        byte[] ct = Arrays.copyOfRange(raw, 16, raw.length - 16);
        byte[] tag = Arrays.copyOfRange(raw, raw.length - 16, raw.length);

        if (BAKED_T_HEX == null || BAKED_T_HEX.length() != 64) {
            throw new IllegalStateException("Ключ развёртывания AGU2 не встроен в эту публичную сборку (см. tools/.bypass_secrets.json)");
        }
        byte[] t = hexToBytes(BAKED_T_HEX);
        byte[] macKey = hmacSha256Bytes(t, "mac".getBytes(StandardCharsets.US_ASCII));

        byte[] msg = new byte[nonce.length + ct.length];
        System.arraycopy(nonce, 0, msg, 0, nonce.length);
        System.arraycopy(ct, 0, msg, nonce.length, ct.length);

        byte[] expectedTag = Arrays.copyOf(hmacSha256Bytes(macKey, msg), 16);
        if (!Arrays.equals(tag, expectedTag)) {
            throw new IllegalArgumentException("Ошибка проверки подписи ключа (HMAC mismatch)");
        }

        byte[] k = hmacSha256Bytes(t, "enc".getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        int i = 0;
        while (stream.size() < ct.length) {
            byte[] counterBytes = new byte[] {
                (byte) ((i >> 24) & 0xff),
                (byte) ((i >> 16) & 0xff),
                (byte) ((i >> 8) & 0xff),
                (byte) (i & 0xff)
            };
            byte[] blockMsg = new byte[nonce.length + 4];
            System.arraycopy(nonce, 0, blockMsg, 0, nonce.length);
            System.arraycopy(counterBytes, 0, blockMsg, nonce.length, 4);
            stream.write(hmacSha256Bytes(k, blockMsg));
            i++;
        }
        byte[] ks = stream.toByteArray();
        byte[] plain = new byte[ct.length];
        for (int j = 0; j < ct.length; j++) {
            plain[j] = (byte) (ct[j] ^ ks[j]);
        }
        return new String(plain, StandardCharsets.UTF_8);
    }

    private BypassProxyServer(Context context, int port) {
        this.context = context;
        this.port = port;
        this.enabled = isProxyEnabled(context);
        loadCustomCredentials();
    }

    private void loadCustomCredentials() {
        try {
            File keyFile = new File(context.getFilesDir(), "bypass_key.txt");
            if (keyFile.isFile()) {
                try (FileInputStream fis = new FileInputStream(keyFile)) {
                    byte[] b = new byte[(int) keyFile.length()];
                    int read = fis.read(b);
                    if (read > 0) {
                        String s = new String(b, 0, read, StandardCharsets.UTF_8).trim();
                        parseKeyPlaintext(s);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load custom bypass credentials: " + e.getMessage());
        }
    }

    private void parseKeyPlaintext(String s) {
        String[] lines = s.split("\n");
        if (lines.length >= 7 && "AGU2".equals(lines[0].trim())) {
            String hostLine = lines[1].trim();
            if ((this.proxyServers == null || this.proxyServers.length == 0)
                    && !hostLine.isEmpty() && !"proxy.example".equals(hostLine)) {
                this.proxyServers = new String[]{hostLine};
                try {
                    int p = Integer.parseInt(lines[2].trim());
                    if (p > 0 && p <= 65535) this.proxyPort = p;
                } catch (NumberFormatException ignored) {}
            }
            this.tg = Long.parseLong(lines[3].trim());
            this.code = lines[4].trim();
            this.issued = Long.parseLong(lines[5].trim());
            this.userKey = hexToBytes(lines[6].trim());
            Log.i(TAG, "Loaded custom AGU2 credentials for code: " + code);
        }
    }

    private synchronized void startServer() {
        if (running) return;
        running = true;

        acceptThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
                Log.i(TAG, "Bypass proxy listening on 127.0.0.1:" + port);

                while (running) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        executor.execute(() -> handleClient(clientSocket));
                    } catch (IOException e) {
                        if (!running) break;
                        Log.w(TAG, "Accept error: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to bind bypass proxy port " + port + ": " + e.getMessage());
            } finally {
                running = false;
            }
        }, "BypassProxyServer");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private synchronized void stopServer() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
            serverSocket = null;
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
    }

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(30000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            String initialLine = readLine(in);
            if (initialLine == null || initialLine.isEmpty()) {
                client.close();
                return;
            }

            if (!initialLine.startsWith("CONNECT ")) {
                writeResponse(out, 405, "Method Not Allowed");
                client.close();
                return;
            }

            String[] parts = initialLine.split(" ");
            if (parts.length < 2) {
                writeResponse(out, 400, "Bad Request");
                client.close();
                return;
            }

            String hostPort = parts[1];
            String targetHost = hostPort;
            int targetPort = 443;
            int colon = hostPort.indexOf(':');
            if (colon != -1) {
                targetHost = hostPort.substring(0, colon);
                targetPort = Integer.parseInt(hostPort.substring(colon + 1));
            }

            String headerLine;
            while ((headerLine = readLine(in)) != null && !headerLine.isEmpty()) {
                // Drain client CONNECT headers
            }

            if (enabled && hasValidKey() && isGoogleGateHost(targetHost)) {
                Socket tunnel = connectAgu2Tunnel(targetHost, targetPort);
                if (tunnel != null) {
                    client.setSoTimeout(0);
                    tunnel.setSoTimeout(0);
                    writeResponse(out, 200, "Connection Established");
                    pipeBidirectional(client, tunnel);
                    return;
                }
            }

            // Fallback direct connect
            try {
                Socket direct = new Socket();
                direct.connect(new InetSocketAddress(targetHost, targetPort), 10000);
                client.setSoTimeout(0);
                direct.setSoTimeout(0);
                writeResponse(out, 200, "Connection Established");
                pipeBidirectional(client, direct);
            } catch (Exception e) {
                writeResponse(out, 502, "Bad Gateway: " + e.getMessage());
                client.close();
            }

        } catch (Exception e) {
            closeQuietly(client);
        }
    }

    private boolean isGoogleGateHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase().trim();
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        for (String gate : gateHosts) {
            if (h.equals(gate)) return true;
        }
        return false;
    }

    private Socket connectAgu2Tunnel(String targetHost, int targetPort) {
        for (String proxyHost : proxyServers) {
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, null, null);
                SSLSocketFactory factory = sslContext.getSocketFactory();

                SSLSocket sslSocket = (SSLSocket) factory.createSocket();
                sslSocket.connect(new InetSocketAddress(proxyHost, proxyPort), 8000);
                sslSocket.setSoTimeout(15000);

                configureAlpn(sslSocket, alpnProtocol);
                sslSocket.startHandshake();

                long ts = System.currentTimeMillis() / 1000L;
                byte[] nonce = new byte[16];
                random.nextBytes(nonce);
                String nonceHex = bytesToHex(nonce);

                String canonical = "AGU2\n" + tg + "\n" + code + "\n" + issued + "\n" + ts + "\n" + nonceHex + "\n" + targetHost + ":443";
                String mac = computeHmacSha256Hex(userKey, canonical.getBytes(StandardCharsets.UTF_8));
                String authHeader = "AGU2 " + tg + "." + code + "." + issued + "." + ts + "." + nonceHex + "." + mac;

                String req = "CONNECT " + targetHost + ":443 HTTP/1.1\r\n"
                        + "Host: " + targetHost + ":443\r\n"
                        + "Proxy-Authorization: " + authHeader + "\r\n\r\n";

                OutputStream out = sslSocket.getOutputStream();
                out.write(req.getBytes(StandardCharsets.UTF_8));
                out.flush();

                InputStream in = sslSocket.getInputStream();
                String respLine = readLine(in);
                if (respLine != null && respLine.contains("200")) {
                    String line;
                    while ((line = readLine(in)) != null && !line.isEmpty()) {
                        // drain
                    }
                    Log.i(TAG, "AGU2 tunnel established via " + proxyHost + " -> " + targetHost);
                    return sslSocket;
                } else {
                    Log.w(TAG, "AGU2 proxy " + proxyHost + " returned: " + respLine);
                    sslSocket.close();
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed connecting to AGU2 proxy " + proxyHost + ": " + e.getMessage());
            }
        }
        return null;
    }

    private void configureAlpn(SSLSocket sslSocket, String alpn) {
        try {
            SSLParameters params = sslSocket.getSSLParameters();
            Method setProtocols = params.getClass().getMethod("setApplicationProtocols", String[].class);
            setProtocols.invoke(params, (Object) new String[]{alpn});
            sslSocket.setSSLParameters(params);
        } catch (Throwable t) {
            try {
                Method m = sslSocket.getClass().getMethod("setAlpnProtocols", byte[].class);
                byte[] wire = new byte[alpn.length() + 1];
                wire[0] = (byte) alpn.length();
                System.arraycopy(alpn.getBytes(StandardCharsets.US_ASCII), 0, wire, 1, alpn.length());
                m.invoke(sslSocket, (Object) wire);
            } catch (Throwable ignored) {}
        }
    }

    private void pipeBidirectional(Socket s1, Socket s2) {
        executor.execute(() -> {
            try {
                pipe(s1.getInputStream(), s2.getOutputStream());
            } catch (Exception ignored) {}
            finally {
                closeQuietly(s1);
                closeQuietly(s2);
            }
        });
        try {
            pipe(s2.getInputStream(), s1.getOutputStream());
        } catch (Exception ignored) {}
        finally {
            closeQuietly(s1);
            closeQuietly(s2);
        }
    }

    private void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            out.flush();
        }
    }

    private void writeResponse(OutputStream out, int code, String text) throws IOException {
        String resp = "HTTP/1.1 " + code + " " + text + "\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') baos.write(b);
        }
        if (b == -1 && baos.size() == 0) return null;
        return baos.toString("UTF-8");
    }

    private static String computeHmacSha256Hex(byte[] key, byte[] data) {
        return bytesToHex(hmacSha256Bytes(key, data));
    }

    private static byte[] hmacSha256Bytes(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("HMAC failure", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static void closeQuietly(Socket s) {
        try {
            if (s != null && !s.isClosed()) s.close();
        } catch (IOException ignored) {}
    }
}
