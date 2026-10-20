package com.bajajnav.relay;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;

import java.util.ArrayList;
import java.util.List;

/**
 * Identifies the maneuver from the Google Maps notification icon by matching it
 * against Google Maps' own named drawables (maneuver_turn_normal_left, ...),
 * loaded at runtime from the Maps package resources.
 *
 * Comparison is shape-based: both images are cropped to their foreground
 * bounding box and resampled to a small boolean grid, so size/tint/framing
 * differences don't matter.
 */
final class ManeuverMatcher {
    private static final String GMAPS = "com.google.android.apps.maps";
    private static final int N = 28;          // grid resolution
    private static final int RENDER = 128;    // template render size

    private static final String[] NAMES = {
            "maneuver_straight", "maneuver_depart", "maneuver_name_change",
            "maneuver_destination", "maneuver_destination_straight",
            "maneuver_destination_left", "maneuver_destination_right",
            "maneuver_turn_normal_left", "maneuver_turn_normal_right",
            "maneuver_turn_slight_left", "maneuver_turn_slight_right",
            "maneuver_turn_sharp_left", "maneuver_turn_sharp_right",
            "maneuver_u_turn_left", "maneuver_u_turn_right",
            "maneuver_keep_left", "maneuver_keep_right",
            "maneuver_fork_left", "maneuver_fork_right",
            "maneuver_merge", "maneuver_merge_left", "maneuver_merge_right",
            "maneuver_on_ramp_keep_left", "maneuver_on_ramp_normal_left",
            "maneuver_on_ramp_slight_left", "maneuver_on_ramp_sharp_left",
            "maneuver_on_ramp_u_turn_left",
            "maneuver_on_ramp_keep_right", "maneuver_on_ramp_normal_right",
            "maneuver_on_ramp_slight_right", "maneuver_on_ramp_sharp_right",
            "maneuver_on_ramp_u_turn_right",
            "maneuver_off_ramp_keep_left", "maneuver_off_ramp_normal_left",
            "maneuver_off_ramp_slight_left", "maneuver_off_ramp_sharp_left",
            "maneuver_off_ramp_u_turn_left",
            "maneuver_off_ramp_keep_right", "maneuver_off_ramp_normal_right",
            "maneuver_off_ramp_slight_right", "maneuver_off_ramp_sharp_right",
            "maneuver_off_ramp_u_turn_right",
            "maneuver_roundabout_enter_cw", "maneuver_roundabout_enter_ccw",
            "maneuver_roundabout_exit_cw", "maneuver_roundabout_exit_ccw",
            "maneuver_roundabout_enter_and_exit_cw",
            "maneuver_roundabout_enter_and_exit_ccw",
            "maneuver_roundabout_enter_and_exit_cw_normal_left",
            "maneuver_roundabout_enter_and_exit_cw_normal_right",
            "maneuver_roundabout_enter_and_exit_ccw_normal_left",
            "maneuver_roundabout_enter_and_exit_ccw_normal_right",
            "maneuver_roundabout_enter_and_exit_cw_slight_left",
            "maneuver_roundabout_enter_and_exit_cw_slight_right",
            "maneuver_roundabout_enter_and_exit_ccw_slight_left",
            "maneuver_roundabout_enter_and_exit_ccw_slight_right",
            "maneuver_roundabout_enter_and_exit_cw_sharp_left",
            "maneuver_roundabout_enter_and_exit_cw_sharp_right",
            "maneuver_roundabout_enter_and_exit_ccw_sharp_left",
            "maneuver_roundabout_enter_and_exit_ccw_sharp_right",
            "maneuver_roundabout_enter_and_exit_cw_straight",
            "maneuver_roundabout_enter_and_exit_ccw_straight",
            "maneuver_roundabout_enter_and_exit_cw_u_turn",
            "maneuver_roundabout_enter_and_exit_ccw_u_turn",
    };

    static final class Result {
        final String name;
        final int icon;
        final double score;   // 1.0 = identical

        Result(String name, int icon, double score) {
            this.name = name;
            this.icon = icon;
            this.score = score;
        }
    }

