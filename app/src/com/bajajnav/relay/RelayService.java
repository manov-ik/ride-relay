package com.bajajnav.relay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import java.util.Calendar;

/**
 * Foreground service that keeps the BLE link up and relays the current
 * navigation state to the cluster at ~800 ms, plus a periodic general packet.
 */
public class RelayService extends Service {
    static final String ACTION_START = "com.bajajnav.relay.START";
    static final String ACTION_STOP = "com.bajajnav.relay.STOP";
    static final String PREFS = "relay";
    static final String K_ADDR = "device_address";
    static final String K_NAME = "device_name";
    static final String K_GEN = "general_mode";
    static final String K_RELAY = "relay_enabled";
    static final String K_CALLS = "calls_enabled";
    static final String K_RIDES = "rides_enabled";
    static final String K_NFC = "nfc_connect";

    private static final String CHANNEL = "relay";
    private static final int NOTIF_ID = 42;
    private static final long TBT_PERIOD_MS = 800L;
    private static final long GEN_PERIOD_MS = 5000L;

    private static volatile RelayService INSTANCE;
    static volatile int generalMode = GeneralPacket.MODE_LEGACY55;
    static volatile boolean relayEnabled = true;
    static volatile boolean ridesEnabled = true;

    private HandlerThread thread;
    private Handler h;
    private long lastGenMs = 0;
    private boolean wasActive = false;
    private boolean wasReady = false;
    private volatile boolean fgStarted = false;
    private volatile boolean fgLocation = false;
    private volatile boolean fgLocationBlocked = false;
    private long lastFgAttempt = 0;

    static int getGeneralMode() {
        return generalMode;
    }

    /** Application context for helpers that have no Context of their own. */
    static android.content.Context context() {
        RelayService s = INSTANCE;
        return s == null ? null : s.getApplicationContext();
    }

    /** Ask for a general packet on the next loop iteration, not 5 s from now. */
    static void pushGeneralNow() {
        RelayService s = INSTANCE;
        if (s != null && s.h != null) {
            s.h.post(() -> ClusterLink.get().sendGeneralNow());
        }
    }

    /** The activity is in front again, so the location service type is allowed. */
    static void allowLocationType() {
        RelayService s = INSTANCE;
        if (s != null) {
            s.fgLocationBlocked = false;
        }
    }

    static void kick() {
        RelayService s = INSTANCE;
        if (s != null && s.h != null) {
            s.h.post(s::tick);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        thread = new HandlerThread("relay");
        thread.start();
        h = new Handler(thread.getLooper());

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        generalMode = p.getInt(K_GEN, GeneralPacket.MODE_LEGACY55);
        relayEnabled = p.getBoolean(K_RELAY, true);
        ridesEnabled = p.getBoolean(K_RIDES, true);

        ClusterLink.get().init(this);
        ManeuverMatcher.prime(this);
        PhoneBridge.get().load(this);
        readBatteryAndVolume();
        String addr = p.getString(K_ADDR, null);
        if (addr != null) {
            if (ClusterLink.get().hasGivenUp()) {
                // The link already stopped retrying in this process; a service
                // restart must not quietly undo that. Only a manual Connect does.
                Lg.i("auto-connect still stopped — tap Connect to retry");
            } else {
                ClusterLink.get().connect(addr, p.getString(K_NAME, addr));
            }
        } else {
            Lg.w("no saved bike — open the app and connect first");
        }

        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                tick();
                h.postDelayed(this, TBT_PERIOD_MS);
            }
        }, TBT_PERIOD_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void tick() {
        try {
            if ((!fgStarted || (!fgLocation && !fgLocationBlocked
                    && TripRecorder.hasPermission(this)))
                    && System.currentTimeMillis() - lastFgAttempt > 2000) {
                // Permissions may not have been granted when the service first
                // started — retry (throttled, so a denial does not spin), and add
                // the location type once the rider has allowed ride logging.
                lastFgAttempt = System.currentTimeMillis();
                startForegroundCompat();
            }
            boolean ready = ClusterLink.get().isReady();
            boolean active = NavState.active;
            if (ready != wasReady) {
                wasReady = ready;
                if (ready) {
                    TripRecorder.onLinkUp();
                } else {
                    TripRecorder.onLinkDown();
                }
            }
            if (active && !wasActive) {
                Lg.i("navigation start -> relaying");
            }
            if (!active && wasActive) {
                Lg.i("navigation stopped -> sending stop frame");
                ClusterLink.get().sendTbt(TbtFrame.stop());
            }
            wasActive = active;

            if (!ready) {
                return;
            }
            if (relayEnabled && active) {
                ClusterLink.get().sendTbt(buildCurrentFrame());
            }
            long now = System.currentTimeMillis();
            if (generalMode != GeneralPacket.MODE_OFF && now - lastGenMs >= GEN_PERIOD_MS) {
                lastGenMs = now;
                readBatteryAndVolume();
                ClusterLink.get().sendGeneral(GeneralPacket.build(generalMode));
            }
        } catch (Exception e) {
            Lg.w("tick error: " + e);
        }
    }

