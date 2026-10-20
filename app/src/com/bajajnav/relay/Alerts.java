package com.bajajnav.relay;

/**
 * The missed-call packet.
 *
 * The cluster does not get this pushed at it and then display it: it counts
 * missed calls (general packet byte 13), shows a badge, and *pulls* the details
 * when the rider opens the list — that pull shows up as a changed byte in the
 * control characteristic. See {@link Controls}.
 *
 * Layout is the official app's (ble.protocol.NewMissedCallPacketV2). It carries
 * no checksum; the receiver trusts the length bytes.
 */
final class Alerts {
    private Alerts() {
    }

    /** 63 bytes for the MISSED_CALL characteristic (0310676e). */
    static byte[] missedCall(String name, String number, long epochSeconds) {
        byte[] b = new byte[63];
        b[0] = 1; // reserved id, always 1 in the official packet
        String who = Pack.clean(name);
        String num = Pack.clean(number);
        // The cluster falls back to the number when the name is empty, so when we
        // have no contact name there is no point writing the number twice.
        if (!who.isEmpty() && !who.equals(num)) {
            byte[] nb = Pack.utf8(who, 32);
            b[1] = (byte) nb.length;
            System.arraycopy(nb, 0, b, 2, nb.length);
        }
        byte[] numb = Pack.utf8(num, 18);
        b[34] = (byte) numb.length;
        System.arraycopy(numb, 0, b, 35, numb.length);
        Pack.putTime(b, 53, epochSeconds);
        return b;
    }
}
