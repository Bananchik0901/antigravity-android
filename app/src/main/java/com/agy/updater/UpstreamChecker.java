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
    public static final String GDRIVE_FOLDER_ID = "1G5pZieHp--dHqtq2dFqInBuZDCsRxQoz";

    public static final class CheckResult {
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
                        buildBypassConfigFromUpstream(res);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Bypass check failed: " + e.getMessage());
                res.bypassError = e.getMessage();
            }
        }

        // 3. Check Google Drive special folder for full App APK updates
        try {
            checkGoogleDriveFolder(res);
        } catch (Exception e) {
            Log.w(TAG, "Drive check failed: " + e.getMessage());
            res.driveError = e.getMessage();
        }

        return res;
    }

    private static void checkGoogleDriveFolder(CheckResult res) throws Exception {
        String html = httpGetString("https://drive.google.com/embeddedfolderview?id=" + GDRIVE_FOLDER_ID + "#list");
        String flavorSuffix = UpdatePrefs.isVanillaBuild() ? "-vanilla.apk" : "-mobile.apk";

        // First look for direct APK entries: Antigravity-X.Y.Z-mobile.apk / -vanilla.apk
        String bestVer = null;
        String bestFileId = null;
        String bestFileName = null;

        Matcher mFile = Pattern.compile(
                "/file/d/([a-zA-Z0-9_-]+)/[^\"']*[\"'][^>]*>.*?class=\"flip-entry-title\"[^>]*>([^<]+)<",
                Pattern.DOTALL).matcher(html);
        while (mFile.find()) {
            String id = mFile.group(1);
            String name = mFile.group(2).trim();
            if (name.startsWith("Antigravity-") && name.endsWith(flavorSuffix)) {
                String ver = name.substring("Antigravity-".length(), name.length() - flavorSuffix.length());
                if (bestVer == null || UpdatePrefs.compareVersions(ver, bestVer) > 0) {
                    bestVer = ver;
                    bestFileId = id;
                    bestFileName = name;
                }
            }
        }

        // Also look for Antigravity-X.Y.Z subfolders if no direct newer APK found
        String bestFolderVer = null;
        String bestFolderId = null;
        Matcher mFolder = Pattern.compile(
                "/drive/folders/([a-zA-Z0-9_-]+)[^\"']*[\"'][^>]*>.*?class=\"flip-entry-title\"[^>]*>([^<]+)<",
                Pattern.DOTALL).matcher(html);
        while (mFolder.find()) {
            String id = mFolder.group(1);
            String name = mFolder.group(2).trim();
            if (name.startsWith("Antigravity-")) {
                String ver = name.substring("Antigravity-".length());
                if (bestFolderVer == null || UpdatePrefs.compareVersions(ver, bestFolderVer) > 0) {
                    bestFolderVer = ver;
                    bestFolderId = id;
                }
            }
        }

        if (bestFolderId != null && (bestVer == null || UpdatePrefs.compareVersions(bestFolderVer, bestVer) > 0)) {
            if (UpdatePrefs.compareVersions(bestFolderVer, res.installedCoreVersion) > 0) {
                String subHtml = httpGetString("https://drive.google.com/embeddedfolderview?id=" + bestFolderId + "#list");
                Matcher mSub = Pattern.compile(
                        "/file/d/([a-zA-Z0-9_-]+)/[^\"']*[\"'][^>]*>.*?class=\"flip-entry-title\"[^>]*>([^<]+)<",
                        Pattern.DOTALL).matcher(subHtml);
                while (mSub.find()) {
                    String id = mSub.group(1);
                    String name = mSub.group(2).trim();
                    if (name.endsWith(flavorSuffix)) {
                        bestVer = bestFolderVer;
                        bestFileId = id;
                        bestFileName = name;
                        break;
                    }
                }
            }
        }

        if (bestVer != null && bestFileId != null
                && UpdatePrefs.compareVersions(bestVer, res.installedCoreVersion) > 0) {
            res.latestDriveVersion = bestVer;
            res.latestDriveApkName = bestFileName;
            res.latestDriveDownloadUrl = "https://drive.usercontent.google.com/download?id=" + bestFileId + "&confirm=t";
            res.hasDriveUpdate = true;
        }
    }

    private static void buildBypassConfigFromUpstream(CheckResult res) throws Exception {
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

        Set<String> proxyServers = new LinkedHashSet<>();
        Matcher mProxy = Pattern.compile("\"([a-zA-Z0-9.-]+\\.nova-app\\.eu)\"").matcher(combined);
        while (mProxy.find()) {
            proxyServers.add(mProxy.group(1));
        }
        if (proxyServers.isEmpty()) {
            proxyServers.add("<relay-host>");
            proxyServers.add("<relay-host-2>");
            proxyServers.add("<relay-host-3>");
        }

        Set<String> gateHosts = new LinkedHashSet<>();
        Matcher mGate = Pattern.compile("\"([a-zA-Z0-9.-]+\\.googleapis\\.com)\"").matcher(combined);
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
