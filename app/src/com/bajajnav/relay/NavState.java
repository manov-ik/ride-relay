package com.bajajnav.relay;

/** Current navigation state parsed from the Google Maps notification. */
final class NavState {
    static volatile boolean active = false;
    static volatile int maneuver = TbtFrame.STRAIGHT;
    static volatile int stepMeters = 0;
    static volatile int totalMeters = 0;
    static volatile int etaSeconds = -1;
    /** Arrival clock as shown by Maps: 12-hour hour (1-12), minute, am/pm. -1 hour = unknown. */
    static volatile int etaHour = -1;
    static volatile int etaMinute = -1;
    static volatile boolean etaIsAm = true;
    static volatile String street = "";
    static volatile String rawTitle = "";
    static volatile String rawText = "";
    static volatile String rawSubText = "";
    static volatile long lastUpdateMs = 0L;
    static volatile long seq = 0L;

    private NavState() {
    }

    static synchronized void update(int maneuverId, int step, int total, int etaSec,
                                    int clockHour, int clockMin, boolean clockAm, String streetText) {
        maneuver = maneuverId;
        stepMeters = Math.max(0, step);
        totalMeters = Math.max(0, total);
        etaSeconds = etaSec;
        etaHour = clockHour;
        etaMinute = clockMin;
        etaIsAm = clockAm;
        street = streetText == null ? "" : streetText;
        lastUpdateMs = System.currentTimeMillis();
        seq++;
        active = true;
    }

    static synchronized void clear() {
        active = false;
        lastUpdateMs = System.currentTimeMillis();
        seq++;
    }
}
