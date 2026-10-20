package com.bajajnav.relay;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Local ride log.
 *
 * The official app's trips come from Bajaj's cloud, not the bike — the cluster
 * only sends button presses back. So Ride Relay keeps its own log, recorded from
 * the phone's GPS while the bike link is up.
 *
 * Stored points are the route trace shown in the trip dialog: a compact
 * {@code lat,lng;lat,lng} string, thinned out to keep a long ride small.
 */
final class TripStore extends SQLiteOpenHelper {
    private static final String DB = "rides.db";
    private static final int VERSION = 1;
    private static final String TABLE = "trip";

    private static TripStore instance;

    static synchronized TripStore get(Context c) {
        if (instance == null) {
            instance = new TripStore(c.getApplicationContext());
        }
        return instance;
    }

    private TripStore(Context c) {
        super(c, DB, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "started INTEGER NOT NULL,"
                + "ended INTEGER NOT NULL,"
                + "meters REAL NOT NULL,"
                + "moving_s INTEGER NOT NULL,"
                + "max_kmh REAL NOT NULL,"
                + "avg_kmh REAL NOT NULL,"
                + "points TEXT)");
        db.execSQL("CREATE INDEX trip_started ON " + TABLE + " (started DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 is the first schema; nothing to migrate yet.
    }

    /** One ride. Timestamps are epoch milliseconds. */
    static final class Trip {
        long id;
        long started;
        long ended;
        double meters;
        long movingSeconds;
        double maxKmh;
        double avgKmh;
        String points = "";

        double km() {
            return meters / 1000.0;
        }

        long durationSeconds() {
            return Math.max(0, (ended - started) / 1000L);
        }
    }

    void insert(Trip t) {
        ContentValues v = new ContentValues();
        v.put("started", t.started);
        v.put("ended", t.ended);
        v.put("meters", t.meters);
        v.put("moving_s", t.movingSeconds);
        v.put("max_kmh", t.maxKmh);
        v.put("avg_kmh", t.avgKmh);
        v.put("points", t.points);
        try {
            getWritableDatabase().insert(TABLE, null, v);
        } catch (Exception e) {
            Lg.w("trip insert failed: " + e);
        }
    }

    List<Trip> recent(int limit) {
        List<Trip> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(TABLE, null, null, null, null, null,
                "started DESC", String.valueOf(limit))) {
            while (c.moveToNext()) {
                Trip t = new Trip();
                t.id = c.getLong(c.getColumnIndexOrThrow("id"));
                t.started = c.getLong(c.getColumnIndexOrThrow("started"));
                t.ended = c.getLong(c.getColumnIndexOrThrow("ended"));
                t.meters = c.getDouble(c.getColumnIndexOrThrow("meters"));
                t.movingSeconds = c.getLong(c.getColumnIndexOrThrow("moving_s"));
                t.maxKmh = c.getDouble(c.getColumnIndexOrThrow("max_kmh"));
                t.avgKmh = c.getDouble(c.getColumnIndexOrThrow("avg_kmh"));
                String p = c.getString(c.getColumnIndexOrThrow("points"));
                t.points = p == null ? "" : p;
                out.add(t);
            }
        } catch (Exception e) {
            Lg.w("trip query failed: " + e);
        }
        return out;
    }

    int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    double totalMeters() {
        try (Cursor c = getReadableDatabase()
                .rawQuery("SELECT SUM(meters) FROM " + TABLE, null)) {
            return c.moveToFirst() ? c.getDouble(0) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    long totalMovingSeconds() {
        try (Cursor c = getReadableDatabase()
                .rawQuery("SELECT SUM(moving_s) FROM " + TABLE, null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    void clear() {
        try {
            getWritableDatabase().delete(TABLE, null, null);
        } catch (Exception e) {
            Lg.w("trip clear failed: " + e);
        }
    }

    /** "12.4 km" / "820 m", the way a rider reads it. */
    static String distance(double meters) {
        if (meters < 1000) {
            return Math.round(meters) + " m";
        }
        return String.format(Locale.US, "%.1f km", meters / 1000.0);
    }

    /** "1 h 24 min" / "18 min" / "40 s". */
    static String duration(long seconds) {
        if (seconds < 60) {
            return seconds + " s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " min";
        }
        return (minutes / 60) + " h " + (minutes % 60) + " min";
    }
}
