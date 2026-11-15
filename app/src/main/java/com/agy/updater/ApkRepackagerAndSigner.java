package com.agy.updater;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import com.agy.util.AppFileProvider;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.json.JSONObject;

/**
 * Downloads upstream components on-device, patches language_server,
 * repackages the installed APK, signs it with APK Signature Scheme v2,
 * and launches Android's package installer.
 */
public final class ApkRepackagerAndSigner {

    private static final String TAG = "ApkRepackager";
    private static final int CHUNK_1MB = 1024 * 1024;
    private static final int APK_SIG_SCHEME_V2_BLOCK_ID = 0x7109871a;
    private static final int SIG_ALG_RSA_PKCS1_V15_SHA256 = 0x0103;
    private static final byte[] APK_SIG_BLOCK_MAGIC =
            "APK Sig Block 42".getBytes(StandardCharsets.US_ASCII);

    public interface ProgressCallback {
        void onProgress(String step);
    }

    private ApkRepackagerAndSigner() {}

    public static File downloadDriveApk(Context ctx, String downloadUrl, ProgressCallback cb) throws Exception {
        File workDir = new File(ctx.getFilesDir(), "updater_work");
        if (!workDir.exists()) workDir.mkdirs();

        File sourceApk = new File(ctx.getApplicationInfo().sourceDir);
        if (sourceApk.exists()) {
            if (cb != null) cb.onProgress("Создание резервной копии текущего APK для отката...");
            copyFile(sourceApk, UpdatePrefs.getBackupApkFile(ctx));
            UpdatePrefs.saveBackupMeta(
                    ctx,
                    UpdatePrefs.getInstalledCoreVersion(ctx),
                    UpdatePrefs.getInstalledBypassVersion(ctx),
                    UpdatePrefs.getCurrentVersionCode(ctx),
                    UpdatePrefs.getCurrentVersionName(ctx));
        }

        File outApk = new File(workDir, "antigravity_drive_update.apk");
        if (cb != null) cb.onProgress("Скачивание APK с Google Диска...");
        downloadToFile(downloadUrl, outApk, cb);
        return outApk;
    }

    public static File rebuildAndSignUpdate(
            Context ctx,
            UpstreamChecker.CheckResult check,
            boolean updateCore,
            boolean updateBypass,
            ProgressCallback cb) throws Exception {

        File workDir = new File(ctx.getFilesDir(), "updater_work");
        if (!workDir.exists()) workDir.mkdirs();

        String sourceApkPath = ctx.getApplicationInfo().sourceDir;
        File sourceApk = new File(sourceApkPath);
        if (!sourceApk.exists()) {
            throw new IOException("Installed base APK not found: " + sourceApkPath);
        }

        // Save backup of current APK before replacing
        if (cb != null) cb.onProgress("Создание резервной копии текущего APK для отката...");
        File backupFile = UpdatePrefs.getBackupApkFile(ctx);
        copyFile(sourceApk, backupFile);
        UpdatePrefs.saveBackupMeta(
                ctx,
                UpdatePrefs.getInstalledCoreVersion(ctx),
                UpdatePrefs.getInstalledBypassVersion(ctx),
                UpdatePrefs.getCurrentVersionCode(ctx),
                UpdatePrefs.getCurrentVersionName(ctx));

        File patchedLsFile = null;
        String newLsSha256 = null;
        String newCoreVer = UpdatePrefs.getInstalledCoreVersion(ctx);
        String newBypassVer = UpdatePrefs.getInstalledBypassVersion(ctx);

        if (updateCore) {
            if (cb != null) cb.onProgress("Скачивание ядра " + check.latestCoreVersion + " (~226 МБ)...");
            patchedLsFile = new File(workDir, "language_server.tmp");
            downloadToFile(check.latestCoreDownloadUrl, patchedLsFile, cb);
            newLsSha256 = CoreBinaryPatcher.patchFile(patchedLsFile, cb::onProgress);
            newCoreVer = check.latestCoreVersion;
        }

        String newBypassConfig = null;
        if (updateBypass && check.latestBypassConfigJson != null) {
            newBypassConfig = check.latestBypassConfigJson;
            newBypassVer = check.latestBypassVersion;
            // Also write immediate runtime override
            try (FileOutputStream fos = new FileOutputStream(new File(ctx.getFilesDir(), "bypass_config.json"))) {
                fos.write(newBypassConfig.getBytes(StandardCharsets.UTF_8));
            }
        }

        int nextVersionCode = UpdatePrefs.getCurrentVersionCode(ctx) + 1;
        String suffix = UpdatePrefs.isVanillaBuild() ? "-vanilla-autoup" : "-mobile-autoup";
        String baseDriveVer = UpdatePrefs.getInstalledDriveBaseVersion(ctx);
        String nextVersionName = UpdatePrefs.nextAutoUpVersion(baseDriveVer, newCoreVer, updateCore) + suffix;

        if (cb != null) cb.onProgress("Пересборка APK (v" + nextVersionName + ", code " + nextVersionCode + ")...");
        File unsignedApk = new File(workDir, "rebuilt_unsigned.apk");
        File signedApk = new File(workDir, "antigravity_update.apk");

        try (ZipFile zin = new ZipFile(sourceApk)) {
            byte[] axmlBytes = readZipEntry(zin, "AndroidManifest.xml");
            byte[] patchedAxml = AxmlPatcher.patchVersionCodeAndName(axmlBytes, nextVersionCode, nextVersionName);

            byte[] manifestJsonBytes = readZipEntry(zin, "assets/runtime/manifest.json");
            if (newLsSha256 != null) {
                JSONObject mj = new JSONObject(new String(manifestJsonBytes, StandardCharsets.UTF_8));
                mj.getJSONObject("files").put("bin/liblanguage_server.so", newLsSha256);
                manifestJsonBytes = mj.toString(2).getBytes(StandardCharsets.UTF_8);
            }

            JSONObject meta = new JSONObject();
            meta.put("core_version", newCoreVer);
            meta.put("bypass_version", newBypassVer);
            meta.put("drive_version", baseDriveVer);
            byte[] versionMetaBytes = meta.toString(2).getBytes(StandardCharsets.UTF_8);

            repackageZip(zin, unsignedApk, patchedAxml, manifestJsonBytes, versionMetaBytes,
                    patchedLsFile, newBypassConfig != null ? newBypassConfig.getBytes(StandardCharsets.UTF_8) : null);
        } finally {
            if (patchedLsFile != null && patchedLsFile.exists()) {
                patchedLsFile.delete();
            }
        }

        if (cb != null) cb.onProgress("Подписание APK (APK Signature Scheme v2)...");
        byte[] p12Bytes = readAssetBytes(ctx, "updater/debug.p12");
        signApkV2(unsignedApk, signedApk, p12Bytes, "android", "androiddebugkey");
        unsignedApk.delete();

        return signedApk;
    }

