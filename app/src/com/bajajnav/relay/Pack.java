package com.bajajnav.relay;

import java.nio.charset.StandardCharsets;

/**
 * Byte-level helpers shared by the packets we write to the cluster.
 *
 * The cluster's text fields are fixed-width UTF-8 blocks with a separate length
 * byte, so everything here is about never lying to that length byte and never
 * handing the cluster a glyph it will draw as a box.
 */
final class Pack {
    private Pack() {
    }

    /**
     * Drop control and unassigned characters. The official app uses
     * {@code replaceAll("[\\p{Cn}]", "")}; this is the same idea with the newlines
     * Android puts in notification text turned into spaces so the cluster does not
     * print a gap in the middle of a name.
     */
    static String clean(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(' ');
                continue;
            }
            int type = Character.getType(c);
            if (type == Character.CONTROL || type == Character.UNASSIGNED
                    || type == Character.FORMAT) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /** UTF-8 bytes of {@code s}, hard-limited to {@code max} without splitting a char. */
    static byte[] utf8(String s, int max) {
        byte[] all = s.getBytes(StandardCharsets.UTF_8);
        if (all.length <= max) {
            return all;
        }
        int end = max;
        while (end > 0 && (all[end] & 0xC0) == 0x80) {
            end--;
        }
        byte[] out = new byte[end];
        System.arraycopy(all, 0, out, 0, end);
        return out;
    }

    /** 32-bit big-endian epoch seconds, the order the missed-call packet uses. */
    static void putTime(byte[] b, int off, long epochSeconds) {
        int t = (int) (epochSeconds & 0xFFFFFFFFL);
        b[off] = (byte) (t >>> 24);
        b[off + 1] = (byte) (t >>> 16);
        b[off + 2] = (byte) (t >>> 8);
        b[off + 3] = (byte) t;
    }

    /** Sum of the first {@code n} bytes, the checksum every framed packet uses. */
    static byte checksum(byte[] b, int n) {
        int s = 0;
        for (int i = 0; i < n; i++) {
            s = (s + (b[i] & 0xFF)) & 0xFF;
        }
        return (byte) s;
    }

    static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }
}
