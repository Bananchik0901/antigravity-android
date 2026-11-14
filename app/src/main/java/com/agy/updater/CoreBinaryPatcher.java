package com.agy.updater;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java, streaming/file-backed binary patcher for language_server (arm64).
 * Applies all modules/patches equivalents without loading the 226MB ELF into heap.
 */
public final class CoreBinaryPatcher {

    private static final String TAG = "CoreBinaryPatcher";
    private static final int CHUNK_SIZE = 4 * 1024 * 1024;
    private static final int OVERLAP = 128;

    public interface ProgressCallback {
        void onProgress(String message);
    }

    private CoreBinaryPatcher() {}

    public static String patchFile(File file, ProgressCallback cb) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            long fileLen = raf.length();
            if (fileLen < 64) throw new IOException("File too small: " + fileLen);

            if (cb != null) cb.onProgress("Анализ ELF-заголовков и Go pclntab...");
            SectionInfo textSec = findSection(raf, ".text");
            if (textSec == null) throw new IOException(".text section not found in ELF");
            long pclnOff = findPclntabOffset(raf, fileLen);
            if (pclnOff < 0) throw new IOException("Go pclntab not found in ELF");

            long firstGo = findGoTextStart(raf, textSec.offset, fileLen);

            if (cb != null) cb.onProgress("Патчинг Go-функций (faccessat2, fchmodat2, isSandboxEnabled)...");
            patchGoFunctions(raf, pclnOff, fileLen - pclnOff, firstGo);

