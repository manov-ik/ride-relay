package com.bajajnav.relay;

/**
 * The two "something happened on the phone" packets.
 *
 * The cluster does not get these pushed at it and then display them: it counts
 * them (general packet bytes 13/15), shows a badge, and *pulls* the body when the
 * rider opens the list — that pull shows up as a changed byte in the control
 * characteristic. See {@link Controls}.
 *
 * Layouts are the official app's (ble.protocol.NewAlertsPacketV2 /
 * NewMissedCallPacketV2). Neither carries a checksum; the receiver trusts the
 * length bytes.
 */
final class Alerts {
    /** Alert type 1 = a text message, 2 = WhatsApp. From com.bajajconnect.enums.Alert. */
    static final int TYPE_SMS = 1;
    static final int TYPE_WHATSAPP = 2;

    private Alerts() {
    }

    /** 44 bytes for the ALERTS_INFO characteristic (0410676e). */
    static byte[] alert(int type, String sender, long epochSeconds) {
        byte[] b = new byte[44];
        b[0] = (byte) type;
        byte[] name = Pack.utf8(Pack.clean(sender), 32);
        b[1] = (byte) name.length;
        System.arraycopy(name, 0, b, 2, name.length);
        Pack.putTime(b, 34, epochSeconds);
        return b;
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
