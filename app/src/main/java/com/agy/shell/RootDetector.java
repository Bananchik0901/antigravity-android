package com.agy.shell;

import android.os.Build;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * Utility to detect Root access (Magisk, KernelSU, APatch, SuperSU).
 */
public final class RootDetector {

    private static final String[] SU_PATHS = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su",
            "/system/app/Superuser.apk"
    };

    private RootDetector() {}

    /** Fast non-blocking check for root binaries or tags. */
    public static boolean isRootAvailable() {
        String buildTags = Build.TAGS;
        if (buildTags != null && buildTags.contains("test-keys")) {
            return true;
        }

        for (String path : SU_PATHS) {
            try {
                if (new File(path).exists()) {
                    return true;
                }
            } catch (Exception ignored) {}
        }

        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"which", "su"});
            BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = in.readLine();
            if (line != null && !line.trim().isEmpty()) {
                return true;
            }
        } catch (Exception ignored) {
        } finally {
            if (process != null) {
                process.destroy();
            }
        }

        return false;
    }

    /** Runs a test `su -c id` command to verify active root privileges. */
    public static String testSu() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            StringBuilder sb = new StringBuilder();
            BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = in.readLine()) != null) {
                sb.append(line).append('\n');
            }
            int code = process.waitFor();
            String out = sb.toString().trim();
            if (code == 0 && out.contains("uid=0")) {
                return "Root активен (uid=0): " + out;
            }
            return out.isEmpty() ? "Команда su завершилась с кодом " + code : out;
        } catch (Exception e) {
            return "Ошибка su: " + e.getMessage();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
