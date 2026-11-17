package com.agy.updater;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpstreamChecker {

    private static final String TAG = "UpstreamChecker";
    private static final String CORE_RELEASES_URL =
            "https://antigravity-hub-auto-updater-974169037036.us-central1.run.app/releases";
    private static final String CORE_GCS_BASE =
            "https://storage.googleapis.com/antigravity-public/antigravity-hub/";
    private static final String BYPASS_LATEST_URL =
            "https://github.com/confeden/Antigravity/releases/latest";
    private static final String BYPASS_RAW_BASE =
            "https://raw.githubusercontent.com/confeden/Antigravity/main/src/";
    public static final String APP_GITHUB_REPO = "Bananchik09/antigravity-android";
    private static final String APP_RELEASES_LATEST_URL =
            "https://github.com/" + APP_GITHUB_REPO + "/releases/latest";
    private static final String APP_EXPANDED_ASSETS_URL =
            "https://github.com/" + APP_GITHUB_REPO + "/releases/expanded_assets/";

    public static final class CheckResult {
        public String installedAppVersion;
        public String installedDriveVersion;
        public String installedCoreVersion;
        public String latestCoreVersion;
        public String latestCoreExecutionId;
        public String latestCoreDownloadUrl;
        public boolean hasCoreUpdate;

        public String installedBypassVersion;
        public String latestBypassVersion;
        public String latestBypassConfigJson;
        public boolean bypassRequiresManualCodeUpdate;
        public boolean hasBypassUpdate;

        public String latestDriveVersion;
        public String latestDriveApkName;
        public String latestDriveDownloadUrl;
        public boolean hasDriveUpdate;

        public String coreError;
        public String bypassError;
        public String driveError;

        public boolean hasAnyUpdate() {
            return hasCoreUpdate || hasBypassUpdate || hasDriveUpdate;
        }

        public boolean isSkippedByUser(Context ctx) {
            if (!hasAnyUpdate()) return true;
            boolean coreUnseen = hasCoreUpdate
                    && !latestCoreVersion.equals(UpdatePrefs.getSkippedCoreVersion(ctx));
            boolean bypassUnseen = hasBypassUpdate
                    && !latestBypassVersion.equals(UpdatePrefs.getSkippedBypassVersion(ctx));
            boolean driveUnseen = hasDriveUpdate
                    && !latestDriveVersion.equals(UpdatePrefs.getSkippedDriveVersion(ctx));
            return !coreUnseen && !bypassUnseen && !driveUnseen;
        }
    }

    private UpstreamChecker() {}

    public static CheckResult check(Context ctx) {
        CheckResult res = new CheckResult();
        res.installedAppVersion = UpdatePrefs.getInstalledAppVersionNumeric(ctx);
        res.installedDriveVersion = UpdatePrefs.getInstalledDriveBaseVersion(ctx);
        res.installedCoreVersion = UpdatePrefs.getInstalledCoreVersion(ctx);
        res.installedBypassVersion = UpdatePrefs.getInstalledBypassVersion(ctx);

        // 1. Check Core from Google Antigravity Hub
        try {
            String json = httpGetString(CORE_RELEASES_URL);
            JSONArray arr = new JSONArray(json);
            if (arr.length() > 0) {
                JSONObject best = null;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    String v = item.optString("version", "");
                    if (v.isEmpty()) continue;
                    if (best == null || UpdatePrefs.compareVersions(v, best.optString("version", "")) > 0) {
                        best = item;
                    }
                }
                if (best != null) {
                    res.latestCoreVersion = best.getString("version");
                    res.latestCoreExecutionId = best.getString("execution_id");
                    res.latestCoreDownloadUrl = CORE_GCS_BASE
                            + res.latestCoreVersion + "-" + res.latestCoreExecutionId
                            + "/unsigned/language_server-linux-arm64";
                    res.hasCoreUpdate = UpdatePrefs.compareVersions(
                            res.latestCoreVersion, res.installedCoreVersion) > 0;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Core check failed: " + e.getMessage());
            res.coreError = e.getMessage();
        }

        // 2. Check Bypass from confeden/Antigravity (skip in Vanilla)
        if (!UpdatePrefs.isVanillaBuild()) {
            try {
                String tag = fetchLatestReleaseTag(BYPASS_LATEST_URL);
                if (tag != null && !tag.isEmpty()) {
                    res.latestBypassVersion = tag.replaceFirst("^v", "");
                    res.hasBypassUpdate = UpdatePrefs.compareVersions(
                            res.latestBypassVersion, res.installedBypassVersion) > 0;
                    if (res.hasBypassUpdate) {
                        buildBypassConfigFromUpstream(res, ctx);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Bypass check failed: " + e.getMessage());
                res.bypassError = e.getMessage();
            }
        }

        // 3. Check GitHub Releases (Bananchik09/antigravity-android) for full App APK updates
        try {
            checkGitHubAppRelease(res);
        } catch (Exception e) {
            Log.w(TAG, "GitHub APK check failed: " + e.getMessage());
            res.driveError = e.getMessage();
        }

        return res;
    }

    private static void checkGitHubAppRelease(CheckResult res) throws Exception {
        String latestTag = fetchLatestReleaseTag(APP_RELEASES_LATEST_URL);
        if (latestTag == null || latestTag.isEmpty()) return;

        String flavorSuffix = UpdatePrefs.isVanillaBuild() ? "-vanilla.apk" : "-mobile.apk";
        String bestVer = null;
        String bestFileName = null;
        String bestUrl = null;

        try {
            String expandedHtml = httpGetString(APP_EXPANDED_ASSETS_URL + latestTag);
            Matcher m = Pattern.compile(
                    "\"(/" + Pattern.quote(APP_GITHUB_REPO) + "/releases/download/[^\"<>]+/"
                            + "(Antigravity-([^\"/<>]+?)" + Pattern.quote(flavorSuffix) + "))\""
            ).matcher(expandedHtml);
            while (m.find()) {
                String path = m.group(1);
                String name = m.group(2);
                String ver = m.group(3);
                if (bestVer == null || UpdatePrefs.compareVersions(ver, bestVer) > 0) {
                    bestVer = ver;
                    bestFileName = name;
                    bestUrl = "https://github.com" + path;
                }
            }
        } catch (Exception ignored) {}

        if (bestUrl == null) {
            String apiJson = httpGetString("https://api.github.com/repos/" + APP_GITHUB_REPO + "/releases/latest");
            JSONObject obj = new JSONObject(apiJson);
            String tagVer = obj.optString("tag_name", latestTag).replaceFirst("^v", "").trim();
            JSONArray assets = obj.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    String name = a.optString("name", "");
                    String dl = a.optString("browser_download_url", "");
                    if (name.startsWith("Antigravity-") && name.endsWith(flavorSuffix) && !dl.isEmpty()) {
                        String ver = name.substring("Antigravity-".length(), name.length() - flavorSuffix.length());
                        if (ver.isEmpty()) ver = tagVer;
                        if (bestVer == null || UpdatePrefs.compareVersions(ver, bestVer) > 0) {
                            bestVer = ver;
                            bestFileName = name;
                            bestUrl = dl;
                        }
                    }
                }
            }
        }

        if (bestVer != null && bestUrl != null
                && UpdatePrefs.compareVersions(bestVer, res.installedDriveVersion) > 0) {
            res.latestDriveVersion = bestVer;
            res.latestDriveApkName = bestFileName;
            res.latestDriveDownloadUrl = bestUrl;
            res.hasDriveUpdate = true;
        }
    }

    private static void buildBypassConfigFromUpstream(CheckResult res, Context ctx) throws Exception {
        String proxyRs = httpGetString(BYPASS_RAW_BASE + "proxy.rs");
        String groupRs = "";
        String resolversRs = "";
        try { groupRs = httpGetString(BYPASS_RAW_BASE + "group.rs"); } catch (Exception ignored) {}
        try { resolversRs = httpGetString(BYPASS_RAW_BASE + "resolvers.rs"); } catch (Exception ignored) {}

        String combined = proxyRs + "\n" + groupRs + "\n" + resolversRs;

        // Detect breaking protocol change (e.g., agu/3 or AGU3)
        if (combined.contains("agu/3") || combined.contains("AGU3")) {
            res.bypassRequiresManualCodeUpdate = true;
        }

        // Preserve configured relay servers from installed config or BuildConfig (upstream hides relay list)
        Set<String> proxyServers = loadExistingProxyServers(ctx);

        Set<String> gateHosts = new LinkedHashSet<>();
        int gateIdx = proxyRs.indexOf("fn is_gate_host");
        String gateSection = gateIdx >= 0
                ? proxyRs.substring(gateIdx, Math.min(proxyRs.length(), gateIdx + 800))
                : "";
        Matcher mGate = Pattern.compile("\"([a-zA-Z0-9.-]+\\.googleapis\\.com)\"").matcher(gateSection);
        while (mGate.find()) {
            gateHosts.add(mGate.group(1).toLowerCase());
        }
        if (gateHosts.isEmpty()) {
            gateHosts.add("cloudcode-pa.googleapis.com");
            gateHosts.add("daily-cloudcode-pa.googleapis.com");
            gateHosts.add("aicode.googleapis.com");
        }

        String alpn = "agu/2";
        Matcher mAlpn = Pattern.compile("\"(agu/[0-9]+)\"").matcher(combined);
        if (mAlpn.find()) {
            alpn = mAlpn.group(1);
        }

        JSONObject cfg = new JSONObject();
        cfg.put("version", res.latestBypassVersion);
        cfg.put("proxy_port", 443);
        cfg.put("alpn", alpn);
        JSONArray ps = new JSONArray();
        for (String s : proxyServers) ps.put(s);
        cfg.put("proxy_servers", ps);
        JSONArray gh = new JSONArray();
        for (String s : gateHosts) gh.put(s);
        cfg.put("gate_hosts", gh);
        res.latestBypassConfigJson = cfg.toString(2);
    }

    private static Set<String> loadExistingProxyServers(Context ctx) {
        Set<String> servers = new LinkedHashSet<>();
        if (ctx != null) {
            try {
                java.io.File override = new java.io.File(ctx.getFilesDir(), "bypass_config.json");
                if (override.isFile()) {
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(override)) {
                        JSONObject obj = new JSONObject(UpdatePrefs.readAllUtf8(fis));
                        JSONArray arr = obj.optJSONArray("proxy_servers");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                String s = arr.optString(i, "").trim();
                                if (!s.isEmpty()) servers.add(s);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
            if (servers.isEmpty()) {
                try {
                    String asset = UpdatePrefs.readAssetText(ctx, "bypass_config.json");
                    if (asset != null) {
                        JSONObject obj = new JSONObject(asset);
                        JSONArray arr = obj.optJSONArray("proxy_servers");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                String s = arr.optString(i, "").trim();
                                if (!s.isEmpty()) servers.add(s);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        if (servers.isEmpty()) {
            for (String s : com.agy.BuildConfig.AGU2_PROXY_SERVERS) {
                if (s != null && !s.trim().isEmpty()) servers.add(s.trim());
            }
        }
        return servers;
    }

    private static String fetchLatestReleaseTag(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "Antigravity-Android-Updater/1.0");
        try {
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                if (loc != null && loc.contains("/tag/")) {
                    return loc.substring(loc.lastIndexOf("/tag/") + 5).trim();
                }
            }
            // Fallback if redirect was followed
            String finalUrl = conn.getURL().toString();
            if (finalUrl.contains("/tag/")) {
                return finalUrl.substring(finalUrl.lastIndexOf("/tag/") + 5).trim();
            }
            return null;
        } finally {
            conn.disconnect();
        }
    }

    private static String httpGetString(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "Antigravity-Android-Updater/1.0");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new RuntimeException("HTTP " + code + " from " + urlStr);
            }
            try (InputStream is = conn.getInputStream()) {
                return UpdatePrefs.readAllUtf8(is);
            }
        } finally {
            conn.disconnect();
        }
    }
}
