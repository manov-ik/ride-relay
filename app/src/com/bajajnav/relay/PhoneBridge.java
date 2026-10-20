package com.bajajnav.relay;

import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Everything the cluster knows about the phone that is not navigation: who is
 * calling, and how many calls were missed.
 *
 * This is a port of the official app's call bookkeeping in
 * {@code com.bajajconnect.ble.BleService} and {@code enums.CallState}. The
 * cluster's text field is 32 bytes, so the caller line is all that is sent.
 */
final class PhoneBridge {
    // CallState values, from com.bajajconnect.enums.CallState.
    static final int CALL_NONE = 0;
    static final int CALL_INCOMING = 1;
    static final int CALL_OUTGOING = 2;
    static final int CALL_ACTIVE = 3;
    static final int CALL_ENDED = 4;

    /** Set of package names that mean "this is a phone call", not a notification. */
    private static final Set<String> DIALER_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.server.telecom",
            "com.android.phone",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui",
            "com.google.android.apps.dialer"));

    private static final PhoneBridge INSTANCE = new PhoneBridge();

    private final Handler ui = new Handler(Looper.getMainLooper());

    /** User switch, mirrored from the Advanced screen. */
    volatile boolean callsEnabled = true;

    private volatile int callState = CALL_NONE;
    private volatile String callerName = "";
    private volatile String callerNumber = "";

    /** Missed calls waiting on the cluster. */
    private int missedCount = 0;

    /** Last cluster request we acted on, echoed back as the ack byte. */
    private int missedCallGetAck = 0;
    private int callAcceptAck = 0;
    private int callRejectAck = 0;
    private int callRejectSmsAck = 0;
    private int callProgress = 0;

    /** Kept so the cluster can re-pull the last missed call when the rider asks. */
    private byte[] lastMissedCall;

    private Context ctx;
    private NavListener listener;
    private boolean watchingCalls = false;
    private boolean warnedPhonePermission = false;

    private final PhoneStateListener telephony = new PhoneStateListener() {
        @Override
        public void onCallStateChanged(int state, String number) {
            onTelephony(state);
        }
    };

    private PhoneBridge() {
    }

    static PhoneBridge get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ lifecycle

    synchronized void load(Context context) {
        ctx = context.getApplicationContext();
        SharedPreferences p = ctx.getSharedPreferences(RelayService.PREFS, Context.MODE_PRIVATE);
        callsEnabled = p.getBoolean(RelayService.K_CALLS, true);
        startCallWatch();
    }

    synchronized void attach(NavListener svc) {
        listener = svc;
        if (ctx == null) {
            ctx = svc.getApplicationContext();
        }
        startCallWatch();
    }

    synchronized void detach(NavListener svc) {
        if (listener == svc) {
            listener = null;
        }
    }

    private void startCallWatch() {
        if (!callsEnabled || watchingCalls || ctx == null) {
            return;
        }
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!warnedPhonePermission) {
                warnedPhonePermission = true;
                Lg.w("call watch needs phone-state permission (Advanced → Calls)");
            }
            return;
        }
        TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) {
            return;
        }
        try {
            tm.listen(telephony, PhoneStateListener.LISTEN_CALL_STATE);
            watchingCalls = true;
            warnedPhonePermission = false;
            Lg.i("call watch on");
        } catch (Exception e) {
            Lg.w("call watch unavailable: " + e);
        }
    }

    void setCallsEnabled(boolean on) {
        callsEnabled = on;
        if (on) {
            startCallWatch();
        } else {
            stopCallWatch();
            if (callState != CALL_NONE) {
                callState = CALL_NONE;
                callerName = "";
                callerNumber = "";
                pushGeneral();
            }
        }
    }

    private void stopCallWatch() {
        if (!watchingCalls || ctx == null) {
            return;
        }
        TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null) {
            try {
                tm.listen(telephony, PhoneStateListener.LISTEN_NONE);
            } catch (Exception e) {
                Lg.w("call watch off failed: " + e);
            }
        }
        watchingCalls = false;
    }

    // ---------------------------------------------------------------- telephony

    private void onTelephony(int state) {
        if (!callsEnabled) {
            return;
        }
        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                if (callState != CALL_INCOMING) {
                    callerNumber = "";
                    callerName = dialerIdentity();
                    Lg.i("call INCOMING \"" + callerName + "\"");
                    setCall(CALL_INCOMING);
                }
                break;
            case TelephonyManager.CALL_STATE_OFFHOOK:
                if (callState == CALL_INCOMING) {
                    Lg.i("call answered");
                    setCall(CALL_ACTIVE);
                } else if (callState != CALL_ACTIVE) {
                    String who = dialerIdentity();
                    if (!who.isEmpty()) {
                        callerName = who;
                    }
                    Lg.i("call OUTGOING \"" + callerName + "\"");
                    setCall(CALL_OUTGOING);
                }
                break;
            case TelephonyManager.CALL_STATE_IDLE:
            default:
                if (callState == CALL_INCOMING) {
                    Lg.i("call missed \"" + callerName + "\"");
                    missedCall(callerName, callerNumber);
                }
                if (callState != CALL_NONE) {
                    setCall(CALL_ENDED);
                    ui.removeCallbacks(clearCall);
                    ui.postDelayed(clearCall, 2000L);
                }
                break;
        }
    }

    private final Runnable clearCall = () -> setCall(CALL_NONE);

    private void setCall(int state) {
        if (callState == state) {
            return;
        }
        callState = state;
        Lg.i("call state -> " + stateName(state));
        if (state == CALL_NONE || state == CALL_ENDED) {
            callerName = "";
            callerNumber = "";
            callProgress = 0;
        }
        pushGeneral();
    }

    /** The name on the dialer's own notification — no call-log permission needed. */
    private String dialerIdentity() {
        NavListener svc = listener;
        if (svc == null) {
            return "";
        }
        try {
            StatusBarNotification[] active = svc.getActiveNotifications();
            if (active == null) {
                return "";
            }
            for (StatusBarNotification sbn : active) {
                if (sbn == null || sbn.getNotification() == null) {
                    continue;
                }
                if (!isDialerPackage(sbn.getPackageName())) {
                    continue;
                }
                String id = extractCallerIdentity(sbn.getNotification());
                if (!id.isEmpty()) {
                    return id;
                }
            }
        } catch (Exception e) {
            Lg.w("dialer identity failed: " + e);
        }
        return "";
    }

    // ------------------------------------------------------------ notifications

    /** Called for every posted notification, so a late caller name can land. */
    void onNotification(StatusBarNotification sbn) {
        try {
            Notification n = sbn.getNotification();
            if (n == null) {
                return;
            }
            if (isDialerPackage(sbn.getPackageName())) {
                dialerNotification(n);
            }
        } catch (Exception e) {
            Lg.w("phone notification failed: " + e);
        }
    }

    private void dialerNotification(Notification n) {
        if (!callsEnabled) {
            return;
        }
        // The dialer often posts the contact name a moment after the call starts;
        // upgrade the cluster's caller line once we have something better.
        if (callState != CALL_INCOMING && callState != CALL_OUTGOING && callState != CALL_ACTIVE) {
            return;
        }
        String id = extractCallerIdentity(n);
        if (id.isEmpty() || id.equals(callerName)) {
            return;
        }
        String lower = id.toLowerCase();
        if (lower.equals("incoming") || lower.equals("outgoing") || lower.equals("dialing")
                || lower.equals("calling")) {
            return;
        }
        callerName = id;
        pushGeneral();
    }

    private void missedCall(String name, String number) {
        String who = Pack.clean(name);
        String num = Pack.clean(number);
        if (num.isEmpty() && looksLikeNumber(who)) {
            num = who;
        }
        byte[] packet = Alerts.missedCall(who, num, Pack.nowSeconds());
        lastMissedCall = packet;
        missedCount = (missedCount + 1) & 0xFF;
        Lg.i("missed call \"" + who + "\" count=" + missedCount);
        ClusterLink.get().sendMissedCall(packet);
        pushGeneral();
    }

    // ---------------------------------------------------------- cluster actions

    /**
     * The rider pressed "answer" on the cluster. Needs ANSWER_PHONE_CALLS, which
     * the Advanced screen asks for when calls are switched on.
     */
    void acceptCall() {
        if (ctx == null) {
            return;
        }
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            android.telecom.TelecomManager tc =
                    (android.telecom.TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 26 && tc != null) {
                tc.acceptRingingCall();
                Lg.i("cluster: call accepted");
                return;
            }
            if (tm != null) {
                Lg.w("cluster: accept unsupported on API " + android.os.Build.VERSION.SDK_INT);
            }
        } catch (Exception e) {
            Lg.w("cluster accept failed: " + e);
        }
    }

    /** The rider pressed "decline" (or "decline with message") on the cluster. */
    void endCall() {
        if (ctx == null) {
            return;
        }
        try {
            android.telecom.TelecomManager tc =
                    (android.telecom.TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 28 && tc != null
                    && android.content.pm.PackageManager.PERMISSION_GRANTED
                    == ctx.checkSelfPermission("android.permission.ANSWER_PHONE_CALLS")) {
                tc.endCall();
                Lg.i("cluster: call ended");
            } else {
                Lg.w("cluster: endCall needs ANSWER_PHONE_CALLS");
            }
        } catch (Exception e) {
            Lg.w("cluster end call failed: " + e);
        }
    }

    void onMissedCallRequested(int request) {
        missedCallGetAck = request;
        if (lastMissedCall != null) {
            Lg.i("cluster: re-sending last missed call");
            ClusterLink.get().sendMissedCall(lastMissedCall);
        }
    }

    void onAcceptRequested(int request) {
        callAcceptAck = request;
        acceptCall();
    }

    void onRejectRequested(int request) {
        callRejectAck = request;
        endCall();
    }

    void onRejectWithSmsRequested(int request) {
        callRejectSmsAck = request;
        // Sending the canned text needs SEND_SMS; we just end the call, which is
        // what the rider asked for either way.
        endCall();
    }

    // ------------------------------------------------------------------ getters

    int callState() {
        return callState;
    }

    String callerName() {
        return callerName;
    }

    int missedCount() {
        return missedCount;
    }

    int missedCallGetAck() {
        return missedCallGetAck;
    }

    int callAcceptAck() {
        return callAcceptAck;
    }

    int callRejectAck() {
        return callRejectAck;
    }

    int callRejectSmsAck() {
        return callRejectSmsAck;
    }

    /** Counts general packets while a call is up; the cluster uses it to refresh. */
    int nextCallProgress() {
        if (callState == CALL_NONE || callState == CALL_ENDED) {
            callProgress = 0;
        } else {
            callProgress = (callProgress + 1) & 0xFFFF;
        }
        return callProgress;
    }

    private static void pushGeneral() {
        RelayService.pushGeneralNow();
    }

    // -------------------------------------------------------------- text helpers

    private static String text(Notification n, String key) {
        if (n == null || n.extras == null) {
            return "";
        }
        CharSequence cs = n.extras.getCharSequence(key);
        return cs == null ? "" : cs.toString().trim();
    }

    private static boolean isDialerPackage(String pkg) {
        if (pkg == null) {
            return false;
        }
        if (DIALER_PACKAGES.contains(pkg)) {
            return true;
        }
        String lower = pkg.toLowerCase();
        return lower.contains("dialer") || lower.contains("incallui") || lower.contains("telecom");
    }

    private static boolean looksLikeNumber(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        String digits = s.replaceAll("[^0-9]", "");
        return digits.length() >= 7 && digits.length() >= s.length() - 2;
    }

    /** Port of MyNotificationService.extractCallerIdentity. */
    private static String extractCallerIdentity(Notification n) {
        if (n == null || n.extras == null) {
            return "";
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                ArrayList<android.app.Person> people =
                        n.extras.getParcelableArrayList("android.people.list");
                if (people != null) {
                    for (android.app.Person p : people) {
                        if (p != null && p.getName() != null) {
                            String name = p.getName().toString().trim();
                            if (looksLikeCallerIdentity(name)) {
                                return name;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // some dialers put non-Person data in that key
            }
        }
        String[] keys = {"android.title", "android.text", "android.bigText",
                "android.subText", "android.infoText"};
        for (String key : keys) {
            String v = text(n, key);
            if (looksLikeCallerIdentity(v)) {
                return v;
            }
        }
        return "";
    }

    private static boolean looksLikeCallerIdentity(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return false;
        }
        String lower = t.toLowerCase();
        if (lower.contains("calling") || lower.contains("dialing") || lower.contains("dialling")
                || lower.contains("ringing") || lower.contains("connecting")
                || lower.contains("incoming call") || lower.contains("outgoing call")
                || lower.equals("incoming") || lower.equals("outgoing")) {
            return false;
        }
        if (t.matches("^\\d{1,2}:\\d{2}(:\\d{2})?$")) {
            return false;
        }
        return !lower.equals("call") && !lower.equals("phone") && !lower.equals("mobile")
                && !lower.equals("ongoing call") && !lower.equals("in call")
                && !lower.equals("in-call") && !lower.startsWith("call with");
    }

    /** Diagnostics for the Advanced screen. */
    String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("state=").append(callState);
        if (!callerName.isEmpty()) {
            sb.append(" name=\"").append(callerName).append('"');
        }
        sb.append(" missed=").append(missedCount);
        return sb.toString();
    }

    private static String stateName(int state) {
        switch (state) {
            case CALL_INCOMING:
                return "incoming";
            case CALL_OUTGOING:
                return "outgoing";
            case CALL_ACTIVE:
                return "active";
            case CALL_ENDED:
                return "ended";
            default:
                return "none";
        }
    }
}
