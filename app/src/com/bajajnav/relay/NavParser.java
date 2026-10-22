package com.bajajnav.relay;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the ongoing Google Maps navigation notification.
 *
 * Google does not publish maneuver data, so the maneuver is recovered two ways:
 *  1. the notification's icon bitmap, matched against Google Maps' own named
 *     maneuver drawables (see {@link ManeuverMatcher}); and
 *  2. the instruction text, when it contains an explicit direction keyword.
 * Explicit text wins; otherwise the icon decides; otherwise straight.
 */
final class NavParser {
    static final String GMAPS = "com.google.android.apps.maps";

    private static final Pattern DIST = Pattern.compile(
            "(?i)(\\d+(?:[.,]\\d+)?)\\s*(km|mi|ft|yd|m)\\b");
    private static final Pattern ETA_MIN = Pattern.compile("(\\d+)\\s*min", Pattern.CASE_INSENSITIVE);
    private static final Pattern ETA_HR = Pattern.compile("(\\d+)\\s*(?:hr|hour)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2}):(\\d{2})(?:\\s*([ap]m))?", Pattern.CASE_INSENSITIVE);

    private static final String[] ICON_KEYS = {
            Notification.EXTRA_LARGE_ICON,                 // android.largeIcon
            "android.ongoingActivityNoti.chipIcon",        // Samsung ongoing-activity chip
            "android.ongoingActivityNoti.nowbarIcon",
            "android.ongoingActivityNoti.secondIcon",
    };

    private NavParser() {
    }

    static boolean isNavigation(Notification n) {
        if (n == null) {
            return false;
        }
        if (Notification.CATEGORY_NAVIGATION.equals(n.category)) {
            return true;
        }
        String cat = n.category;
        if (cat != null && cat.toLowerCase().contains("nav")) {
            return true;
        }
        Bundle ex = n.extras;
        return ex != null && ex.containsKey("android.subText");
    }

