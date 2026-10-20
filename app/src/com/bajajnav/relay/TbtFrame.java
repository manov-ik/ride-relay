package com.bajajnav.relay;

import java.nio.charset.StandardCharsets;

/**
 * Builds the 48-byte turn-by-turn frame for characteristic 0110676e-...
 * Layout mirrors com.bajajconnect.utils.TbtNavFrame.buildNavHelperFrame.
 *
 * off size field
 *  0   1   type(bit0) | step-unit(bit4, 1=metres) | PM(bit7)
 *  1   1   maneuver icon id
 *  2-5 4   step distance, 16.16 fixed point, little-endian
 *  6   1   ETA minute
 *  7   1   ETA hour(1-12) | roundaboutExit<<4
 *  8-11 4  total distance, 16.16 fixed point, little-endian
 * 12   1   total-unit(bit0) | gps status(bits2-3, 1=active)
 * 13   1   takeMeHome ack
 * 14   1   text length
 * 15-45 31 text
 * 46   1   unused
 * 47   1   checksum = sum(0..46) & 0xFF
 */
final class TbtFrame {
    // PrimaryTurns values (base / non-blinking)
    static final int WRONG_WAY = 66;
    static final int SLIGHT_LEFT = 67;
    static final int SLIGHT_RIGHT = 68;
    static final int SHARP_LEFT = 69;
    static final int SHARP_RIGHT = 70;
    static final int STRAIGHT = 71;
    static final int DEST_REACHED = 72;
    static final int TURN_LEFT = 73;
    static final int TURN_RIGHT = 74;
    static final int RAMP_LEFT = 75;
    static final int RAMP_RIGHT = 76;
    static final int ROUNDABOUT_RIGHT = 78;
    static final int U_TURN_LEFT = 79;
    static final int U_TURN_RIGHT = 80;
    static final int FORK_LEFT = 81;
    static final int FORK_RIGHT = 82;
    static final int FERRY_TRAIN = 84;
    static final int ROUNDABOUT_LEFT = 85;
    static final int MERGE = 86;
    static final int KEEP_RIGHT = 88;
    static final int FERRY = 89;
    static final int KEEP_LEFT = 90;

    private static final int GPS_ACTIVE = 1;

    private TbtFrame() {
    }

    /**
     * Human-readable name for a maneuver id, including the blinking variants
     * (the app adds 32 to the icon when the turn is under 100 m).
     */
    static String maneuverName(int icon) {
        int b = icon >= 98 ? icon - 32 : icon;
        switch (b) {
            case WRONG_WAY:
                return "Wrong way";
            case SLIGHT_LEFT:
                return "Slight left";
            case SLIGHT_RIGHT:
                return "Slight right";
            case SHARP_LEFT:
                return "Sharp left";
            case SHARP_RIGHT:
                return "Sharp right";
            case STRAIGHT:
                return "Continue straight";
            case DEST_REACHED:
                return "Destination";
            case TURN_LEFT:
                return "Turn left";
            case TURN_RIGHT:
                return "Turn right";
            case RAMP_LEFT:
                return "Ramp left";
            case RAMP_RIGHT:
                return "Ramp right";
            case ROUNDABOUT_RIGHT:
                return "Roundabout, right";
            case U_TURN_LEFT:
                return "U-turn left";
            case U_TURN_RIGHT:
                return "U-turn right";
            case FORK_LEFT:
                return "Fork left";
            case FORK_RIGHT:
                return "Fork right";
            case FERRY_TRAIN:
                return "Ferry / train";
            case ROUNDABOUT_LEFT:
                return "Roundabout, left";
            case MERGE:
                return "Merge";
            case KEEP_RIGHT:
                return "Keep right";
            case FERRY:
                return "Ferry";
            case KEEP_LEFT:
                return "Keep left";
            default:
                return "Maneuver " + b;
        }
    }

    /** "170 m" / "1.2 km", for on-screen display. */
    static String distanceText(int metres) {
        if (metres < 1000) {
            return Math.max(0, metres) + " m";
        }
        return String.format(java.util.Locale.US, "%.1f km", metres / 1000.0);
    }

    /**
     * formatDistance returns {unit, iHigh, iLow, fHigh, fLow} (big-endian shorts),
     * matching NavigationHelper.formatDistance + Util.toBytes.
     */
    static byte[] formatDistance(double d) {
        double v;
        int unit;
        if (d < 1000.0d) {
            v = (int) d;
            unit = 1;
        } else {
            v = d / 1000.0d;
            unit = 0;
        }
        int i = (int) v;
        int frac = (int) ((v - i) * 100.0d);
        return new byte[]{
                (byte) unit,
                (byte) ((i >> 8) & 0xFF),
                (byte) (i & 0xFF),
                (byte) ((frac >> 8) & 0xFF),
                (byte) (frac & 0xFF)
        };
    }

    static byte[] build(int type, int maneuver, boolean isAm, int stepMeters, int totalMeters,
                        int etaHour, int etaMinute, int roundaboutExit, String text) {
        byte[] step = formatDistance(Math.max(0, stepMeters));
        byte[] total = formatDistance(Math.max(0, totalMeters));
        String t = text == null ? "" : text.replaceAll("[^0-9a-zA-Z.]", " ").trim();
        if (t.length() > 31) {
            t = t.substring(0, 31) + ".";
        }
        byte[] b = new byte[48];
        b[0] = (byte) type;
        b[0] = (byte) (b[0] | (step[0] << 4));
        b[0] = (byte) (b[0] | ((isAm ? 0 : 1) << 7));
        b[1] = (byte) maneuver;
        b[2] = step[4];
        b[3] = step[3];
        b[4] = step[2];
        b[5] = step[1];
        b[6] = (byte) (etaMinute & 0xFF);
        b[7] = (byte) ((etaHour & 0xFF) | ((roundaboutExit & 0xF) << 4));
        b[8] = total[4];
        b[9] = total[3];
        b[10] = total[2];
        b[11] = total[1];
        b[12] = (byte) (total[0] | (GPS_ACTIVE << 2));
        b[13] = 0;
        if (t.length() > 0) {
            byte[] tb = t.getBytes(StandardCharsets.US_ASCII);
            int n = Math.min(tb.length, 31);
            b[14] = (byte) n;
            System.arraycopy(tb, 0, b, 15, n);
        }
        int sum = 0;
        for (int i = 0; i < 47; i++) {
            sum = (sum + b[i]) & 0xFF;
        }
        b[47] = (byte) sum;
        return b;
    }

    /** Nav stop frame: type 0, everything else zeroed. */
    static byte[] stop() {
        return build(0, 0, true, 0, 0, 0, 0, 0, "");
    }

    static String hex(byte[] a) {
        StringBuilder sb = new StringBuilder();
        for (byte x : a) {
            sb.append(String.format("%02X ", x));
        }
        return sb.toString().trim();
    }
}
