package com.agy.updater;

import android.content.Context;
import android.util.Log;

import com.agy.shell.RootDetector;
import com.agy.shell.ShizukuBridge;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Handles APK rollback:
 * - Uses native Android downgrade (`pm install -r -d`) when Root or Shizuku is available.
 * - Otherwise repackages the backed-up APK with versionCode = current + 1, signs v2,
 *   and launches Android's standard PackageInstaller.
 */
public final class RollbackManagerCompat {

    private static final String TAG = "RollbackManager";

    public interface Callback {
        void onProgress(String msg);
        void onDone(boolean launchedInstaller, String message);
        void onError(String err);
    }

    private RollbackManagerCompat() {}

    public static void performRollback(Context ctx, Callback cb) {
        new Thread(() -> {
            try {
                File backup = UpdatePrefs.getBackupApkFile(ctx);
                if (!backup.exists() || backup.length() < 1024 * 1024) {
                    cb.onError("Резервная копия предыдущего APK не найдена.");
                    return;
                }
                File bypassOverride = new File(ctx.getFilesDir(), "bypass_config.json");
                if (bypassOverride.exists()) {
                    bypassOverride.delete();
                }

                // 1. Try Root (`pm install -r -d`)
                if (RootDetector.isRootAvailable()) {
                    cb.onProgress("Откат через Root (pm install -r -d)...");
                    Process p = Runtime.getRuntime().exec(new String[]{
                            "su", "-c", "pm install -r -d \"" + backup.getAbsolutePath() + "\""
                    });
                    int rc = p.waitFor();
                    if (rc == 0) {
                        cb.onDone(false, "Откат выполнен через Root (pm install -r -d).");
                        return;
                    }
                    Log.w(TAG, "Root pm install -r -d failed rc=" + rc);
                }

                // 2. Try Shizuku (`pm install -r -d`)
                ShizukuBridge shizuku = new ShizukuBridge(ctx);
                if (shizuku.isAvailable()) {
                    cb.onProgress("Откат через Shizuku (pm install -r -d)...");
                    String tmpPath = "/data/local/tmp/agy_rollback.apk";
                    String copyCmd = "cat \"" + backup.getAbsolutePath() + "\" > " + tmpPath
                            + " && pm install -r -d " + tmpPath
                            + " ; rm -f " + tmpPath;
                    String out = shizuku.exec(copyCmd);
                    if (out != null && out.contains("Success")) {
                        cb.onDone(false, "Откат выполнен через Shizuku (pm install -r -d).");
                        return;
                    }
                    Log.w(TAG, "Shizuku pm install -r -d failed: " + out);
                }

                // 3. Fallback: Repackage backed-up APK with versionCode = current + 1 and launch installer
                int targetCode = UpdatePrefs.getCurrentVersionCode(ctx) + 1;
                cb.onProgress("Перепаковка резервного APK с versionCode=" + targetCode + "...");

                File workDir = new File(ctx.getFilesDir(), "updater_work");
                if (!workDir.exists()) workDir.mkdirs();
                File unsignedRb = new File(workDir, "rollback_unsigned.apk");
                File signedRb = new File(workDir, "antigravity_rollback.apk");

                try (ZipFile zin = new ZipFile(backup)) {
                    ZipEntry axmlEntry = zin.getEntry("AndroidManifest.xml");
                    if (axmlEntry == null) throw new IllegalStateException("AndroidManifest.xml missing in backup");
                    byte[] axmlBytes;
                    try (InputStream is = zin.getInputStream(axmlEntry)) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buf = new byte[32768];
                        int n;
                        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                        axmlBytes = baos.toByteArray();
                    }
                    byte[] patchedAxml = AxmlPatcher.patchVersionCodeAndName(axmlBytes, targetCode, null);
                    ApkRepackagerAndSigner.repackageZip(zin, unsignedRb, patchedAxml, null, null, null, null);
                }

                cb.onProgress("Подписание отката (APK v2)...");
                byte[] p12;
                try (InputStream is = ctx.getAssets().open("updater/debug.p12")) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                    p12 = baos.toByteArray();
                }
                ApkRepackagerAndSigner.signApkV2(unsignedRb, signedRb, p12, "android", "androiddebugkey");
                unsignedRb.delete();

                cb.onDone(true, signedRb.getAbsolutePath());
                ApkRepackagerAndSigner.launchInstaller(ctx, signedRb);
            } catch (Exception e) {
                Log.e(TAG, "Rollback error", e);
                cb.onError("Ошибка отката: " + e.getMessage());
            }
        }, "AgyRollback").start();
    }
}
