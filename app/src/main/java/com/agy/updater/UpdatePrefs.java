package com.agy.updater;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class UpdatePrefs {

    private static final String PREF_NAME = "antigravity_updater_prefs";
    private static final String KEY_DO_NOT_REMIND = "do_not_remind";
    private static final String KEY_SKIPPED_CORE = "skipped_core_version";
    private static final String KEY_SKIPPED_BYPASS = "skipped_bypass_version";
    private static final String KEY_SKIPPED_DRIVE = "skipped_drive_version";
    private static final String KEY_BACKUP_CORE = "backup_core_version";
    private static final String KEY_BACKUP_BYPASS = "backup_bypass_version";
    private static final String KEY_BACKUP_VCODE = "backup_version_code";
    private static final String KEY_BACKUP_VNAME = "backup_version_name";

    private UpdatePrefs() {}

    public static boolean isVanillaBuild() {
        try {
            Class.forName("com.agy.proxy.BypassProxyServer");
            return false;
        } catch (ClassNotFoundException e) {
            return true;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isDoNotRemind(Context ctx) {
        return prefs(ctx).getBoolean(KEY_DO_NOT_REMIND, false);
    }

    public static void setDoNotRemind(Context ctx, boolean value) {
        prefs(ctx).edit().putBoolean(KEY_DO_NOT_REMIND, value).apply();
    }

    public static String getSkippedCoreVersion(Context ctx) {
        return prefs(ctx).getString(KEY_SKIPPED_CORE, "");
    }

    public static String getSkippedBypassVersion(Context ctx) {
        return prefs(ctx).getString(KEY_SKIPPED_BYPASS, "");
    }

    public static String getSkippedDriveVersion(Context ctx) {
        return prefs(ctx).getString(KEY_SKIPPED_DRIVE, "");
    }

    public static void setSkippedVersions(Context ctx, String coreVer, String bypassVer, String driveVer) {
        SharedPreferences.Editor ed = prefs(ctx).edit();
        if (coreVer != null && !coreVer.isEmpty()) ed.putString(KEY_SKIPPED_CORE, coreVer);
        if (bypassVer != null && !bypassVer.isEmpty()) ed.putString(KEY_SKIPPED_BYPASS, bypassVer);
        if (driveVer != null && !driveVer.isEmpty()) ed.putString(KEY_SKIPPED_DRIVE, driveVer);
        ed.apply();
    }

    public static void clearSkippedAndRemind(Context ctx) {
        prefs(ctx).edit()
                .putBoolean(KEY_DO_NOT_REMIND, false)
                .remove(KEY_SKIPPED_CORE)
                .remove(KEY_SKIPPED_BYPASS)
                .remove(KEY_SKIPPED_DRIVE)
                .apply();
    }

    public static String getInstalledCoreVersion(Context ctx) {
        String meta = readAssetText(ctx, "updater/version_meta.json");
        if (meta != null) {
            try {
                String v = new JSONObject(meta).optString("core_version", "");
                if (!v.isEmpty()) return v;
            } catch (Exception ignored) {}
        }
        String cfgCore = com.agy.BuildConfig.CORE_VERSION;
        if (cfgCore != null && !cfgCore.isEmpty()) {
            return cfgCore;
        }
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (pi.versionName != null) {
                return pi.versionName.replace("-autoup", "").replace("-mobile", "").replace("-vanilla", "").trim();
            }
        } catch (Exception ignored) {}
        return "0.0.0";
    }

    public static String getInstalledBypassVersion(Context ctx) {
        if (isVanillaBuild()) return "none";
        String best = "2.34.0";
        File override = new File(ctx.getFilesDir(), "bypass_config.json");
        if (override.exists()) {
            try (InputStream is = new java.io.FileInputStream(override)) {
                String s = readAllUtf8(is);
                String v = new JSONObject(s).optString("version", "");
                if (!v.isEmpty() && compareVersions(v, best) > 0) best = v;
            } catch (Exception ignored) {}
        }
        String asset = readAssetText(ctx, "bypass_config.json");
        if (asset != null) {
            try {
                String v = new JSONObject(asset).optString("version", "");
                if (!v.isEmpty() && compareVersions(v, best) > 0) best = v;
            } catch (Exception ignored) {}
        }
        return best;
    }

    public static int getCurrentVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return 1;
        }
    }

    public static String getCurrentVersionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "1.0";
        } catch (Exception e) {
            return "1.0";
        }
    }

    public static String getInstalledAppVersionNumeric(Context ctx) {
        String v = getCurrentVersionName(ctx);
        return v.replace("-autoup", "").replace("-mobile", "").replace("-vanilla", "").trim();
    }

    public static String getInstalledDriveBaseVersion(Context ctx) {
        String meta = readAssetText(ctx, "updater/version_meta.json");
        if (meta != null) {
            try {
                String v = new JSONObject(meta).optString("drive_version", "");
                if (!v.isEmpty()) return v;
            } catch (Exception ignored) {}
        }
        return getInstalledAppVersionNumeric(ctx);
    }

    public static String nextAutoUpVersion(String baseDriveVer, String targetCoreVer, boolean coreChanged) {
        if (coreChanged) {
            return targetCoreVer;
        }
        return baseDriveVer;
    }

    public static File getBackupApkFile(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "updater_backups");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "prev_backup.apk");
    }

    public static boolean hasBackup(Context ctx) {
        File f = getBackupApkFile(ctx);
        return f.exists() && f.length() > 1024 * 1024;
    }

    public static void saveBackupMeta(Context ctx, String coreVer, String bypassVer, int vCode, String vName) {
        prefs(ctx).edit()
                .putString(KEY_BACKUP_CORE, coreVer)
                .putString(KEY_BACKUP_BYPASS, bypassVer)
                .putInt(KEY_BACKUP_VCODE, vCode)
                .putString(KEY_BACKUP_VNAME, vName)
                .apply();
    }

    public static String getBackupSummary(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String vName = p.getString(KEY_BACKUP_VNAME, "?");
        int vCode = p.getInt(KEY_BACKUP_VCODE, 0);
        String c = p.getString(KEY_BACKUP_CORE, "?");
        String b = p.getString(KEY_BACKUP_BYPASS, "?");
        if (isVanillaBuild()) {
            return vName + " (vCode " + vCode + ", Ядро " + c + ")";
        }
        return vName + " (vCode " + vCode + ", Ядро " + c + ", Обход " + b + ")";
    }

    public static int compareVersions(String a, String b) {
        if (a == null) a = "0";
        if (b == null) b = "0";
        a = a.replaceAll("^v", "").split("-")[0].trim();
        b = b.replaceAll("^v", "").split("-")[0].trim();
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int vb = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    public static String readAssetText(Context ctx, String path) {
        try (InputStream is = ctx.getAssets().open(path)) {
            return readAllUtf8(is);
        } catch (Exception e) {
            return null;
        }
    }

    public static String readAllUtf8(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }
}