    public static void repackageZip(
            ZipFile zin,
            File outFile,
            byte[] patchedAxml,
            byte[] patchedManifestJson,
            byte[] versionMetaBytes,
            File replacementLsFile,
            byte[] replacementBypassConfig) throws IOException {

        CountingOutputStream cos = new CountingOutputStream(new FileOutputStream(outFile));
        try (ZipOutputStream zos = new ZipOutputStream(cos)) {
            boolean wroteVersionMeta = false;
            boolean wroteBypassConfig = false;

            Enumeration<? extends ZipEntry> entries = zin.entries();
            byte[] ioBuf = new byte[256 * 1024];

            while (entries.hasMoreElements()) {
                ZipEntry inEntry = entries.nextElement();
                String name = inEntry.getName();
                if (name.startsWith("META-INF/")) continue;

                if ("AndroidManifest.xml".equals(name) && patchedAxml != null) {
                    writeDeflatedEntry(zos, name, patchedAxml);
                } else if ("assets/runtime/manifest.json".equals(name) && patchedManifestJson != null) {
                    writeDeflatedEntry(zos, name, patchedManifestJson);
                } else if ("assets/updater/version_meta.json".equals(name) && versionMetaBytes != null) {
                    writeDeflatedEntry(zos, name, versionMetaBytes);
                    wroteVersionMeta = true;
                } else if ("assets/bypass_config.json".equals(name) && replacementBypassConfig != null) {
                    writeDeflatedEntry(zos, name, replacementBypassConfig);
                    wroteBypassConfig = true;
                } else if ("assets/runtime/bin/liblanguage_server.so".equals(name) && replacementLsFile != null) {
                    ZipEntry outEntry = new ZipEntry(name);
                    outEntry.setMethod(ZipEntry.DEFLATED);
                    zos.putNextEntry(outEntry);
                    try (InputStream fis = new BufferedInputStream(new FileInputStream(replacementLsFile), 256 * 1024)) {
                        int n;
                        while ((n = fis.read(ioBuf)) != -1) zos.write(ioBuf, 0, n);
                    }
                    zos.closeEntry();
                } else if ("resources.arsc".equals(name) || inEntry.getMethod() == ZipEntry.STORED) {
                    byte[] data = readZipEntry(zin, name);
                    writeAlignedStoredEntry(zos, cos, name, data);
                } else {
                    ZipEntry outEntry = new ZipEntry(name);
                    outEntry.setMethod(ZipEntry.DEFLATED);
                    zos.putNextEntry(outEntry);
                    try (InputStream is = zin.getInputStream(inEntry)) {
                        int n;
                        while ((n = is.read(ioBuf)) != -1) zos.write(ioBuf, 0, n);
                    }
                    zos.closeEntry();
                }
            }

            if (!wroteVersionMeta && versionMetaBytes != null) {
                writeDeflatedEntry(zos, "assets/updater/version_meta.json", versionMetaBytes);
            }
            if (!wroteBypassConfig && replacementBypassConfig != null) {
                writeDeflatedEntry(zos, "assets/bypass_config.json", replacementBypassConfig);
            }
        }
    }

