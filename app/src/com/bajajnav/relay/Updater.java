package com.bajajnav.relay;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small, on-demand updater for GitHub Releases. No third-party library: it uses
 * HttpsURLConnection, the framework JSON parser and PackageInstaller.
 *
 * The app checks at most once a day when it is opened (the switch in Advanced
 * turns that off) and via the button in Advanced. Android always shows the
 * system install confirmation; nothing is installed silently.
 */
final class Updater {
    static final String K_LAST_CHECK = "update_last_check";
    static final String K_VERSION = "update_version";
    static final String K_URL = "update_url";
    static final String K_SHA = "update_sha";
    static final String K_SIZE = "update_size";
    static final String K_NOTIFIED = "update_notified";
    static final String K_AUTO = "update_auto";

    private static final String API =
            "https://api.github.com/repos/manov-ik/ride-relay/releases/latest";
    private static final String CHANNEL = "updates";
    private static final int NOTIF_ID = 43;
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean CHECKING = new AtomicBoolean(false);

    interface CheckCallback {
        void onResult(boolean ok, boolean available, String message);
    }

    interface InstallCallback {
        void onProgress(int percent);

        void onResult(boolean ok, String message);
    }

    private Updater() {
    }

    // ------------------------------------------------------------------ public

    static String currentVersion(Context c) {
        try {
            return c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** The newer version stored by the last successful check, or "" when none. */
    static String availableVersion(Context c) {
        String v = prefs(c).getString(K_VERSION, "");
        if (v.isEmpty()) {
            return "";
        }
        return compareVersions(v, currentVersion(c)) > 0 ? v : "";
    }

    static long lastCheck(Context c) {
        return prefs(c).getLong(K_LAST_CHECK, 0L);
    }

    static String lastCheckLabel(Context c) {
        long t = lastCheck(c);
        if (t == 0L) {
            return "Not checked yet";
        }
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(t));
    }

    static String availableSize(Context c) {
        long size = prefs(c).getLong(K_SIZE, 0L);
        if (size <= 0L) {
            return "";
        }
        return Math.max(1L, Math.round(size / 1024.0)) + " KB";
    }

    /**
     * Daily check used when the main screen is opened. It never shows a spinner
     * and fails quietly; the result is visible in Advanced.
     */
    static void autoCheck(Context c) {
        final Context app = c.getApplicationContext();
        SharedPreferences p = prefs(app);
        if (!p.getBoolean(K_AUTO, true)) {
            return;
        }
        long last = p.getLong(K_LAST_CHECK, 0L);
        if (last != 0L && System.currentTimeMillis() - last < DAY_MS) {
            return;
        }
        check(app, false, null);
    }

    /**
     * Re-post the "update available" notification for a version found by an
     * earlier check. Cheap and network-free: it matters on the first launch
     * after install, when the notification permission prompt may still have
     * been open while the daily check finished.
     */
    static void notifyIfAvailable(Context c) {
        String version = availableVersion(c);
        if (!version.isEmpty()) {
            notifyUpdate(c.getApplicationContext(), version);
        }
    }

    static void check(final Context context, final boolean manual, final CheckCallback cb) {
        if (!CHECKING.compareAndSet(false, true)) {
            postCheck(cb, false, false, "A check is already running");
            return;
        }
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            boolean ok = false;
            boolean available = false;
            String message;
            try {
                JSONObject release = getJson(API);
                JSONArray assets = release.optJSONArray("assets");
                JSONObject apk = null;
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.optJSONObject(i);
                        if (a == null) {
                            continue;
                        }
                        String name = a.optString("name", "");
                        if (name.toLowerCase(Locale.US).endsWith(".apk")) {
                            apk = a;
                            break;
                        }
                    }
                }
                if (apk == null) {
                    throw new IOException("no APK in the latest release");
                }

                String tag = cleanVersion(release.optString("tag_name", ""));
                String url = apk.optString("browser_download_url", "");
                if (tag.isEmpty() || url.isEmpty()) {
                    throw new IOException("release metadata is incomplete");
                }
                long size = apk.optLong("size", 0L);
                String digest = apk.optString("digest", "");
                String sha = digest.startsWith("sha256:")
                        ? digest.substring("sha256:".length()) : "";

                available = compareVersions(tag, currentVersion(app)) > 0;
                SharedPreferences.Editor e = prefs(app).edit()
                        .putLong(K_LAST_CHECK, System.currentTimeMillis());
                if (available) {
                    e.putString(K_VERSION, tag)
                            .putString(K_URL, url)
                            .putString(K_SHA, sha)
                            .putLong(K_SIZE, size);
                } else {
                    e.remove(K_VERSION).remove(K_URL).remove(K_SHA)
                            .remove(K_SIZE).remove(K_NOTIFIED);
                }
                e.apply();

                if (available) {
                    notifyUpdate(app, tag);
                    message = "Version " + tag + " is available";
                } else {
                    message = "Ride Relay is up to date";
                }
                ok = true;
            } catch (Exception ex) {
                message = "Could not check for updates — " + shortError(ex);
                // Do not stamp K_LAST_CHECK here: a failed check (offline, GitHub
                // hiccup) must not use up the once-a-day budget, and Advanced
                // shows the last successful check.
            } finally {
                CHECKING.set(false);
            }
            postCheck(cb, ok, available, message);
            if (!manual && ok && !available) {
                // Quiet success; the Advanced screen shows the timestamp.
            }
        }, "update-check").start();
    }

    /**
     * Downloads the APK stored by the last check (if needed), verifies it and
     * opens the system installer. Must be called with an Activity so the
     * "install unknown apps" screen can be opened when needed.
     */
    static void downloadAndInstall(final Activity activity, final InstallCallback cb) {
        final SharedPreferences p = prefs(activity);
        final String version = p.getString(K_VERSION, "");
        final String url = p.getString(K_URL, "");
        final String sha = p.getString(K_SHA, "");
        final long size = p.getLong(K_SIZE, 0L);
        if (version.isEmpty() || url.isEmpty()) {
            postInstall(cb, false, "Check for updates first");
            return;
        }

        final File dest = new File(activity.getCacheDir(),
                "ride-relay-" + version + ".apk");
        if (isGoodFile(dest, size, sha)) {
            install(activity, dest, cb);
            return;
        }

        new Thread(() -> {
            try {
                download(url, dest, size, sha, cb);
                install(activity, dest, cb);
            } catch (Exception ex) {
                if (dest.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    dest.delete();
                }
                postInstall(cb, false, "Download failed — " + shortError(ex));
            }
        }, "update-download").start();
    }

    // ------------------------------------------------------------------ check

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "Ride-Relay-Android");
        try {
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(in);
            if (code != 200) {
                throw new IOException("GitHub HTTP " + code);
            }
            return new JSONObject(body);
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, "UTF-8"))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    private static void notifyUpdate(Context c, String version) {
        try {
            SharedPreferences p = prefs(c);
            if (version.equals(p.getString(K_NOTIFIED, ""))) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 33
                    && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            NotificationManager nm = (NotificationManager)
                    c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL,
                        "App updates", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("A new Ride Relay release is available");
                nm.createNotificationChannel(ch);
            }

            Intent open = new Intent(c, AdvancedActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, NOTIF_ID, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(c, CHANNEL)
                    : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Ride Relay " + version + " is available")
                    .setContentText("Tap to review and install the update.")
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            if (Build.VERSION.SDK_INT < 26) {
                b.setPriority(Notification.PRIORITY_LOW);
            }
            nm.notify(NOTIF_ID, b.build());
            p.edit().putString(K_NOTIFIED, version).apply();
        } catch (Exception ignored) {
            // A missing notification must never break the update check.
        }
    }

    // --------------------------------------------------------------- download

    private static void download(String url, File dest, long expected,
                                 String sha, InstallCallback cb) throws Exception {
        File tmp = new File(dest.getPath() + ".part");
        if (tmp.exists()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Ride-Relay-Android");
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            long total = conn.getContentLengthLong();
            if (total <= 0) {
                total = expected;
            }
            long done = 0L;
            int lastPct = -1;
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int pct = (int) Math.min(100L, done * 100L / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            postProgress(cb, pct);
                        }
                    }
                }
            }
            if (expected > 0 && tmp.length() != expected) {
                throw new IOException("size mismatch");
            }
            if (!sha.isEmpty()) {
                String got = sha256(tmp);
                if (!sha.equalsIgnoreCase(got)) {
                    throw new IOException("checksum mismatch");
                }
            }
            if (!tmp.renameTo(dest)) {
                throw new IOException("could not store the APK");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static boolean isGoodFile(File f, long size, String sha) {
        if (!f.isFile()) {
            return false;
        }
        if (size > 0 && f.length() != size) {
            return false;
        }
        if (!sha.isEmpty()) {
            try {
                return sha.equalsIgnoreCase(sha256(f));
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- install

    private static void install(final Activity activity, final File apk,
                                final InstallCallback cb) {
        activity.runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= 26
                    && !activity.getPackageManager().canRequestPackageInstalls()) {
                postInstall(cb, false,
                        "Allow installs from Ride Relay, then tap Install again");
                openUnknownSources(activity);
                return;
            }
            PackageInstaller installer = activity.getPackageManager()
                    .getPackageInstaller();
            int id = -1;
            try {
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(
                                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(activity.getPackageName());
                id = installer.createSession(params);

                try (PackageInstaller.Session session = installer.openSession(id)) {
                    try (OutputStream out = session.openWrite("base.apk", 0,
                            apk.length());
                         InputStream in = new BufferedInputStream(
                                 new FileInputStream(apk))) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                        out.flush();
                        session.fsync(out);
                    }
                    Intent result = new Intent(activity, InstallResultReceiver.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) {
                        flags |= PendingIntent.FLAG_MUTABLE;
                    }
                    PendingIntent pi = PendingIntent.getBroadcast(
                            activity, id, result, flags);
                    session.commit(pi.getIntentSender());
                }
                postInstall(cb, true,
                        "Confirm the install in the system dialog");
            } catch (Exception ex) {
                if (id != -1) {
                    try {
                        installer.abandonSession(id);
                    } catch (Exception ignored) {
                    }
                }
                postInstall(cb, false, "Could not start the installer — "
                        + shortError(ex));
            }
        });
    }

    private static void openUnknownSources(Activity a) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName()));
            a.startActivity(i);
        } catch (Exception e) {
            try {
                a.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(RelayService.PREFS, Context.MODE_PRIVATE);
    }

    private static void postCheck(CheckCallback cb, boolean ok,
                                  boolean available, String message) {
        if (cb == null) {
            return;
        }
        MAIN.post(() -> cb.onResult(ok, available, message));
    }

    private static void postInstall(InstallCallback cb, boolean ok, String message) {
        if (cb == null) {
            return;
        }
        MAIN.post(() -> cb.onResult(ok, message));
    }

    private static void postProgress(InstallCallback cb, int pct) {
        if (cb == null) {
            return;
        }
        MAIN.post(() -> cb.onProgress(pct));
    }

    private static String cleanVersion(String tag) {
        String v = tag == null ? "" : tag.trim();
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1);
        }
        return v;
    }

    /** Numeric, dot-separated compare; "1.10" is newer than "1.9". */
    static int compareVersions(String left, String right) {
        int[] a = versionParts(left);
        int[] b = versionParts(right);
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        return 0;
    }

    private static int[] versionParts(String v) {
        String s = cleanVersion(v);
        String[] parts = s.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            int end = 0;
            while (end < parts[i].length()
                    && Character.isDigit(parts[i].charAt(end))) {
                end++;
            }
            try {
                out[i] = end == 0 ? 0 : Integer.parseInt(parts[i].substring(0, end));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static String shortError(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) {
            m = e.getClass().getSimpleName();
        }
        return m.length() > 120 ? m.substring(0, 120) : m;
    }
}
