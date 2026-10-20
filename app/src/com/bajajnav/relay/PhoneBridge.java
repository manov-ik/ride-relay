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
 * calling, and how many text/WhatsApp alerts and missed calls are waiting.
 *
 * This is a port of the official app's split across
 * {@code com.bajajconnect.MyNotificationService} (which notifications count),
 * {@code BleService} (call bookkeeping) and {@code enums.CallState}.
 *
 * The cluster's text field is 32 bytes, so the alert only ever carries the
 * sender — the message body stays on the phone.
 */
final class PhoneBridge {
    // CallState values, from com.bajajconnect.enums.CallState.
    static final int CALL_NONE = 0;
    static final int CALL_INCOMING = 1;
    static final int CALL_OUTGOING = 2;
    static final int CALL_ACTIVE = 3;
    static final int CALL_ENDED = 4;

    /** The two alert types the cluster understands (enums.Alert). */
    private static final int ALERT_SMS = 1;
    private static final int ALERT_WHATSAPP = 2;

    private static final Set<String> SMS_PACKAGES = new HashSet<>(Arrays.asList(
            "com.samsung.android.messaging",
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.android.messaging"));

    private static final String WHATSAPP = "com.whatsapp";
    private static final String WHATSAPP_BUSINESS = "com.whatsapp.w4b";
    private static final String WHATSAPP_VOIP_CHANNEL = "voip_notification";

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

    /** User switches, mirrored from the Advanced screen. */
    volatile boolean notificationsEnabled = true;
    volatile boolean callsEnabled = true;

    private volatile int callState = CALL_NONE;
    private volatile String callerName = "";
    private volatile String callerNumber = "";

    /** Counters the general packet reports. */
    private int alertCount = 0;
    private int missedCount = 0;

    /** Last cluster request we acted on, echoed back as the ack byte. */
    private int alertGetAck = 0;
    private int missedCallGetAck = 0;
    private int callAcceptAck = 0;
    private int callRejectAck = 0;
    private int callRejectSmsAck = 0;
    private int callProgress = 0;

    /** Kept so the cluster can re-pull the last body when the rider opens the list. */
    private byte[] lastAlert;
    private byte[] lastMissedCall;

    /** Dedup state, same purpose as the official app's GlobalVar.last* keys. */
    private String lastSmsKey = "";
    private String lastWaSender = "";
    private String lastWaText = "";

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
        notificationsEnabled = p.getBoolean(RelayService.K_NOTIF, true);
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

    /** Called for every posted notification, before the Maps parser sees it. */
    void onNotification(StatusBarNotification sbn) {
        try {
            Notification n = sbn.getNotification();
            if (n == null) {
                return;
            }
            String pkg = sbn.getPackageName();
            if (isDialerPackage(pkg)) {
                dialerNotification(n);
                return;
            }
            if (!notificationsEnabled) {
                return;
            }
            if (WHATSAPP.equals(pkg) || WHATSAPP_BUSINESS.equals(pkg)) {
                whatsapp(n);
            } else if (SMS_PACKAGES.contains(pkg)) {
                sms(n);
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

    private void sms(Notification n) {
        String title = text(n, "android.title");
        String body = text(n, "android.text");
        if (title.isEmpty() || isGenericSmsTitle(title)) {
            return;
        }
        String sender = normalizeAlertIdentity(title);
        String key = "sms_" + sender + "_" + body;
        if (key.equals(lastSmsKey)) {
            return;
        }
        lastSmsKey = key;
        alert(ALERT_SMS, sender);
    }

    private void whatsapp(Notification n) {
        if (isWhatsAppCall(n)) {
            return; // a WhatsApp voice call is handled by the call path
        }
        String title = text(n, "android.title");
        String body = text(n, "android.text");
        if ("WhatsApp".equals(title)) {
            return;
        }
        if (isMissedCallBoilerplate(title) || isMissedCallBoilerplate(body)
                || isMissedCallBoilerplate(text(n, "android.bigText"))
                || isMissedCallBoilerplate(text(n, "android.subText"))
                || isMissedCallBoilerplate(text(n, "android.infoText"))) {
            return;
        }
        if (title.equals(lastWaSender) && body.equals(lastWaText)) {
            return;
        }
        lastWaSender = title;
        lastWaText = body;
        alert(ALERT_WHATSAPP, normalizeAlertIdentity(title));
    }

    private void alert(int type, String sender) {
        byte[] packet = Alerts.alert(type, sender, Pack.nowSeconds());
        lastAlert = packet;
        alertCount = (alertCount + 1) & 0xFF;
        Lg.i("alert type=" + type + " from=\"" + sender + "\" count=" + alertCount);
        ClusterLink.get().sendAlert(packet);
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

    /** Cluster changed its "show me the alert" byte — re-send the body. */
    void onAlertRequested(int request) {
        alertGetAck = request;
        if (lastAlert != null) {
            Lg.i("cluster: re-sending last alert");
            ClusterLink.get().sendAlert(lastAlert);
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

    int alertCount() {
        return alertCount;
    }

    int missedCount() {
        return missedCount;
    }

    int alertGetAck() {
        return alertGetAck;
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

    private static boolean isWhatsAppCall(Notification n) {
        if (n == null) {
            return false;
        }
        if (Notification.CATEGORY_CALL.equals(n.category)) {
            return true;
        }
        String channel = android.os.Build.VERSION.SDK_INT >= 26 ? n.getChannelId() : null;
        return channel != null && channel.startsWith(WHATSAPP_VOIP_CHANNEL);
    }

    /**
     * "Missed call", "Missed video call · tap to call back" and friends — WhatsApp
     * posts these as ordinary notifications and they are not worth an alert.
     */
    private static boolean isMissedCallBoilerplate(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        String s = raw.trim().toLowerCase().replaceAll("^[^\\p{L}\\p{N}]+", "").trim();
        if (s.isEmpty()) {
            return false;
        }
        switch (s) {
            case "missed call":
            case "missed voice call":
            case "missed video call":
            case "missed group voice call":
            case "missed group video call":
            case "missed voice call · tap to call back":
            case "missed video call · tap to call back":
                return true;
            default:
                break;
        }
        if (s.matches("\\d+\\s+missed\\s+(group\\s+)?(voice|video)\\s+calls?")) {
            return true;
        }
        if (s.matches("missed\\s+(group\\s+)?(voice|video)\\s+call(\\s*[·•\\-]\\s*.*)?")) {
            return true;
        }
        if (s.matches("missed\\s+call(\\s*[·•\\-]\\s*.*)?")) {
            return true;
        }
        return s.length() <= 48 && s.contains("missed") && s.contains("call")
                && (s.contains("voice") || s.contains("video") || s.startsWith("missed call"));
    }

    private static boolean isGenericSmsTitle(String title) {
        if (title == null) {
            return true;
        }
        String t = title.trim();
        return t.equalsIgnoreCase("Message") || t.equalsIgnoreCase("Messages")
                || t.equalsIgnoreCase("SMS") || t.equalsIgnoreCase("SMS Message")
                || t.equalsIgnoreCase("SMS: Message") || t.equalsIgnoreCase("New message")
                || t.equalsIgnoreCase("Text message");
    }

    /** A sender that is really a phone number becomes its last ten digits. */
    private static String normalizeAlertIdentity(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.length() < 10 || digits.length() < s.replaceAll("[\\s+\\-()]", "").length() - 1) {
            return s;
        }
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
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
        sb.append(" alerts=").append(alertCount);
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
