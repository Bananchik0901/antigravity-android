package com.agy.updater;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Pure-Java binary AndroidManifest.xml (AXML) editor.
 * Updates android:versionCode (0x0101021b) and optionally android:versionName (0x0101021c).
 */
public final class AxmlPatcher {

    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_XML_RESOURCE_MAP_TYPE = 0x0180;
    private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;

    private static final int ATTR_RES_VERSION_CODE = 0x0101021b;
    private static final int ATTR_RES_VERSION_NAME = 0x0101021c;

    private AxmlPatcher() {}

    public static int readVersionCode(byte[] axml) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
        int type = bb.getShort(0) & 0xffff;
        if (type != RES_XML_TYPE) throw new IOException("Invalid AXML magic");

        int pos = bb.getShort(2) & 0xffff;
        int[] resMap = new int[0];

        while (pos + 8 <= axml.length) {
            int cType = bb.getShort(pos) & 0xffff;
            int cHdr = bb.getShort(pos + 2) & 0xffff;
            int cSize = bb.getInt(pos + 4);
            if (cSize < 8) break;

            if (cType == RES_XML_RESOURCE_MAP_TYPE) {
                int count = (cSize - cHdr) / 4;
                resMap = new int[count];
                for (int i = 0; i < count; i++) {
                    resMap[i] = bb.getInt(pos + cHdr + i * 4);
                }
            } else if (cType == RES_XML_START_ELEMENT_TYPE) {
                int attrCount = bb.getShort(pos + 28) & 0xffff;
                int attrStart = pos + 36;
                for (int i = 0; i < attrCount; i++) {
                    int a = attrStart + i * 20;
                    int nameIdx = bb.getInt(a + 4);
                    if (nameIdx >= 0 && nameIdx < resMap.length && resMap[nameIdx] == ATTR_RES_VERSION_CODE) {
                        return bb.getInt(a + 16);
                    }
                }
            }
            pos += cSize;
        }
        throw new IOException("android:versionCode not found in AXML");
    }

    public static byte[] patchVersionCodeAndName(byte[] axml, int newVersionCode, String newVersionName)
            throws IOException {
        byte[] work = axml.clone();
        int newNameIdx = -1;

        if (newVersionName != null && !newVersionName.isEmpty()) {
            AppendResult ar = appendStringToPool(work, newVersionName);
            work = ar.axml;
            newNameIdx = ar.newStringIndex;
        }

        ByteBuffer bb = ByteBuffer.wrap(work).order(ByteOrder.LITTLE_ENDIAN);
        int pos = bb.getShort(2) & 0xffff;
        int[] resMap = new int[0];
        boolean foundCode = false;

        while (pos + 8 <= work.length) {
            int cType = bb.getShort(pos) & 0xffff;
            int cHdr = bb.getShort(pos + 2) & 0xffff;
            int cSize = bb.getInt(pos + 4);
            if (cSize < 8) break;

            if (cType == RES_XML_RESOURCE_MAP_TYPE) {
                int count = (cSize - cHdr) / 4;
                resMap = new int[count];
                for (int i = 0; i < count; i++) {
                    resMap[i] = bb.getInt(pos + cHdr + i * 4);
                }
            } else if (cType == RES_XML_START_ELEMENT_TYPE) {
                int attrCount = bb.getShort(pos + 28) & 0xffff;
                int attrStart = pos + 36;
                for (int i = 0; i < attrCount; i++) {
                    int a = attrStart + i * 20;
                    int nameIdx = bb.getInt(a + 4);
                    if (nameIdx >= 0 && nameIdx < resMap.length) {
                        if (resMap[nameIdx] == ATTR_RES_VERSION_CODE) {
                            bb.putInt(a + 16, newVersionCode);
                            foundCode = true;
                        } else if (newNameIdx >= 0 && resMap[nameIdx] == ATTR_RES_VERSION_NAME) {
                            bb.putInt(a + 8, newNameIdx);
                            bb.putShort(a + 12, (short) 8);
                            work[a + 14] = 0;
                            work[a + 15] = 0x03; // TYPE_STRING
                            bb.putInt(a + 16, newNameIdx);
                        }
                    }
                }
            }
            pos += cSize;
        }

        if (!foundCode) {
            throw new IOException("android:versionCode not found in AXML");
        }
        return work;
    }

    private static final class AppendResult {
        final byte[] axml;
        final int newStringIndex;
        AppendResult(byte[] axml, int newStringIndex) {
            this.axml = axml;
            this.newStringIndex = newStringIndex;
        }
    }

    private static AppendResult appendStringToPool(byte[] axml, String str) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
        int spPos = bb.getShort(2) & 0xffff;
        int spType = bb.getShort(spPos) & 0xffff;
        if (spType != RES_STRING_POOL_TYPE) {
            throw new IOException("Expected StringPool chunk at offset " + spPos);
        }
        int spHdrSize = bb.getShort(spPos + 2) & 0xffff;
        int oldSpSize = bb.getInt(spPos + 4);
        int stringCount = bb.getInt(spPos + 8);
        int styleCount = bb.getInt(spPos + 12);
        int flags = bb.getInt(spPos + 16);
        int stringsStart = bb.getInt(spPos + 20);
        int stylesStart = bb.getInt(spPos + 24);
        boolean isUtf8 = (flags & 0x100) != 0;

        byte[] encodedEntry = encodeStringPoolEntry(str, isUtf8);

        int oldOffsetsStart = spPos + spHdrSize;
        int oldOffsetsLen = (stringCount + styleCount) * 4;
        int oldStringsDataStart = spPos + stringsStart;
        int oldStringsDataEnd = (stylesStart > 0) ? (spPos + stylesStart) : (spPos + oldSpSize);
        int oldStringsDataLen = oldStringsDataEnd - oldStringsDataStart;
        int oldStylesDataLen = (stylesStart > 0) ? ((spPos + oldSpSize) - (spPos + stylesStart)) : 0;

        int newStringsStart = stringsStart + 4;
        int unpaddedStringsLen = oldStringsDataLen + encodedEntry.length;
        int paddedStringsLen = (unpaddedStringsLen + 3) & ~3;
        int newStylesStart = (stylesStart > 0) ? (newStringsStart + paddedStringsLen) : 0;
        int newSpSize = newStringsStart + paddedStringsLen + oldStylesDataLen;
        int sizeDelta = newSpSize - oldSpSize;

        ByteArrayOutputStream spOut = new ByteArrayOutputStream(newSpSize);
        byte[] hdr = new byte[spHdrSize];
        System.arraycopy(axml, spPos, hdr, 0, spHdrSize);
        ByteBuffer hbb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        hbb.putInt(4, newSpSize);
        hbb.putInt(8, stringCount + 1);
        hbb.putInt(20, newStringsStart);
        if (stylesStart > 0) {
            hbb.putInt(24, newStylesStart);
        }
        spOut.write(hdr);

        // Existing string offsets
        spOut.write(axml, oldOffsetsStart, stringCount * 4);
        // New string offset (relative to stringsStart)
        byte[] newOff = new byte[4];
        ByteBuffer.wrap(newOff).order(ByteOrder.LITTLE_ENDIAN).putInt(oldStringsDataLen);
        spOut.write(newOff);
        // Style offsets
        if (styleCount > 0) {
            spOut.write(axml, oldOffsetsStart + stringCount * 4, styleCount * 4);
        }

        // Old strings data + new encoded string + zero padding to 4-byte boundary
        spOut.write(axml, oldStringsDataStart, oldStringsDataLen);
        spOut.write(encodedEntry);
        for (int p = unpaddedStringsLen; p < paddedStringsLen; p++) {
            spOut.write(0);
        }

        // Old styles data if present
        if (oldStylesDataLen > 0) {
            spOut.write(axml, spPos + stylesStart, oldStylesDataLen);
        }

        byte[] newSpBytes = spOut.toByteArray();
        ByteArrayOutputStream fullOut = new ByteArrayOutputStream(axml.length + sizeDelta);
        fullOut.write(axml, 0, spPos);
        fullOut.write(newSpBytes);
        int restStart = spPos + oldSpSize;
        fullOut.write(axml, restStart, axml.length - restStart);

        byte[] res = fullOut.toByteArray();
        ByteBuffer.wrap(res).order(ByteOrder.LITTLE_ENDIAN).putInt(4, res.length);
        return new AppendResult(res, stringCount);
    }

    private static byte[] encodeStringPoolEntry(String s, boolean isUtf8) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (isUtf8) {
            byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
            writeLen8(out, s.length());
            writeLen8(out, utf8.length);
            out.write(utf8);
            out.write(0);
        } else {
            byte[] utf16 = s.getBytes(StandardCharsets.UTF_16LE);
            writeLen16(out, s.length());
            out.write(utf16);
            out.write(0);
            out.write(0);
        }
        return out.toByteArray();
    }

    private static void writeLen8(ByteArrayOutputStream out, int len) {
        if (len >= 0x80) {
            out.write(((len >> 8) & 0x7f) | 0x80);
            out.write(len & 0xff);
        } else {
            out.write(len);
        }
    }

    private static void writeLen16(ByteArrayOutputStream out, int len) {
        if (len >= 0x8000) {
            int hi = ((len >> 16) & 0x7fff) | 0x8000;
            int lo = len & 0xffff;
            out.write(hi & 0xff);
            out.write((hi >> 8) & 0xff);
            out.write(lo & 0xff);
            out.write((lo >> 8) & 0xff);
        } else {
            out.write(len & 0xff);
            out.write((len >> 8) & 0xff);
        }
    }
}
