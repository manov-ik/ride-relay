package com.bajajnav.relay;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/** Tiny in-app log shared by the UI and every component. */
final class Lg {
    interface Sink {
        void onLine(String line);
    }

    private static final String TAG = "BajajRelay";
    private static final List<Sink> SINKS = new ArrayList<>();
    private static final LinkedList<String> BUF = new LinkedList<>();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private Lg() {
    }

    static void addSink(Sink s) {
        List<String> snap;
        synchronized (BUF) {
            snap = new ArrayList<>(BUF);
        }
        synchronized (SINKS) {
            SINKS.add(s);
        }
        for (String l : snap) {
            s.onLine(l);
        }
    }

    static void removeSink(Sink s) {
        synchronized (SINKS) {
            SINKS.remove(s);
        }
    }

    static void i(String m) {
        line('I', m);
    }

    static void w(String m) {
        line('W', m);
    }

    private static void line(char lvl, String m) {
        String l = FMT.format(new Date()) + " " + lvl + " " + m;
        Log.println(lvl == 'W' ? Log.WARN : Log.INFO, TAG, m);
        List<Sink> sinks;
        synchronized (BUF) {
            BUF.add(l);
            if (BUF.size() > 600) {
                BUF.removeFirst();
            }
        }
        synchronized (SINKS) {
            sinks = new ArrayList<>(SINKS);
        }
        for (Sink s : sinks) {
            try {
                s.onLine(l);
            } catch (Exception ignored) {
            }
        }
    }
}
