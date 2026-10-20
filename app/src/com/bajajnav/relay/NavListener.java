package com.bajajnav.relay;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * The one notification listener. It feeds Maps navigation into NavState and
 * dialer notifications into PhoneBridge, so a caller's name can reach the
 * cluster.
 *
 * Having a single listener matters: Android allows one component to hold the
 * notification-access grant per app, and the phone's dialer already relies on
 * this grant for caller names.
 */
public class NavListener extends NotificationListenerService {

    private static volatile boolean connected = false;

    static boolean isConnected() {
        return connected;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ManeuverMatcher.prime(this);
        PhoneBridge.get().attach(this);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        connected = true;
        PhoneBridge.get().attach(this);
        Lg.i("notification listener connected");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        connected = false;
        Lg.w("notification listener disconnected");
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            requestRebind(new android.content.ComponentName(this, NavListener.class));
        }
    }

    @Override
    public void onDestroy() {
        PhoneBridge.get().detach(this);
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        // The dialer first: it is cheap and must not be skipped if the Maps
        // parser below throws on an odd notification.
        PhoneBridge.get().onNotification(sbn);

        if (!NavParser.GMAPS.equals(sbn.getPackageName())) {
            return;
        }
        Notification n = sbn.getNotification();
        try {
            boolean nav = NavParser.isNavigation(n);
            Lg.i("maps notif id=" + sbn.getId() + " tag=" + sbn.getTag()
                    + " cat=" + (n == null ? "?" : n.category)
                    + " flags=0x" + (n == null ? "?" : Integer.toHexString(n.flags))
                    + " nav=" + nav);
            if (!nav) {
                return;
            }
            NavParser.dumpExtras(n);
            if (NavParser.parse(n)) {
                RelayService.kick();
            }
        } catch (Exception e) {
            Lg.w("maps parse error: " + e);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || !NavParser.GMAPS.equals(sbn.getPackageName())) {
            return;
        }
        Notification n = sbn.getNotification();
        if (NavParser.isNavigation(n)) {
            Lg.i("maps navigation notification removed");
            NavState.clear();
            RelayService.kick();
        }
    }
}