    private byte[] buildCurrentFrame() {
        int step = NavState.stepMeters;
        int total = NavState.totalMeters;
        boolean isAm;
        int hour;
        int minute;
        if (NavState.etaHour > 0) {
            // Use the arrival clock Maps already computed for us.
            hour = NavState.etaHour;
            minute = NavState.etaMinute;
            isAm = NavState.etaIsAm;
        } else {
            int eta = NavState.etaSeconds;
            Calendar c = Calendar.getInstance();
            if (eta > 0) {
                c.add(Calendar.SECOND, eta);
            }
            isAm = c.get(Calendar.AM_PM) == Calendar.AM;
            hour = c.get(Calendar.HOUR);
            if (hour == 0) {
                hour = 12;
            }
            minute = c.get(Calendar.MINUTE);
        }
        return TbtFrame.build(1, NavState.maneuver, isAm, step, total, hour, minute, 0, NavState.street);
    }

    /**
     * Battery goes to the cluster as five bars, and the volume nibble mirrors the
     * phone's media volume so the cluster's volume keys and ours never disagree.
     */
    private void readBatteryAndVolume() {
        try {
            android.os.BatteryManager bm =
                    (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
            if (bm != null) {
                int pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if (pct >= 0) {
                    if (pct < 20) {
                        GeneralPacket.battery = 0;
                    } else if (pct < 40) {
                        GeneralPacket.battery = 1;
                    } else if (pct < 60) {
                        GeneralPacket.battery = 2;
                    } else if (pct < 80) {
                        GeneralPacket.battery = 3;
                    } else {
                        GeneralPacket.battery = 4;
                    }
                }
            }
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                int idx = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                if (max > 0) {
                    GeneralPacket.volume = Math.round(idx * 15f / max);
                }
            }
        } catch (Exception e) {
            Lg.w("battery/volume read failed: " + e);
        }
    }

    private void startForegroundCompat() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Navigation relay",
                        NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);
        b.setContentTitle(getString(R.string.app_name))
                .setContentText("Relaying navigation to your bike")
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setOngoing(true)
                .setContentIntent(pi);
        Notification n = b.build();
        int cd = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        int loc = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        boolean wantsLocation = !fgLocationBlocked
                && android.content.pm.PackageManager.PERMISSION_GRANTED
                == checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                int type = wantsLocation ? (cd | loc) : cd;
                startForeground(NOTIF_ID, n, type);
                fgLocation = (type & loc) != 0;
            } else {
                startForeground(NOTIF_ID, n);
            }
            fgStarted = true;
        } catch (Exception e) {
            Lg.w("startForeground(typed) failed: " + e);
            if (wantsLocation && Build.VERSION.SDK_INT >= 29) {
                // Android 14 refuses the location type when the service comes
                // back in the background. Stay alive as a connected-device
                // service and try the location type again next time the app is
                // opened, rather than losing the bike link.
                fgLocationBlocked = true;
                try {
                    startForeground(NOTIF_ID, n, cd);
                    fgLocation = false;
                    fgStarted = true;
                    return;
                } catch (Exception e2) {
                    Lg.w("startForeground(connected) failed: " + e2);
                }
            }
            try {
                startForeground(NOTIF_ID, n);
                fgStarted = true;
            } catch (Exception e2) {
                Lg.w("startForeground failed: " + e2);
            }
        }
    }

    @Override
    public void onDestroy() {
        TripRecorder.onLinkDown();
        INSTANCE = null;
        if (thread != null) {
            thread.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
