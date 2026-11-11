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
 * Intercepts Google gate endpoints and tunnels them to <relay-host>
 * using the AGU2 authenticated protocol with the device hardware ID.
 */
public final class BypassProxyServer {

    private static final String TAG = "BypassProxy";
    public static final int DEFAULT_PORT = 45162;

    private static final String PREF_NAME = "antigravity_proxy_prefs";
    private static final String KEY_ENABLED = "bypass_proxy_enabled";

    // Baked AES/HMAC secret for agk1 token unwrapping
    private static final String BAKED_T_HEX = "";

    private static final String[] PROXY_SERVERS = new String[] {
            "<relay-host>",
            "<relay-host-2>",
            "<relay-host-3>"
    };
    private static final int PROXY_PORT = 443;

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
            instance.startServer();
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
        if (instance != null && instance.hasValidKey()) {
            return "🟢 Активно";
        }
        return "⚪ Ключ не установлен";
    }

    public static synchronized String updateCredentials(Context context, String input) throws Exception {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("Ключ не может быть пустым");
        }
        String clean = input.trim();
        String plaintext;
        if (clean.startsWith("agk1.")) {
            plaintext = unwrapToken(clean);
        } else if (clean.startsWith("AGU2")) {
            plaintext = clean;
        } else {
            throw new IllegalArgumentException("Неверный формат ключа (ожидается ключ agk1... или AGU2...)");
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
        return h.equals("cloudcode-pa.googleapis.com")
                || h.equals("daily-cloudcode-pa.googleapis.com")
                || h.equals("aicode.googleapis.com");
    }

    private Socket connectAgu2Tunnel(String targetHost, int targetPort) {
        for (String proxyHost : PROXY_SERVERS) {
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, null, null);
                SSLSocketFactory factory = sslContext.getSocketFactory();

                SSLSocket sslSocket = (SSLSocket) factory.createSocket();
                sslSocket.connect(new InetSocketAddress(proxyHost, PROXY_PORT), 8000);
                sslSocket.setSoTimeout(15000);

                configureAlpn(sslSocket, "agu/2");
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
