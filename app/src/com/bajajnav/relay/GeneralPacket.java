package com.bajajnav.relay;

import android.os.Build;

import java.util.Arrays;

/**
 * The general status packet written to 0210676e (GENERAL_CHAR): the cluster's
 * always-on summary of the phone — volume, battery, call state, caller name and
 * how many alerts/missed calls are waiting.
 *
 * Two variants exist in the official app and the bike firmware picks one by SKU,
 * so the mode stays user-selectable:
 *  - legacy (utils.CallFrame.phoneStatusNew): 55 bytes, no checksum
 *  - v2 (ble.protocol.NewGeneralPacketV2.build): 89 bytes + checksum
 *
 * Layouts here are byte-for-byte the official ones. In particular the cluster
 * only has two bits for battery (clamped to 3, i.e. it shows a coarse bar) and
 * the caller name gets a "sticky" length so a shorter name always erases the
 * tail of a longer previous one.
 */
final class GeneralPacket {
    static final int MODE_OFF = 0;
    static final int MODE_LEGACY55 = 1;
    static final int MODE_V2_89 = 2;

    private static final int LEGACY_NAME_OFFSET = 21;
    private static final int LEGACY_NAME_MAX = 30;
    private static final int V2_NAME_OFFSET = 20;
    private static final int V2_NAME_MAX = 32;

    private static int heartbeat = 0;
    private static int stickyCallerOverwriteLen = 0;

    /** Media volume 0..15, mirrored from the cluster's volume keys. */
    static volatile int volume = 5;
    /** Battery in five bars (0..4), the way the official app scales it. */
    static volatile int battery = 3;

    private GeneralPacket() {
    }

    static byte[] build(int mode) {
        if (mode == MODE_LEGACY55) {
            return legacy();
        }
        return v2();
    }

    private static int statusByteZero() {
        return (volume & 0x0F) | 0xC0; // volume, no headset, marker 0xC0
    }

    private static int statusByteOne() {
        int level = Math.min(battery & 0xFF, 3);
        // v2: dnd off, two battery bits, call state in the low nibble
        return ((level & 3) << 4) | (PhoneBridge.get().callState() & 0x0F);
    }

    /** Legacy puts the five-bar battery at bits 3-5 instead. */
    private static int legacyStatusByteOne() {
        return (battery & 0x07) << 3 | (PhoneBridge.get().callState() & 0x0F);
    }

    /** Name to show for a ringing/active call: contact name, else the number. */
    private static String caller() {
        String name = Pack.clean(PhoneBridge.get().callerName());
        return name;
    }

    private static byte[] legacy() {
        heartbeat++;
        PhoneBridge phone = PhoneBridge.get();
        byte[] b = new byte[55];
        b[0] = (byte) statusByteZero();
        b[1] = (byte) legacyStatusByteOne();
        b[2] = 4; // signal strength: unknown, never 0 in the official app
        b[3] = (byte) (phone.callState() == PhoneBridge.CALL_ACTIVE ? 1 : 0);
        b[4] = (byte) (phone.callState() == PhoneBridge.CALL_ACTIVE ? 0 : 1);
        b[5] = (byte) phone.callRejectAck();
        b[6] = (byte) phone.callRejectSmsAck();
        b[13] = (byte) phone.missedCount();
        b[14] = (byte) phone.missedCallGetAck();
        b[15] = (byte) phone.alertCount();
        b[16] = (byte) phone.alertGetAck();
        b[17] = 0;
        if (hasCaller(phone.callState())) {
            String name = caller();
            if (!name.isEmpty()) {
                b[18] = 1;
                byte[] bytes = Pack.utf8(name, LEGACY_NAME_MAX);
                // Clear the field first: the cluster keeps drawing whatever is
                // left in the bytes past the new name's length.
                Arrays.fill(b, LEGACY_NAME_OFFSET, LEGACY_NAME_OFFSET + LEGACY_NAME_MAX, (byte) 0);
                System.arraycopy(bytes, 0, b, LEGACY_NAME_OFFSET, bytes.length);
                int len = Math.max(bytes.length, stickyCallerOverwriteLen);
                b[20] = (byte) Math.min(len, LEGACY_NAME_MAX);
                stickyCallerOverwriteLen = bytes.length;
            }
        } else {
            stickyCallerOverwriteLen = 0;
            b[18] = 0;
        }
        b[53] = (byte) heartbeat;
        return b;
    }

    private static byte[] v2() {
        heartbeat++;
        PhoneBridge phone = PhoneBridge.get();
        byte[] b = new byte[89];
        b[0] = (byte) statusByteZero();
        b[1] = (byte) statusByteOne();
        b[2] = (byte) 4; // signal (bits 0-2), birthday day 0
        b[3] = 0;        // birthday month
        b[4] = (byte) phone.callAcceptAck();
        b[5] = (byte) phone.callRejectAck();
        b[6] = (byte) phone.callRejectSmsAck();
        b[13] = (byte) phone.missedCount();
        b[14] = (byte) phone.missedCallGetAck();
        b[15] = (byte) phone.alertCount();
        b[16] = (byte) phone.alertGetAck();
        b[17] = 0;
        if (hasCaller(phone.callState())) {
            String name = caller();
            if (!name.isEmpty()) {
                b[18] = 1;
                byte[] bytes = Pack.utf8(name, V2_NAME_MAX);
                Arrays.fill(b, V2_NAME_OFFSET, V2_NAME_OFFSET + V2_NAME_MAX, (byte) 0);
                System.arraycopy(bytes, 0, b, V2_NAME_OFFSET, bytes.length);
                int len = Math.max(bytes.length, stickyCallerOverwriteLen);
                b[19] = (byte) Math.min(len, V2_NAME_MAX);
                stickyCallerOverwriteLen = bytes.length;
            }
        } else {
            stickyCallerOverwriteLen = 0;
            b[18] = 0;
        }
        int progress = phone.nextCallProgress();
        b[52] = (byte) (progress & 0xFF);
        b[53] = (byte) ((progress >> 8) & 0xFF);
        b[54] = (byte) heartbeat;
        String model = Build.MODEL == null ? "Android" : Build.MODEL;
        byte[] mb = Pack.utf8(Pack.clean(model), 32);
        b[55] = (byte) mb.length;
        System.arraycopy(mb, 0, b, 56, mb.length);
        b[88] = Pack.checksum(b, 88);
        return b;
    }

    private static boolean hasCaller(int state) {
        return state == PhoneBridge.CALL_INCOMING
                || state == PhoneBridge.CALL_OUTGOING
                || state == PhoneBridge.CALL_ACTIVE;
    }
}
