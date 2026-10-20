package com.bajajnav.relay;

import android.Manifest;
import android.app.ActionBar;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole app in one plain screen: what the state is, and the two buttons that
 * matter (find / connect, disconnect). Everything else — relay toggle, general
 * packet, test frames and the log — lives one tap away under "Advanced".
 */
public class MainActivity extends Activity implements ClusterLink.Listener {
    private static final int REQ_PERMS = 100;
    /** The banner paints itself, so its text colour is known and not guessed. */
    private static final int BAR_BG = 0xFF101A26;
    private static final int BAR_FG = 0xFFFFFFFF;

    private SharedPreferences prefs;

    private TextView vNotifAccess;
    private TextView vLink;
    private TextView vBike;
    private TextView vNav;
    private TextView vRides;
    private Button notifButton;

    private Button scanButton;
    private TextView deviceHint;
    private LinearLayout deviceList;
    private ScrollView deviceScroll;
    private LinearLayout connRow;
    private Button connectButton;
    private Button disconnectButton;

    private final Map<String, String[]> seen = new LinkedHashMap<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            ui.postDelayed(this, 1000L);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(RelayService.PREFS, MODE_PRIVATE);
        ClusterLink.get().init(this);
        ManeuverMatcher.prime(this);
        setContentView(buildUi());
        setupBanner();
        ClusterLink.get().addListener(this);
        requestPermissions();
        refreshStatus();
        startRelayService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The Advanced screen owns these switches; pick up whatever changed.
        RelayService.relayEnabled = prefs.getBoolean(RelayService.K_RELAY, true);
        RelayService.ridesEnabled = prefs.getBoolean(RelayService.K_RIDES, true);
        PhoneBridge.get().setCallsEnabled(prefs.getBoolean(RelayService.K_CALLS, true));
        RelayService.allowLocationType();
        TripRecorder.refresh();
        refreshStatus();
        ui.removeCallbacks(ticker);
        ui.postDelayed(ticker, 1000L);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(ticker);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ClusterLink.get().removeListener(this);
        ui.removeCallbacks(ticker);
        super.onDestroy();
    }

    private void startRelayService() {
        Intent svc = new Intent(this, RelayService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    private String savedAddress() {
        return prefs.getString(RelayService.K_ADDR, null);
    }

    private String savedName() {
        return prefs.getString(RelayService.K_NAME, null);
    }

    // ---------------------------------------------------------------------- theme

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private int secondaryText() {
        return Ui.secondary(this);
    }

    // ------------------------------------------------------------------------ UI

    /** Action bar: app name on the left, the advanced gear on the right. */
    private void setupBanner() {
        ActionBar ab = getActionBar();
        if (ab == null) {
            return;
        }
        ab.setBackgroundDrawable(new ColorDrawable(BAR_BG));
        ab.setDisplayShowHomeEnabled(false);
        ab.setDisplayShowTitleEnabled(false);
        ab.setDisplayShowCustomEnabled(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), 0, dp(2), 0);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(BAR_FG);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);

        ImageView advanced = new ImageView(this);
        advanced.setImageResource(R.drawable.ic_advanced);
        advanced.setPadding(dp(12), dp(12), dp(12), dp(12));
        advanced.setContentDescription("Advanced");
        // Pull the gear out towards the screen edge: the action bar already
        // contributes its own inset and this would otherwise look off-centre.
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.rightMargin = -dp(10);
        advanced.setLayoutParams(alp);
        TypedValue ripple = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                && ripple.resourceId != 0) {
            advanced.setBackgroundResource(ripple.resourceId);
        }
        advanced.setOnClickListener(v -> startActivity(new Intent(this, AdvancedActivity.class)));
        bar.addView(advanced);

        ab.setCustomView(bar, new ActionBar.LayoutParams(
                ActionBar.LayoutParams.MATCH_PARENT, ActionBar.LayoutParams.MATCH_PARENT));
    }

    private View buildUi() {
        ScrollView root = new ScrollView(this);
        root.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(14), dp(20), dp(32));
        root.addView(col);

        col.addView(section("Status"));
        vNotifAccess = row(col, "Notification access");
        vLink = row(col, "Bike link");
        vBike = row(col, "Bike");
        vNav = row(col, "Navigating");

        notifButton = button("Enable notification access");
        notifButton.setOnClickListener(v -> openNotificationSettings());
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(10);
        notifButton.setLayoutParams(nlp);
        col.addView(notifButton);

        col.addView(divider());
        col.addView(section("Bike"));

        scanButton = button("Find bike");
        scanButton.setOnClickListener(v -> onScanClicked());
        col.addView(fullWidth(scanButton));

        deviceHint = new TextView(this);
        deviceHint.setTextSize(13);
        deviceHint.setTextColor(secondaryText());
        deviceHint.setPadding(0, dp(10), 0, dp(6));
        col.addView(deviceHint);

        deviceScroll = new ScrollView(this);
        deviceScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(140)));
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        deviceScroll.addView(deviceList);
        deviceScroll.setVisibility(View.GONE);
        col.addView(deviceScroll);

        connectButton = button("Connect");
        connectButton.setOnClickListener(v -> {
            String a = savedAddress();
            if (a != null) {
                String n = savedName();
                ClusterLink.get().connect(a, n == null ? a : n);
            }
        });
        disconnectButton = button("Disconnect");
        disconnectButton.setOnClickListener(v -> ClusterLink.get().disconnect());

        connRow = buttonRow(connectButton, disconnectButton);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(12);
        connRow.setLayoutParams(clp);
        col.addView(connRow);

        col.addView(divider());
        col.addView(section("Rides"));
        vRides = row(col, "Recorded");

        Button ridesButton = button("Open rides");
        ridesButton.setOnClickListener(v -> startActivity(new Intent(this, TripsActivity.class)));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);
        ridesButton.setLayoutParams(rlp);
        col.addView(ridesButton);

        return root;
    }

    private View section(String title) {
        return Ui.section(this, title);
    }

    private View divider() {
        return Ui.rule(this);
    }

    private TextView row(LinearLayout parent, String label) {
        return Ui.row(this, parent, label);
    }

    private View fullWidth(View child) {
        return Ui.fullWidth(child);
    }

    private LinearLayout buttonRow(Button... buttons) {
        return Ui.buttonRow(this, buttons);
    }

    private Button button(String text) {
        return Ui.button(this, text);
    }

    // -------------------------------------------------------------------- actions

    private void openNotificationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void onScanClicked() {
        if (ClusterLink.get().isScanning()) {
            ClusterLink.get().stopScan();
            return;
        }
        seen.clear();
        deviceList.removeAllViews();
        requestPermissions();
        ClusterLink.get().startScan();
    }

    // --------------------------------------------------------------------- status

    private void refreshStatus() {
        boolean granted = hasNotificationAccess() || NavListener.isConnected();
        vNotifAccess.setText(granted ? "Granted" : "Not granted");
        notifButton.setVisibility(granted ? View.GONE : View.VISIBLE);

        String link;
        if (ClusterLink.get().isReady()) {
            link = "Ready";
        } else if (ClusterLink.get().isConnected()) {
            link = "Connecting…";
        } else if (ClusterLink.get().hasGivenUp()) {
            link = "Idle — tap Connect";
        } else {
            link = "Disconnected";
        }
        vLink.setText(link);

        String addr = ClusterLink.get().getAddress();
        if (addr == null) {
            addr = savedAddress();
        }
        String name = ClusterLink.get().getName();
        if (name == null) {
            name = savedName();
        }
        if (addr == null) {
            vBike.setText("None");
        } else if (name != null && !name.isEmpty() && !name.equals(addr)) {
            vBike.setText(name);
        } else {
            vBike.setText(addr);
        }

        if (NavState.active) {
            StringBuilder sb = new StringBuilder(TbtFrame.maneuverName(NavState.maneuver));
            if (NavState.stepMeters > 0) {
                sb.append(" · ").append(TbtFrame.distanceText(NavState.stepMeters));
            }
            vNav.setText(sb.toString());
        } else {
            vNav.setText("—");
        }

        TripStore store = TripStore.get(this);
        String rides = store.count() + (store.count() == 1 ? " ride" : " rides")
                + " · " + TripStore.distance(store.totalMeters());
        if (TripRecorder.isRecording()) {
            rides += " · recording";
        }
        vRides.setText(rides);

        boolean saved = savedAddress() != null;
        boolean ready = ClusterLink.get().isReady();

        // Connect stays visible even before a bike is saved (just disabled), so the
        // screen always has the same two controls and nothing jumps around.
        connRow.setVisibility(View.VISIBLE);
        scanButton.setVisibility(ready ? View.GONE : View.VISIBLE);
        connectButton.setEnabled(!ready && saved);
        disconnectButton.setEnabled(ClusterLink.get().isConnected() || ready);
        scanButton.setText(ClusterLink.get().isScanning() ? "Stop scan" : "Find bike");

        if (ready) {
            deviceHint.setVisibility(View.GONE);
            deviceScroll.setVisibility(View.GONE);
            return;
        }
        if (deviceList.getChildCount() > 0) {
            deviceHint.setVisibility(View.GONE);
            deviceScroll.setVisibility(View.VISIBLE);
        } else {
            deviceScroll.setVisibility(View.GONE);
            deviceHint.setVisibility(View.VISIBLE);
            if (ClusterLink.get().isScanning()) {
                deviceHint.setText("Scanning…");
            } else if (saved) {
                deviceHint.setText("No devices found yet — tap Find bike.");
            } else {
                deviceHint.setText("No bike saved yet — tap Find bike to scan.");
            }
        }
    }

    private boolean hasNotificationAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        return enabled != null && enabled.contains(getPackageName());
    }

    // ---------------------------------------------------------------- permissions

    private void requestPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            need.add("android.permission.POST_NOTIFICATIONS");
        }
        if (Build.VERSION.SDK_INT >= 31 && RelayService.ridesEnabled
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (prefs.getBoolean(RelayService.K_CALLS, true)) {
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.READ_PHONE_STATE);
            }
            if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.ANSWER_PHONE_CALLS);
            }
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            startRelayService();
            TripRecorder.refresh();
            refreshStatus();
        }
    }

    // ------------------------------------------------------ ClusterLink listener

    @Override
    public void onDevice(final String name, final String address, final int rssi) {
        runOnUiThread(() -> {
            if (seen.containsKey(address)) {
                return;
            }
            seen.put(address, new String[]{name, address});

            String label = (name == null || name.isEmpty() ? "(no name)" : name)
                    + "   " + address + "   " + rssi + " dBm";
            Button b = button(label);
            b.setOnClickListener(v -> {
                String n = name == null || name.isEmpty() ? address : name;
                prefs.edit()
                        .putString(RelayService.K_ADDR, address)
                        .putString(RelayService.K_NAME, n)
                        .apply();
                ClusterLink.get().stopScan();
                ClusterLink.get().connect(address, n);
                refreshStatus();
            });
            deviceList.addView(fullWidth(b));
            deviceHint.setVisibility(View.GONE);
            deviceScroll.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onConnection(boolean connected, String address, String name) {
        runOnUiThread(this::refreshStatus);
    }

    @Override
    public void onStatus(String s) {
        runOnUiThread(this::refreshStatus);
    }
}