    static void dumpExtras(Notification n) {
        Bundle ex = n.extras;
        if (ex == null) {
            Lg.i("maps: notification has no extras");
            return;
        }
        for (String k : ex.keySet()) {
            Object v;
            try {
                v = ex.get(k);
            } catch (Exception e) {
                v = "<err " + e + ">";
            }
            String s;
            if (v instanceof Bitmap) {
                Bitmap bm = (Bitmap) v;
                s = "Bitmap " + bm.getWidth() + "x" + bm.getHeight();
            } else if (v == null) {
                s = "null";
            } else if (v instanceof Object[]) {
                Object[] a = (Object[]) v;
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < Math.min(a.length, 4); i++) {
                    sb.append(a[i]).append(i < Math.min(a.length, 4) - 1 ? ", " : "");
                }
                s = sb.append("]").toString();
            } else {
                s = v.toString();
            }
            Lg.i("maps.extra " + k + " = " + s);
        }
    }

    /** Returns true if this looked like a navigation update. */
    static boolean parse(Notification n) {
        if (!isNavigation(n)) {
            return false;
        }
        Bundle ex = n.extras;
        String title = cs(ex, Notification.EXTRA_TITLE);
        String text = cs(ex, Notification.EXTRA_TEXT);
        String subText = cs(ex, Notification.EXTRA_SUB_TEXT);
        String infoText = cs(ex, Notification.EXTRA_INFO_TEXT);
        String bigText = cs(ex, Notification.EXTRA_BIG_TEXT);

        // "Rerouting..." carries no route data; keep the previous state on the cluster.
        if (title.isEmpty() && subText.isEmpty() && text.toLowerCase().contains("rerout")) {
            Lg.i("maps.nav rerouting — keeping last state");
            return false;
        }

        NavState.rawTitle = title;
        NavState.rawText = text;
        NavState.rawSubText = subText;

        String instr = join(title, text, bigText);
        String lower = instr.toLowerCase();

        // --- maneuver -----------------------------------------------------
        int textManeuver = classifyManeuver(lower);
        ManeuverMatcher.Result mr = ManeuverMatcher.match(iconSource(ex));
        int maneuver;
        String how;
        if (textManeuver >= 0) {
            maneuver = textManeuver;
            how = "text";
        } else if (mr != null && mr.score >= 0.60) {
            maneuver = mr.icon;
            how = "icon:" + mr.name + " " + Math.round(mr.score * 100) + "%";
        } else if (mr != null) {
            maneuver = mr.icon;
            how = "icon-lowconf:" + mr.name + " " + Math.round(mr.score * 100) + "%";
        } else {
            maneuver = TbtFrame.STRAIGHT;
            how = "default";
        }

        int roundaboutExit = parseRoundaboutExit(instr);

        // --- distances ----------------------------------------------------
        int stepDist = parseDistanceMeters(title);
        if (stepDist < 0) {
            stepDist = parseDistanceMeters(text);
        }
        int totalDist = parseDistanceMeters(subText);
        if (totalDist < 0) {
            totalDist = parseDistanceMeters(infoText);
        }
        if (stepDist < 0) {
            stepDist = 0;
        }
        if (totalDist < 0) {
            totalDist = stepDist;
        }

        // --- ETA ----------------------------------------------------------
        int[] clock = parseEtaClock(subText);
        if (clock == null) {
            clock = parseEtaClock(infoText);
        }
        int etaSec = parseEtaSeconds(subText);
        if (etaSec < 0) {
            etaSec = parseEtaSeconds(instr);
        }

        // Blink the maneuver when the turn is imminent (< 100 m), matching the app.
        int icon = maneuver;
        if (stepDist >= 0 && stepDist < 100 && maneuver != TbtFrame.DEST_REACHED) {
            icon = maneuver + 32;
        }

        String street = cleanStreet(text, title, infoText);
        NavState.update(icon, stepDist, totalDist, etaSec,
                clock == null ? -1 : clock[0], clock == null ? -1 : clock[1],
                clock == null || clock[2] == 1, street);

        Lg.i("maps.nav -> icon=" + maneuver + " blink->" + icon + " [" + how + "]"
                + " match=" + (mr == null ? "null"
                        : mr.name + "/" + Math.round(mr.score * 100) + "%")
                + " step=" + stepDist + "m total=" + totalDist + "m eta=" + etaSec + "s clock="
                + (clock == null ? "-" : clock[0] + ":" + clock[1] + (clock[2] == 1 ? "am" : "pm"))
                + " exit=" + roundaboutExit + " street=\"" + street + "\"");
        return true;
    }

    /** First available maneuver icon: Maps largeIcon, else Samsung chip icons. */
    private static Object iconSource(Bundle ex) {
        if (ex == null) {
            return null;
        }
        for (String k : ICON_KEYS) {
            Object o;
            try {
                o = ex.get(k);
            } catch (Exception e) {
                continue;
            }
            if (o instanceof Icon || o instanceof Bitmap) {
                return o;
            }
        }
        return null;
    }

    private static String cs(Bundle ex, String key) {
        if (ex == null) {
            return "";
        }
        CharSequence c = ex.getCharSequence(key);
        return c == null ? "" : c.toString();
    }

    static String smallIconDesc(Bundle ex) {
        Object o = ex.get(Notification.EXTRA_SMALL_ICON);
        if (o == null) {
            return "none";
        }
        if (o instanceof Integer) {
            return "resId=" + Integer.toHexString((Integer) o);
        }
        if (Build.VERSION.SDK_INT >= 23 && o instanceof Icon) {
            Icon ic = (Icon) o;
            if (ic.getType() == Icon.TYPE_RESOURCE) {
                return "icon.resId=" + Integer.toHexString(ic.getResId());
            }
            return "icon.type=" + ic.getType();
        }
        return o.getClass().getSimpleName() + ":" + o;
    }

    /**
     * Returns a maneuver id, or -1 when the text carries no explicit direction.
     *
     * The arrival wording has to be checked carefully: Maps describes the last
     * turn as "Turn right onto X, then arrive at Y", so a bare "arrive" must not
     * override the turn — that made the cluster announce the destination one
     * maneuver early. Only an arrival with no direction of its own is a
     * destination.
     */
    static int classifyManeuver(String s) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        if (contains(s, "reached your destination", "you have reached", "you've reached",
                "you have arrived", "arrived at your destination")) {
            return TbtFrame.DEST_REACHED;
        }
        boolean hasDirection = contains(s, "turn left", "turn right", "left onto", "right onto",
                "left on", "right on", "keep left", "keep right", "slight", "sharp",
                "roundabout", "rotary", "traffic circle", "fork", "merge",
                "u-turn", "u turn", "uturn", "take the", "exit", "continue", "straight",
                "head ", "go ");
        if (!hasDirection && contains(s, "arrive")) {
            return TbtFrame.DEST_REACHED;
        }
        if (contains(s, "roundabout", "rotary", "traffic circle")) {
            return contains(s, "right") ? TbtFrame.ROUNDABOUT_RIGHT : TbtFrame.ROUNDABOUT_LEFT;
        }
        if (contains(s, "u-turn", "u turn", "uturn")) {
            return contains(s, "right") ? TbtFrame.U_TURN_RIGHT : TbtFrame.U_TURN_LEFT;
        }
        if (contains(s, "sharp left")) {
            return TbtFrame.SHARP_LEFT;
        }
        if (contains(s, "sharp right")) {
            return TbtFrame.SHARP_RIGHT;
        }
        if (contains(s, "slight left", "bear left")) {
            return TbtFrame.SLIGHT_LEFT;
        }
        if (contains(s, "slight right", "bear right")) {
            return TbtFrame.SLIGHT_RIGHT;
        }
        if (contains(s, "keep left")) {
            return TbtFrame.KEEP_LEFT;
        }
        if (contains(s, "keep right")) {
            return TbtFrame.KEEP_RIGHT;
        }
        if (contains(s, "merge")) {
            return TbtFrame.MERGE;
        }
        if (contains(s, "fork left")) {
            return TbtFrame.FORK_LEFT;
        }
        if (contains(s, "fork right")) {
            return TbtFrame.FORK_RIGHT;
        }
        if (contains(s, "take the", "exit")) {
            if (contains(s, "left")) {
                return TbtFrame.FORK_LEFT;
            }
            if (contains(s, "right")) {
                return TbtFrame.FORK_RIGHT;
            }
        }
        if (contains(s, "turn left", "left onto", "left on")) {
            return TbtFrame.TURN_LEFT;
        }
        if (contains(s, "turn right", "right onto", "right on")) {
            return TbtFrame.TURN_RIGHT;
        }
        if (contains(s, "continue", "straight", "head ", "go ")) {
            return TbtFrame.STRAIGHT;
        }
        return -1;
    }

    static int parseRoundaboutExit(String s) {
        if (s == null) {
            return 0;
        }
        String l = s.toLowerCase();
        Matcher m = Pattern.compile("(\\d+)(?:st|nd|rd|th)\\s+exit").matcher(l);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        m = Pattern.compile("exit\\s+(\\d+)").matcher(l);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /** Returns metres, or -1 when nothing parseable was found. */
    static int parseDistanceMeters(String s) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        Matcher m = DIST.matcher(s);
        if (m.find()) {
            double v = Double.parseDouble(m.group(1).replace(',', '.'));
            String u = m.group(2).toLowerCase();
            double metres;
            switch (u) {
                case "km":
                    metres = v * 1000.0;
                    break;
                case "mi":
                    metres = v * 1609.34;
                    break;
                case "ft":
                    metres = v * 0.3048;
                    break;
                case "yd":
                    metres = v * 0.9144;
                    break;
                default:
                    metres = v;
                    break;
            }
            return (int) Math.round(metres);
        }
        return -1;
    }

    /**
     * Parses the arrival clock shown in the sub-text, e.g. "4:49 am ETA" or "16:49".
     * Returns {hour12, minute, isAm(1|0)}, or null.
     */
    static int[] parseEtaClock(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        Matcher m = CLOCK.matcher(s);
        if (!m.find()) {
            return null;
        }
        int hr = Integer.parseInt(m.group(1));
        int mn = Integer.parseInt(m.group(2));
        String ap = m.group(3);
        boolean am;
        if (ap != null) {
            am = ap.equalsIgnoreCase("am");
        } else {
            am = hr < 12;
            if (hr == 0) {
                hr = 12;
            } else if (hr > 12) {
                hr -= 12;
            }
        }
        return new int[]{hr, mn, am ? 1 : 0};
    }

    /** Remaining time in seconds from "6 hr 17 min" / "12 min", or -1. */
    static int parseEtaSeconds(String s) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        int sec = 0;
        boolean found = false;
        Matcher h = ETA_HR.matcher(s);
        if (h.find()) {
            sec += Integer.parseInt(h.group(1)) * 3600;
            found = true;
        }
        Matcher m = ETA_MIN.matcher(s);
        if (m.find()) {
            sec += Integer.parseInt(m.group(1)) * 60;
            found = true;
        }
        return found ? sec : -1;
    }

    /** Reduces Maps' instruction text to just the road name. */
    private static String cleanStreet(String text, String title, String info) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            t = info == null ? "" : info.trim();
        }
        t = t.trim();
        String lt = t.toLowerCase();

        int i = lt.indexOf(" towards ");
        if (i >= 0) {
            t = t.substring(i + 9).trim();
        } else if (lt.startsWith("towards ")) {
            t = t.substring(8).trim();
        }
        lt = t.toLowerCase();
        i = lt.indexOf(" onto ");
        if (i >= 0) {
            t = t.substring(i + 6).trim();
        } else if (lt.startsWith("onto ")) {
            t = t.substring(5).trim();
        }
        lt = t.toLowerCase();

        // Anything still starting with a bare instruction has no road name; leave it blank.
        if (lt.startsWith("turn ") || lt.startsWith("keep ") || lt.startsWith("merge")
                || lt.startsWith("continue") || lt.startsWith("head ")
                || lt.startsWith("take ") || lt.startsWith("exit")
                || lt.startsWith("slight") || lt.startsWith("sharp")
                || lt.startsWith("u-turn") || lt.startsWith("u turn")
                || lt.startsWith("roundabout") || lt.startsWith("arrive")
                || lt.startsWith("start") || lt.startsWith("rerout")) {
            t = "";
        }
        return t.trim();
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                sb.append(p);
            }
        }
        return sb.toString();
    }

    private static boolean contains(String s, String... needles) {
        for (String n : needles) {
            if (s.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
