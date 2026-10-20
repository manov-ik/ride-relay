package com.bajajnav.relay;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The ride log: every ride recorded while the bike link was up, newest first.
 *
 * This is the one feature Ride Relay cannot copy from the official app — Bajaj's
 * trips come from their cloud, and the cluster has no telemetry channel — so it
 * is built from the phone's GPS instead. See {@link TripRecorder}.
 */
public class TripsActivity extends Activity {
    private static final int BAR_BG = 0xFF101A26;

    private TripStore store;
    private LinearLayout col;
    private TextView summary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = TripStore.get(this);
        setContentView(buildUi());

        ActionBar ab = getActionBar();
        if (ab != null) {
            ab.setBackgroundDrawable(new ColorDrawable(BAR_BG));
            ab.setDisplayHomeAsUpEnabled(true);
            ab.setTitle("Rides");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private View buildUi() {
        ScrollView root = new ScrollView(this);
        root.setFillViewport(true);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(14), dp(20), dp(32));
        root.addView(col);
        return root;
    }

    private void rebuild() {
        col.removeAllViews();

        List<TripStore.Trip> trips = store.recent(200);
        summary = new TextView(this);
        summary.setTextSize(13);
        summary.setTextColor(Ui.secondary(this));
        summary.setPadding(0, 0, 0, dp(12));
        col.addView(summary);

        if (trips.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No rides yet.\n\nRide Relay records a ride while the bike link is up and "
                    + "location access is allowed. Distance, time and speed are saved on the phone.");
            empty.setTextSize(14);
            empty.setTextColor(Ui.secondary(this));
            empty.setLineSpacing(0, 1.25f);
            col.addView(empty);
            Button settings = Ui.button(this, "Allow location access");
            settings.setOnClickListener(v ->
                    startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(16);
            settings.setLayoutParams(lp);
            col.addView(settings);
            return;
        }

        double meters = 0;
        long moving = 0;
        for (TripStore.Trip t : trips) {
            meters += t.meters;
            moving += t.movingSeconds;
        }
        summary.setText(trips.size() + (trips.size() == 1 ? " ride" : " rides")
                + "  ·  " + TripStore.distance(meters) + "  ·  " + TripStore.duration(moving));

        col.addView(Ui.section(this, "Logged rides"));
        for (TripStore.Trip t : trips) {
            addTripRow(t);
        }

        col.addView(Ui.rule(this));
        Button clear = Ui.button(this, "Clear ride log");
        clear.setOnClickListener(v -> confirmClear());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        clear.setLayoutParams(lp);
        col.addView(clear);
    }

    private void addTripRow(final TripStore.Trip t) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setBackground(getSelectableBackground());

        TextView when = new TextView(this);
        when.setText(formatDate(t.started) + "  ·  " + formatTime(t.started)
                + " – " + formatTime(t.ended));
        when.setTextSize(14);
        when.setTypeface(Typeface.DEFAULT_BOLD);
        when.setTextColor(Ui.primary(this));
        row.addView(when);

        TextView stats = new TextView(this);
        stats.setText(TripStore.distance(t.meters)
                + "  ·  " + TripStore.duration(t.durationSeconds())
                + "  ·  avg " + Math.round(t.avgKmh) + " km/h"
                + "  ·  max " + Math.round(t.maxKmh) + " km/h");
        stats.setTextSize(13);
        stats.setTextColor(Ui.secondary(this));
        stats.setPadding(0, dp(3), 0, 0);
        row.addView(stats);

        row.setOnClickListener(v -> showTrip(t));
        col.addView(row);
        col.addView(Ui.rule(this));
    }

    private android.graphics.drawable.Drawable getSelectableBackground() {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                && tv.resourceId != 0) {
            return getDrawable(tv.resourceId);
        }
        return null;
    }

    private void showTrip(TripStore.Trip t) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));

        TextView title = new TextView(this);
        title.setText(formatDate(t.started) + "  ·  " + formatTime(t.started));
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView stats = new TextView(this);
        stats.setText(TripStore.distance(t.meters) + "\n"
                + "Time  " + TripStore.duration(t.durationSeconds()) + "\n"
                + "Moving  " + TripStore.duration(t.movingSeconds) + "\n"
                + "Average  " + String.format(Locale.US, "%.1f", t.avgKmh) + " km/h\n"
                + "Top speed  " + String.format(Locale.US, "%.1f", t.maxKmh) + " km/h");
        stats.setTextSize(14);
        stats.setLineSpacing(0, 1.35f);
        stats.setPadding(0, dp(8), 0, dp(8));
        box.addView(stats);

        if (t.points != null && !t.points.isEmpty()) {
            RouteView route = new RouteView(this);
            route.setPoints(t.points);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
            rlp.topMargin = dp(4);
            route.setLayoutParams(rlp);
            box.addView(route);

            TextView caption = new TextView(this);
            caption.setText(R.string.route_trace_caption);
            caption.setTextSize(11);
            caption.setTextColor(Ui.secondary(this));
            caption.setPadding(0, dp(6), 0, 0);
            caption.setGravity(Gravity.START);
            box.addView(caption);
        }

        new AlertDialog.Builder(this)
                .setView(box)
                .setPositiveButton("Close", null)
                .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Clear ride log?")
                .setMessage("All recorded rides on this phone will be deleted. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, w) -> {
                    store.clear();
                    rebuild();
                })
                .show();
    }

    private static String formatDate(long millis) {
        return new SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())
                .format(new Date(millis));
    }

    private static String formatTime(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(millis));
    }
}