    private static void writeDeflatedEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.DEFLATED);
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }

    private static void writeAlignedStoredEntry(ZipOutputStream zos, CountingOutputStream cos,
                                                String name, byte[] data) throws IOException {
        zos.flush();
        long headerStart = cos.count;
        int nameLen = name.getBytes(StandardCharsets.UTF_8).length;
        long dataStart = headerStart + 30 + nameLen;
        int pad = (int) ((4 - (dataStart % 4)) % 4);

        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        e.setCrc(crc.getValue());
        if (pad > 0) {
            e.setExtra(new byte[pad]);
        }
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }

    public static void signApkV2(File inputZip, File outputApk, byte[] p12Bytes,
                                  String password, String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = new java.io.ByteArrayInputStream(p12Bytes)) {
            ks.load(is, password.toCharArray());
        }
        PrivateKey privateKey = (PrivateKey) ks.getKey(alias, password.toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);

        long fileLen = inputZip.length();
        byte[] eocd;
        long cdOffset;
        int cdSize;
        long eocdOffset;

        try (RandomAccessFile raf = new RandomAccessFile(inputZip, "r")) {
            int tailLen = (int) Math.min(fileLen, 65535 + 22);
            byte[] tail = new byte[tailLen];
            raf.seek(fileLen - tailLen);
            raf.readFully(tail);
            int eocdRel = -1;
            for (int i = tailLen - 22; i >= 0; i--) {
                if (tail[i] == 0x50 && tail[i + 1] == 0x4b && tail[i + 2] == 0x05 && tail[i + 3] == 0x06) {
                    eocdRel = i;
                    break;
                }
            }
            if (eocdRel < 0) throw new IOException("ZIP EOCD not found");
            eocdOffset = fileLen - tailLen + eocdRel;
            eocd = new byte[(int) (fileLen - eocdOffset)];
            System.arraycopy(tail, eocdRel, eocd, 0, eocd.length);

            ByteBuffer ebb = ByteBuffer.wrap(eocd).order(ByteOrder.LITTLE_ENDIAN);
            cdSize = ebb.getInt(12);
            cdOffset = ebb.getInt(16) & 0xffffffffL;
        }

        byte[] cdBytes = new byte[cdSize];
        try (RandomAccessFile raf = new RandomAccessFile(inputZip, "r")) {
            raf.seek(cdOffset);
            raf.readFully(cdBytes);
        }

        // Compute APK Signature Scheme v2 content digest over 3 sections:
        // 1) ZIP entries [0..cdOffset)
        // 2) Central Directory
        // 3) EOCD (with original cdOffset)
        List<byte[]> chunkDigests = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(inputZip, "r")) {
            digestChunks(raf, 0, cdOffset, chunkDigests);
        }
        digestByteArray(cdBytes, chunkDigests);
        digestByteArray(eocd, chunkDigests);

        MessageDigest topMd = MessageDigest.getInstance("SHA-256");
        topMd.update((byte) 0x5a);
        topMd.update(le32(chunkDigests.size()));
        for (byte[] d : chunkDigests) {
            topMd.update(d);
        }
        byte[] contentDigest = topMd.digest();

        // Build signed-data
        byte[] digestsSeq = lenPrefixed(concat(le32(SIG_ALG_RSA_PKCS1_V15_SHA256), lenPrefixed(contentDigest)));
        byte[] certsSeq = lenPrefixed(cert.getEncoded());
        byte[] attrsSeq = new byte[0];
        byte[] signedData = concat(lenPrefixed(digestsSeq), lenPrefixed(certsSeq), lenPrefixed(attrsSeq));

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(signedData);
        byte[] sigBytes = sig.sign();

        byte[] signaturesSeq = lenPrefixed(concat(le32(SIG_ALG_RSA_PKCS1_V15_SHA256), lenPrefixed(sigBytes)));
        byte[] pubKey = cert.getPublicKey().getEncoded();
        byte[] signer = concat(lenPrefixed(signedData), lenPrefixed(signaturesSeq), lenPrefixed(pubKey));
        byte[] v2Block = lenPrefixed(lenPrefixed(signer));

        // Build APK Signing Block
        byte[] pair = concat(le64(4L + v2Block.length), le32(APK_SIG_SCHEME_V2_BLOCK_ID), v2Block);
        long blockSizeField = pair.length + 8L + 16L;
        byte[] signingBlock = concat(le64(blockSizeField), pair, le64(blockSizeField), APK_SIG_BLOCK_MAGIC);

        // Update EOCD cdOffset
        byte[] patchedEocd = eocd.clone();
        ByteBuffer.wrap(patchedEocd).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(16, (int) (cdOffset + signingBlock.length));

        try (FileInputStream fis = new FileInputStream(inputZip);
             FileOutputStream fos = new FileOutputStream(outputApk)) {
            byte[] buf = new byte[256 * 1024];
            long remaining = cdOffset;
            while (remaining > 0) {
                int r = fis.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (r < 0) break;
                fos.write(buf, 0, r);
                remaining -= r;
            }
            fos.write(signingBlock);
            fos.write(cdBytes);
            fos.write(patchedEocd);
        }
    }

    private static void digestChunks(RandomAccessFile raf, long offset, long len, List<byte[]> out)
            throws Exception {
        raf.seek(offset);
        byte[] buf = new byte[CHUNK_1MB];
        long rem = len;
        while (rem > 0) {
            int chunk = (int) Math.min(CHUNK_1MB, rem);
            raf.readFully(buf, 0, chunk);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((byte) 0xa5);
            md.update(le32(chunk));
            md.update(buf, 0, chunk);
            out.add(md.digest());
            rem -= chunk;
        }
    }

    private static void digestByteArray(byte[] data, List<byte[]> out) throws Exception {
        int pos = 0;
        while (pos < data.length) {
            int chunk = Math.min(CHUNK_1MB, data.length - pos);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((byte) 0xa5);
            md.update(le32(chunk));
            md.update(data, pos, chunk);
            out.add(md.digest());
            pos += chunk;
        }
    }

    public static void launchInstaller(Context ctx, File apkFile) {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.getPackageManager().canRequestPackageInstalls()) {
            Intent permIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.getPackageName()));
            permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(permIntent);
            return;
        }
        Uri uri = AppFileProvider.getUriForFile(ctx, apkFile);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }

    private static void downloadToFile(String urlStr, File dest, ProgressCallback cb) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "Antigravity-Android-Updater/1.0");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " downloading " + urlStr);
            }
            long total = conn.getContentLengthLong();
            long downloaded = 0;
            long lastReport = 0;
            byte[] buf = new byte[128 * 1024];
            try (InputStream is = new BufferedInputStream(conn.getInputStream(), 128 * 1024);
                 FileOutputStream fos = new FileOutputStream(dest)) {
                int n;
                while ((n = is.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    downloaded += n;
                    if (cb != null && downloaded - lastReport >= 10 * 1024 * 1024) {
                        lastReport = downloaded;
                        if (total > 0) {
                            int pct = (int) ((downloaded * 100) / total);
                            cb.onProgress("Скачивание ядра: " + pct + "% ("
                                    + (downloaded / (1024 * 1024)) + "/" + (total / (1024 * 1024)) + " МБ)");
                        } else {
                            cb.onProgress("Скачивание ядра: " + (downloaded / (1024 * 1024)) + " МБ");
                        }
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readZipEntry(ZipFile zf, String name) throws IOException {
        ZipEntry e = zf.getEntry(name);
        if (e == null) throw new IOException("Missing ZIP entry: " + name);
        try (InputStream is = zf.getInputStream(e)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }

    private static byte[] readAssetBytes(Context ctx, String path) throws IOException {
        try (InputStream is = ctx.getAssets().open(path)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        byte[] buf = new byte[256 * 1024];
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            int n;
            while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
        }
    }

    private static byte[] le32(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    private static byte[] le64(long v) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array();
    }

    private static byte[] lenPrefixed(byte[] data) {
        return concat(le32(data.length), data);
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        long count = 0;
        CountingOutputStream(OutputStream out) {
            super(out);
        }
        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }
}