    private static final List<boolean[]> TPL = new ArrayList<>();
    private static final List<Integer> TPL_ID = new ArrayList<>();
    private static final List<String> TPL_NAME = new ArrayList<>();
    private static volatile boolean ready = false;
    private static volatile boolean failed = false;
    private static volatile boolean building = false;
    private static Context appCtx;

    private ManeuverMatcher() {
    }

    static void prime(Context c) {
        if (appCtx == null) {
            appCtx = c.getApplicationContext();
        }
        new Thread(() -> {
            try {
                ensureBuilt();
            } catch (Throwable ignored) {
            }
        }, "maneuver-tpl").start();
    }

    /** Renders an Icon (or uses a Bitmap as-is) into a normalised mask and matches it. */
    static Result match(Object src) {
        if (src == null || appCtx == null) {
            return null;
        }
        ensureBuilt();
        if (!ready || TPL.isEmpty()) {
            return null;
        }
        Bitmap b = toBitmap(src);
        if (b == null) {
            return null;
        }
        boolean[] m = normalize(b);
        if (m == null) {
            return null;
        }
        int best = -1;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < TPL.size(); i++) {
            int d = xor(m, TPL.get(i));
            if (d < bestDiff) {
                bestDiff = d;
                best = i;
            }
        }
        if (best < 0) {
            return null;
        }
        double score = 1.0 - (bestDiff / (double) (N * N));
        return new Result(TPL_NAME.get(best), TPL_ID.get(best), score);
    }

    private static Bitmap toBitmap(Object src) {
        if (src instanceof Bitmap) {
            return (Bitmap) src;
        }
        if (src instanceof Icon) {
            try {
                Drawable d = ((Icon) src).loadDrawable(appCtx);
                if (d == null) {
                    return null;
                }
                Bitmap bm = Bitmap.createBitmap(RENDER, RENDER, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(bm);
                d.setBounds(0, 0, RENDER, RENDER);
                d.draw(cv);
                return bm;
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static synchronized void ensureBuilt() {
        if (ready || failed || building) {
            return;
        }
        building = true;
        long t0 = System.currentTimeMillis();
        try {
            if (appCtx == null) {
                failed = true;
                return;
            }
            Context mc = appCtx.createPackageContext(GMAPS, Context.CONTEXT_IGNORE_SECURITY);
            Resources res = mc.getResources();
            for (String name : NAMES) {
                int id = res.getIdentifier(name, "drawable", GMAPS);
                if (id == 0) {
                    continue;
                }
                Drawable d;
                try {
                    d = res.getDrawable(id, null);
                } catch (Throwable t) {
                    continue;
                }
                if (d == null) {
                    continue;
                }
                Bitmap bm = Bitmap.createBitmap(RENDER, RENDER, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(bm);
                d.setBounds(0, 0, RENDER, RENDER);
                try {
                    d.draw(cv);
                } catch (Throwable t) {
                    bm.recycle();
                    continue;
                }
                boolean[] m = normalize(bm);
                bm.recycle();
                if (m != null) {
                    TPL.add(m);
                    TPL_ID.add(iconFor(name));
                    TPL_NAME.add(name);
                }
            }
            ready = true;
            Lg.i("maneuver templates built: " + TPL.size() + " in "
                    + (System.currentTimeMillis() - t0) + "ms");
        } catch (Throwable t) {
            failed = true;
            Lg.w("maneuver template build failed: " + t);
        } finally {
            building = false;
        }
    }

    private static int xor(boolean[] a, boolean[] b) {
        int d = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                d++;
            }
        }
        return d;
    }

    /** Crops to the foreground bounding box and resamples to an N x N boolean grid. */
    private static boolean[] normalize(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);

        boolean hasAlpha = false;
        for (int p : px) {
            if (((p >>> 24) & 0xFF) < 250) {
                hasAlpha = true;
                break;
            }
        }

        boolean[] fg = new boolean[w * h];
        if (hasAlpha) {
            for (int i = 0; i < px.length; i++) {
                fg[i] = ((px[i] >>> 24) & 0xFF) > 40;
            }
        } else {
            int min = 255;
            int max = 0;
            int[] lum = new int[px.length];
            for (int i = 0; i < px.length; i++) {
                int r = (px[i] >> 16) & 0xFF;
                int g = (px[i] >> 8) & 0xFF;
                int bl = px[i] & 0xFF;
                int l = (r * 77 + g * 151 + bl * 28) >> 8;
                lum[i] = l;
                if (l < min) {
                    min = l;
                }
                if (l > max) {
                    max = l;
                }
            }
            int mid = (min + max) / 2;
            int hi = 0;
            for (int l : lum) {
                if (l > mid) {
                    hi++;
                }
            }
            boolean brightIsForeground = hi <= px.length / 2; // arrow is the minority
            for (int i = 0; i < px.length; i++) {
                fg[i] = brightIsForeground ? (lum[i] > mid) : (lum[i] <= mid);
            }
        }

        int minx = w;
        int miny = h;
        int maxx = -1;
        int maxy = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (fg[y * w + x]) {
                    if (x < minx) {
                        minx = x;
                    }
                    if (x > maxx) {
                        maxx = x;
                    }
                    if (y < miny) {
                        miny = y;
                    }
                    if (y > maxy) {
                        maxy = y;
                    }
                }
            }
        }
        if (maxx < 0) {
            return null;
        }

        int bw = maxx - minx + 1;
        int bh = maxy - miny + 1;
        boolean[] out = new boolean[N * N];
        for (int j = 0; j < N; j++) {
            int y0 = miny + (int) (j * bh / (double) N);
            int y1 = miny + (int) ((j + 1) * bh / (double) N);
            if (y1 <= y0) {
                y1 = y0 + 1;
            }
            for (int i = 0; i < N; i++) {
                int x0 = minx + (int) (i * bw / (double) N);
                int x1 = minx + (int) ((i + 1) * bw / (double) N);
                if (x1 <= x0) {
                    x1 = x0 + 1;
                }
                int on = 0;
                int tot = 0;
                for (int y = y0; y < y1 && y < h; y++) {
                    for (int x = x0; x < x1 && x < w; x++) {
                        tot++;
                        if (fg[y * w + x]) {
                            on++;
                        }
                    }
                }
                out[j * N + i] = tot > 0 && on * 2 >= tot;
            }
        }
        return out;
    }

    /** Maps a Maps drawable name to our cluster icon id. */
    static int iconFor(String name) {
        String n = name.startsWith("maneuver_") ? name.substring(9) : name;
        boolean right = n.contains("right");
        boolean left = n.contains("left");
        if (n.contains("destination")) {
            return TbtFrame.DEST_REACHED;
        }
        if (n.contains("u_turn")) {
            return right ? TbtFrame.U_TURN_RIGHT : TbtFrame.U_TURN_LEFT;
        }
        if (n.contains("roundabout")) {
            return n.contains("ccw") ? TbtFrame.ROUNDABOUT_LEFT : TbtFrame.ROUNDABOUT_RIGHT;
        }
        if (n.contains("sharp")) {
            return right ? TbtFrame.SHARP_RIGHT : TbtFrame.SHARP_LEFT;
        }
        if (n.contains("slight")) {
            return right ? TbtFrame.SLIGHT_RIGHT : TbtFrame.SLIGHT_LEFT;
        }
        if (n.contains("fork")) {
            return right ? TbtFrame.FORK_RIGHT : TbtFrame.FORK_LEFT;
        }
        if (n.contains("keep")) {
            return right ? TbtFrame.KEEP_RIGHT : TbtFrame.KEEP_LEFT;
        }
        if (n.contains("merge")) {
            return TbtFrame.MERGE;
        }
        if (n.contains("ramp")) {
            return right ? TbtFrame.RAMP_RIGHT : TbtFrame.RAMP_LEFT;
        }
        if (n.contains("turn")) {
            return right ? TbtFrame.TURN_RIGHT : TbtFrame.TURN_LEFT;
        }
        if (left && !right) {
            return TbtFrame.TURN_LEFT;
        }
        return TbtFrame.STRAIGHT;
    }
}