            if (cb != null) cb.onProgress("Патчинг инструкций и строк (cli_gate, sigill, failfast, sandbox)...");
            patchWholeFilePatterns(raf, fileLen, textSec.offset, textSec.offset + textSec.size);
        }

        if (cb != null) cb.onProgress("Вычисление SHA-256 патченного ядра...");
        return sha256File(file);
    }

    private static final class SectionInfo {
        final long offset;
        final long size;
        SectionInfo(long offset, long size) {
            this.offset = offset;
            this.size = size;
        }
    }

    private static SectionInfo findSection(RandomAccessFile raf, String targetName) throws IOException {
        byte[] ehdr = new byte[64];
        raf.seek(0);
        raf.readFully(ehdr);
        if (ehdr[0] != 0x7f || ehdr[1] != 'E' || ehdr[2] != 'L' || ehdr[3] != 'F') {
            throw new IOException("Not an ELF binary");
        }
        ByteBuffer bb = ByteBuffer.wrap(ehdr).order(ByteOrder.LITTLE_ENDIAN);
        long shoff = bb.getLong(40);
        int shentsize = bb.getShort(58) & 0xffff;
        int shnum = bb.getShort(60) & 0xffff;
        int shstrndx = bb.getShort(62) & 0xffff;
        if (shoff == 0 || shstrndx >= shnum) return null;

        byte[] strHdr = new byte[shentsize];
        raf.seek(shoff + (long) shstrndx * shentsize);
        raf.readFully(strHdr);
        ByteBuffer sbb = ByteBuffer.wrap(strHdr).order(ByteOrder.LITTLE_ENDIAN);
        long strOff = sbb.getLong(24);
        long strSize = sbb.getLong(32);
        byte[] shstr = new byte[(int) Math.min(strSize, 1024 * 1024)];
        raf.seek(strOff);
        raf.readFully(shstr);

        byte[] secHdr = new byte[shentsize];
        for (int i = 0; i < shnum; i++) {
            raf.seek(shoff + (long) i * shentsize);
            raf.readFully(secHdr);
            ByteBuffer h = ByteBuffer.wrap(secHdr).order(ByteOrder.LITTLE_ENDIAN);
            int nameIdx = h.getInt(0);
            String name = readCString(shstr, nameIdx);
            if (targetName.equals(name)) {
                return new SectionInfo(h.getLong(24), h.getLong(32));
            }
        }
        return null;
    }

    private static String readCString(byte[] buf, int idx) {
        if (idx < 0 || idx >= buf.length) return "";
        int end = idx;
        while (end < buf.length && buf[end] != 0) end++;
        return new String(buf, idx, end - idx, StandardCharsets.US_ASCII);
    }

    private static long findGoTextStart(RandomAccessFile raf, long textOff, long fileLen) throws IOException {
        int len = (int) Math.min(0x2000, fileLen - textOff);
        byte[] buf = new byte[len];
        raf.seek(textOff);
        raf.readFully(buf);
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i + 4 <= len; i += 4) {
            if (bb.getInt(i) == 0xf9400b90) {
                return textOff + i;
            }
        }
        return textOff;
    }

    private static long findPclntabOffset(RandomAccessFile raf, long fileLen) throws IOException {
        byte[] buf = new byte[CHUNK_SIZE + OVERLAP];
        long offset = 0;
        while (offset < fileLen) {
            int toRead = (int) Math.min(buf.length, fileLen - offset);
            raf.seek(offset);
            raf.readFully(buf, 0, toRead);
            int scanLimit = (offset + toRead < fileLen) ? (toRead - OVERLAP) : toRead;
            for (int i = 0; i < scanLimit && i + 72 <= toRead; i++) {
                if ((buf[i] == (byte) 0xf1 || buf[i] == (byte) 0xf2 || buf[i] == (byte) 0xf0)
                        && buf[i + 1] == (byte) 0xff && buf[i + 2] == (byte) 0xff && buf[i + 3] == (byte) 0xff
                        && buf[i + 4] == 0 && buf[i + 5] == 0
                        && buf[i + 6] == 4 && buf[i + 7] == 8) {
                    ByteBuffer bb = ByteBuffer.wrap(buf, i, 72).order(ByteOrder.LITTLE_ENDIAN);
                    long nfunc = bb.getLong(8);
                    long funcnameOff = bb.getLong(32);
                    long pclnOff = bb.getLong(64);
                    long pos = offset + i;
                    if (nfunc >= 1 && nfunc <= 1_000_000
                            && funcnameOff > 0 && pos + funcnameOff < fileLen
                            && pclnOff > 0 && pos + pclnOff < fileLen) {
                        return pos;
                    }
                }
            }
            if (offset + toRead >= fileLen) break;
            offset += CHUNK_SIZE;
        }
        return -1;
    }

    private static void patchGoFunctions(RandomAccessFile raf, long pclnBase, long pclnSize, long firstGo)
            throws IOException {
        byte[] hdr = new byte[72];
        raf.seek(pclnBase);
        raf.readFully(hdr);
        ByteBuffer hbb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        int magic = hbb.getInt(0);
        if (magic != 0xfffffff1) {
            throw new IOException("Unsupported Go pclntab magic: 0x" + Integer.toHexString(magic));
        }
        int nfunc = (int) hbb.getLong(8);
        long funcnameOff = pclnBase + hbb.getLong(32);
        long pclnOff = pclnBase + hbb.getLong(64);
        long funcnameSize = pclnSize - (funcnameOff - pclnBase);

        byte[] funcnames = new byte[(int) Math.min(funcnameSize, 32 * 1024 * 1024)];
        raf.seek(funcnameOff);
        raf.readFully(funcnames);

        byte[] ftbl = new byte[(nfunc + 1) * 8];
        raf.seek(pclnOff);
        raf.readFully(ftbl);
        ByteBuffer fbb = ByteBuffer.wrap(ftbl).order(ByteOrder.LITTLE_ENDIAN);

        String fnFaccessat = "syscall.faccessat2";
        String fnFchmodat = "syscall.fchmodat2";
        String fnSandbox = "google3/third_party/jetski/cortex/handlers/handlers.(*RunCommandHandler).isSandboxEnabled";

        int patchedCount = 0;
        byte[] funcInfo = new byte[8];

        for (int i = 0; i < nfunc; i++) {
            long entryOff = fbb.getInt(i * 8) & 0xffffffffL;
            long infoOff = fbb.getInt(i * 8 + 4) & 0xffffffffL;
            long nextEntry = fbb.getInt((i + 1) * 8) & 0xffffffffL;
            int size = (int) (nextEntry - entryOff);
            if (size <= 0) continue;

            raf.seek(pclnOff + infoOff);
            raf.readFully(funcInfo);
            int nameOff = ByteBuffer.wrap(funcInfo).order(ByteOrder.LITTLE_ENDIAN).getInt(4);
            String name = readCString(funcnames, nameOff);

            if (fnFaccessat.equals(name)) {
                replaceWordInRange(raf, firstGo + entryOff, size, 0xd28036e0, 0xd2800600);
                patchedCount++;
            } else if (fnFchmodat.equals(name)) {
                replaceWordInRange(raf, firstGo + entryOff, size, 0xd2803880, 0xd28006a0);
                patchedCount++;
            } else if (fnSandbox.equals(name)) {
                byte[] retFalse = hexToBytes("00008052c0035fd6");
                raf.seek(firstGo + entryOff);
                raf.write(retFalse);
                Log.i(TAG, "Patched isSandboxEnabled at 0x" + Long.toHexString(firstGo + entryOff));
                patchedCount++;
            }
            if (patchedCount == 3) break;
        }

        if (patchedCount < 3) {
            Log.w(TAG, "Only found " + patchedCount + "/3 Go target functions");
        }
    }

    private static void replaceWordInRange(RandomAccessFile raf, long start, int size, int oldWord, int newWord)
            throws IOException {
        byte[] body = new byte[size];
        raf.seek(start);
        raf.readFully(body);
        ByteBuffer bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i + 4 <= size; i += 4) {
            if (bb.getInt(i) == oldWord) {
                bb.putInt(i, newWord);
                raf.seek(start + i);
                byte[] out = new byte[4];
                ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(newWord);
                raf.write(out);
                Log.i(TAG, "Replaced 0x" + Integer.toHexString(oldWord) + " -> 0x"
                        + Integer.toHexString(newWord) + " at 0x" + Long.toHexString(start + i));
                return;
            }
        }
    }

    private static void patchWholeFilePatterns(RandomAccessFile raf, long fileLen, long textStart, long textEnd)
            throws IOException {
        byte[] ineligible = "ineligible".getBytes(StandardCharsets.US_ASCII);
        byte[] inexigible = "inexigible".getBytes(StandardCharsets.US_ASCII);
        byte[] httpsProxy = "https_proxy".getBytes(StandardCharsets.US_ASCII);
        byte[] agLsProxy = "AG_LS_PROXY".getBytes(StandardCharsets.US_ASCII);
        byte[] binBash = "/bin/bash".getBytes(StandardCharsets.US_ASCII);
        byte[] binSh = "/bin///sh".getBytes(StandardCharsets.US_ASCII);
        byte[] etcResolv = "/etc/resolv.conf".getBytes(StandardCharsets.US_ASCII);
        byte[] relResolv = "etc//resolv.conf".getBytes(StandardCharsets.US_ASCII);
        byte[] sigillRaw = hexToBytes("e00740f9e10b40f9e20f40f9e31340f9c81080d2010000d4e02b00b9c0035fd6");
        byte[] sigillPatch = hexToBytes("e08740a9e28f41a91f1000f1c81080d240000054010000d4ff2b00b9c0035fd6");

        byte[] cliTail = hexToBytes("e10340b201200039");
        byte[] cliRawHead = hexToBytes("01204039");
        byte[] cliRepl = hexToBytes("21008052");

        byte[] entHead = hexToBytes("03204039");
        byte[] entTail = hexToBytes("031006a9");
        byte[] entRepl = hexToBytes("2300805203200039");

        byte[] cpuRepl = hexToBytes("190000141f2003d51f2003d51f2003d51f2003d51f2003d51f2003d51f2003d5");
        byte[] atomicTailRepl = hexToBytes("090140f92a008052490000b4c0035fd60a0100f9");

        byte[] buf = new byte[CHUNK_SIZE + OVERLAP];
        long offset = 0;

        while (offset < fileLen) {
            int toRead = (int) Math.min(buf.length, fileLen - offset);
            raf.seek(offset);
            raf.readFully(buf, 0, toRead);
            int scanLimit = (offset + toRead < fileLen) ? (toRead - OVERLAP) : toRead;

            List<Long> writesPos = new ArrayList<>();
            List<byte[]> writesData = new ArrayList<>();

            // Exact ASCII & binary patterns
            findExactAndQueue(buf, toRead, scanLimit, offset, ineligible, inexigible, writesPos, writesData);
            findExactAndQueue(buf, toRead, scanLimit, offset, httpsProxy, agLsProxy, writesPos, writesData);
            findExactAndQueue(buf, toRead, scanLimit, offset, binBash, binSh, writesPos, writesData);
            findExactAndQueue(buf, toRead, scanLimit, offset, sigillRaw, sigillPatch, writesPos, writesData);

            // /etc/resolv.conf with boundary check
            for (int i = 0; i < scanLimit && i + etcResolv.length <= toRead; i++) {
                if (matchesAt(buf, i, etcResolv)) {
                    byte prev = (i > 0) ? buf[i - 1] : 0;
                    byte next = (i + etcResolv.length < toRead) ? buf[i + etcResolv.length] : 0;
                    if (!isResolvReject(prev) && !isResolvReject(next)) {
                        writesPos.add(offset + i);
                        writesData.add(relResolv);
                    }
                }
            }

            // cli_gate: 01 20 40 39 ?? ?? ?? 37 e1 03 40 b2 01 20 00 39
            for (int i = 0; i < scanLimit && i + 16 <= toRead; i++) {
                if (matchesAt(buf, i, cliRawHead) && buf[i + 7] == 0x37 && matchesAt(buf, i + 8, cliTail)) {
                    writesPos.add(offset + i);
                    writesData.add(cliRepl);
                }
            }

            // enterprise_auth: 03 20 40 39 + tbz w3, #0 + (4 or 8 bytes) + 03 10 06 a9
            for (int i = 0; i < scanLimit && i + 20 <= toRead; i++) {
                if (matchesAt(buf, i, entHead)
                        && (buf[i + 4] & 0x1f) == 0x03 && buf[i + 7] == 0x36) {
                    if (matchesAt(buf, i + 12, entTail) || matchesAt(buf, i + 16, entTail)) {
                        writesPos.add(offset + i);
                        writesData.add(entRepl);
                    }
                }
            }

            // crash_suppressor inside .text
            if (offset + scanLimit > textStart && offset < textEnd) {
                for (int i = 0; i < scanLimit && i + 32 <= toRead; i++) {
                    long abs = offset + i;
                    if (abs < textStart || abs >= textEnd) continue;
                    if ((abs & 3) != 0) continue;
                    if (matchesCpuCheck(buf, i)) {
                        writesPos.add(abs);
                        writesData.add(cpuRepl);
                    } else if (matchesAtomicInit(buf, i)) {
                        writesPos.add(abs + 8);
                        writesData.add(atomicTailRepl);
                    }
                }
            }

            for (int k = 0; k < writesPos.size(); k++) {
                raf.seek(writesPos.get(k));
                raf.write(writesData.get(k));
            }

            if (offset + toRead >= fileLen) break;
            offset += CHUNK_SIZE;
        }
    }

    private static boolean matchesCpuCheck(byte[] buf, int i) {
        int w0 = readLe32(buf, i);
        int w1 = readLe32(buf, i + 4);
        int w2 = readLe32(buf, i + 8);
        int w3 = readLe32(buf, i + 12);
        int w4 = readLe32(buf, i + 16);
        int w5 = readLe32(buf, i + 20);
        int w6 = readLe32(buf, i + 24);
        int w7 = readLe32(buf, i + 28);
        return (w0 & 0x9f00001f) == 0x90000013
                && w1 == 0x5290600a
                && (w2 & 0xffc003ff) == 0xf9400268
                && w3 == 0x2a2803e9
                && w4 == 0xea0a013f
                && (w5 & 0xff00001f) == 0x54000001
                && (w6 & 0xfff8001f) == 0x36380008
                && (w7 & 0xfff8001f) == 0x36500008;
    }

    private static boolean matchesAtomicInit(byte[] buf, int i) {
        int w0 = readLe32(buf, i);
        int w1 = readLe32(buf, i + 4);
        int w2 = readLe32(buf, i + 8);
        int w3 = readLe32(buf, i + 12);
        int w4 = readLe32(buf, i + 16);
        int w5 = readLe32(buf, i + 20);
        int w6 = readLe32(buf, i + 24);
        return (w0 & 0x9f00001f) == 0x90000008
                && (w1 & 0xffc003ff) == 0x91000108
                && w2 == 0x52800029
                && w3 == 0xf8e90108
                && w4 == 0xb4000048
                && w5 == 0xd65f03c0
                && w6 == 0xd503201f;
    }

    private static int readLe32(byte[] buf, int off) {
        return (buf[off] & 0xff)
                | ((buf[off + 1] & 0xff) << 8)
                | ((buf[off + 2] & 0xff) << 16)
                | ((buf[off + 3] & 0xff) << 24);
    }

    private static boolean isResolvReject(byte b) {
        return b == '/' || b == '.' || b == '\\';
    }

    private static void findExactAndQueue(byte[] buf, int toRead, int scanLimit, long baseOffset,
                                          byte[] target, byte[] repl,
                                          List<Long> posOut, List<byte[]> dataOut) {
        byte first = target[0];
        for (int i = 0; i < scanLimit && i + target.length <= toRead; i++) {
            if (buf[i] == first && matchesAt(buf, i, target)) {
                posOut.add(baseOffset + i);
                dataOut.add(repl);
                i += target.length - 1;
            }
        }
    }

    private static boolean matchesAt(byte[] buf, int pos, byte[] pat) {
        for (int j = 0; j < pat.length; j++) {
            if (buf[pos + j] != pat[j]) return false;
        }
        return true;
    }

    public static String sha256File(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[1024 * 1024];
        try (FileInputStream fis = new FileInputStream(file)) {
            int n;
            while ((n = fis.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }
}
