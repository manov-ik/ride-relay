package com.bajajnav.relay;

import android.app.ActionBar;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything that is not needed for day-to-day riding: the relay switch, the
 * general status packet, test frames and the log. Reached from the gear in the
 * top right of the main screen.
 */
public class AdvancedActivity extends Activity implements Lg.Sink {
    /** Keep the on-screen log bounded; Lg's own ring buffer is 600 lines. */
    private static final int LOG_MAX_CHARS = 60000;
    private static final int REQ_CALLS = 200;
    private static final int REQ_RIDES = 201;
    /** Written to the tag; MainActivity only reacts to this scheme + host. */
    private static final String NFC_URL = "bajajrelay://connect";

    private SharedPreferences prefs;
    private CheckBox relayBox;
    private Spinner genSpinner;
    private TextView logView;
    private ScrollView logScroll;
    private TextView updateStatus;
    private Button updateButton;
    private boolean updateBusy;

    private final List<String> genModes = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(RelayService.PREFS, MODE_PRIVATE);
        setContentView(buildUi());

        ActionBar ab = getActionBar();
        if (ab != null) {
            // Same banner colour as the main screen so the two match.
            ab.setBackgroundDrawable(new ColorDrawable(0xFF101A26));
            ab.setDisplayHomeAsUpEnabled(true);
            ab.setTitle("Advanced");
        }
        Lg.addSink(this);
        // Show the newest line once, when the screen opens. After this the log
        // never scrolls on its own, so incoming lines cannot pull the page down.
        logScroll.postDelayed(() -> logScroll.fullScroll(View.FOCUS_DOWN), 250L);
    }

    @Override
    protected void onDestroy() {
        Lg.removeSink(this);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The daily check may have finished while this screen was opening.
        refreshUpdateUi();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------------ UI

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private View buildUi() {
        ScrollView root = new ScrollView(this);
        root.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(14), dp(20), dp(32));
        root.addView(col);

        col.addView(Ui.section(this, "Updates"));
        TextView current = Ui.row(this, col, "Version");
        current.setText(Updater.currentVersion(this));

        updateStatus = new TextView(this);
        updateStatus.setTextSize(12);
        updateStatus.setTextColor(Ui.secondary(this));
        updateStatus.setLineSpacing(0, 1.15f);
        updateStatus.setPadding(0, dp(2), 0, dp(4));
        col.addView(updateStatus);

        CheckBox autoUpdate = new CheckBox(this);
        autoUpdate.setText("Check GitHub once a day");
        autoUpdate.setTextSize(14);
        autoUpdate.setChecked(prefs.getBoolean(Updater.K_AUTO, true));
        autoUpdate.setOnCheckedChangeListener((v, checked) ->
                prefs.edit().putBoolean(Updater.K_AUTO, checked).apply());
        col.addView(autoUpdate);
        col.addView(hint("Android asks before installing; the first time, allow installs "
                + "from Ride Relay. Only the release check talks to GitHub — rides, "
                + "calls and the log stay on the phone."));

        updateButton = Ui.button(this, "Check for updates");
        updateButton.setOnClickListener(v -> onUpdateButton());
        col.addView(Ui.fullWidth(updateButton));
        refreshUpdateUi();

        col.addView(Ui.rule(this));
        col.addView(Ui.section(this, "Relay"));
        relayBox = new CheckBox(this);
        relayBox.setText("Send Google Maps navigation to the cluster");
        relayBox.setTextSize(14);
        relayBox.setChecked(prefs.getBoolean(RelayService.K_RELAY, true));
        relayBox.setOnCheckedChangeListener((v, checked) -> {
            RelayService.relayEnabled = checked;
            prefs.edit().putBoolean(RelayService.K_RELAY, checked).apply();
        });
        col.addView(relayBox);

        TextView genLabel = new TextView(this);
        genLabel.setText("General status packet");
        genLabel.setTextSize(13);
        genLabel.setTextColor(Ui.secondary(this));
        genLabel.setPadding(0, dp(12), 0, 0);
        col.addView(genLabel);

        genModes.clear();
        genModes.add("Off");
        genModes.add("Legacy 55 bytes");
        genModes.add("V2 89 bytes");
        genSpinner = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, genModes);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        genSpinner.setAdapter(ad);
        genSpinner.setSelection(prefs.getInt(RelayService.K_GEN, GeneralPacket.MODE_LEGACY55));
        genSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                RelayService.generalMode = position;
                prefs.edit().putInt(RelayService.K_GEN, position).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        col.addView(genSpinner);

        col.addView(Ui.rule(this));
        col.addView(Ui.section(this, "Phone"));

        CheckBox callsBox = new CheckBox(this);
        callsBox.setText("Calls on the cluster");
        callsBox.setTextSize(14);
        callsBox.setChecked(prefs.getBoolean(RelayService.K_CALLS, true));
        callsBox.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean(RelayService.K_CALLS, checked).apply();
            PhoneBridge.get().setCallsEnabled(checked);
            if (checked) {
                requestPermissions(new String[]{
                        android.Manifest.permission.READ_PHONE_STATE,
                        android.Manifest.permission.ANSWER_PHONE_CALLS}, REQ_CALLS);
            }
        });
        col.addView(callsBox);
        col.addView(hint("Shows the caller and lets the cluster's accept/decline keys answer "
                + "or hang up."));

        CheckBox ridesBox = new CheckBox(this);
        ridesBox.setText("Record rides");
        ridesBox.setTextSize(14);
        ridesBox.setChecked(prefs.getBoolean(RelayService.K_RIDES, true));
        ridesBox.setOnCheckedChangeListener((v, checked) -> {
            RelayService.ridesEnabled = checked;
            prefs.edit().putBoolean(RelayService.K_RIDES, checked).apply();
            TripRecorder.refresh();
            if (checked) {
                requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                        REQ_RIDES);
            }
        });
        col.addView(ridesBox);
        col.addView(hint("Distance, time and speed from GPS while the bike link is up. "
                + "Saved on this phone only."));

        col.addView(Ui.rule(this));
        col.addView(Ui.section(this, "NFC"));

        CheckBox nfcBox = new CheckBox(this);
        nfcBox.setText("Connect with NFC");
        nfcBox.setTextSize(14);
        nfcBox.setChecked(prefs.getBoolean(RelayService.K_NFC, true));
        nfcBox.setOnCheckedChangeListener((v, checked) ->
                prefs.edit().putBoolean(RelayService.K_NFC, checked).apply());
        col.addView(nfcBox);
        col.addView(hint("Tap a written tag and Ride Relay connects to the saved bike in "
                + "the background. The app does not open; the tag only presses Connect for you."));

        TextView nfcUrl = new TextView(this);
        nfcUrl.setText(NFC_URL);
        nfcUrl.setTextSize(14);
        nfcUrl.setTypeface(Typeface.MONOSPACE);
        nfcUrl.setTextIsSelectable(true);
        nfcUrl.setPadding(dp(28), dp(4), 0, dp(4));
        col.addView(nfcUrl);

        Button copyNfc = Ui.button(this, "Copy URL");
        copyNfc.setOnClickListener(v -> copyText("NFC URL", NFC_URL, "URL copied"));
        col.addView(Ui.fullWidth(copyNfc));

        col.addView(hint("Write it as a URI record with any NFC writer (NFC Tools, TagWriter). "
                + "NFC must be on, and the phone reads tags while the screen is on."));

        col.addView(Ui.rule(this));
        col.addView(Ui.section(this, "Test frames"));

        TextView testHint = new TextView(this);
        testHint.setText("Sends straight to the bike — works without a phone navigation app.");
        testHint.setTextSize(13);
        testHint.setTextColor(Ui.secondary(this));
        testHint.setPadding(0, 0, 0, dp(8));
        col.addView(testHint);

        col.addView(Ui.buttonRow(this,
                testButton("Left", TbtFrame.TURN_LEFT, "Left"),
                testButton("Right", TbtFrame.TURN_RIGHT, "Right")));
        col.addView(Ui.buttonRow(this,
                testButton("Straight", TbtFrame.STRAIGHT, "Continue"),
                testButton("U-turn", TbtFrame.U_TURN_LEFT, "U turn")));
        col.addView(Ui.buttonRow(this,
                testButton("Roundabout", TbtFrame.ROUNDABOUT_LEFT, "Roundabout"),
                testButton("Arrived", TbtFrame.DEST_REACHED, "Destination reached")));

        Button stop = Ui.button(this, "Stop navigation");
        stop.setOnClickListener(v -> ClusterLink.get().sendTbt(TbtFrame.stop()));
        col.addView(Ui.fullWidth(stop));

        col.addView(Ui.rule(this));
        col.addView(Ui.section(this, "Log"));

        logScroll = new ScrollView(this);
        logScroll.setFocusable(false);
        logScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));
        logView = new TextView(this);
        logView.setTextSize(10);
        logView.setTypeface(Typeface.MONOSPACE);
        // Keep the log out of the focus chain so the page cannot scroll to it
        // when lines are appended.
        logView.setFocusable(false);
        logView.setFocusableInTouchMode(false);
        logScroll.addView(logView);
        col.addView(logScroll);

        Button clear = Ui.button(this, "Clear log");
        clear.setOnClickListener(v -> logView.setText(""));
        Button copy = Ui.button(this, "Copy log");
        copy.setOnClickListener(v -> copyLog());
        col.addView(Ui.buttonRow(this, clear, copy));

        return root;
    }

    // ---------------------------------------------------------------- updates

    private void refreshUpdateUi() {
        if (updateStatus == null || updateButton == null) {
            return;
        }
        String available = Updater.availableVersion(this);
        if (!available.isEmpty()) {
            String size = Updater.availableSize(this);
            updateStatus.setText("Version " + available + " is available"
                    + (size.isEmpty() ? "" : " · " + size) + ".");
            updateButton.setText("Download and install " + available);
        } else {
            updateStatus.setText("Last checked: " + Updater.lastCheckLabel(this));
            updateButton.setText("Check for updates");
        }
        updateButton.setEnabled(!updateBusy);
    }

    private void onUpdateButton() {
        if (updateBusy) {
            return;
        }
        final String available = Updater.availableVersion(this);
        updateBusy = true;
        updateButton.setEnabled(false);

        if (!available.isEmpty()) {
            updateStatus.setText("Downloading " + available + "…");
            Updater.downloadAndInstall(this, new Updater.InstallCallback() {
                @Override
                public void onProgress(int percent) {
                    updateStatus.setText("Downloading " + available + "… " + percent + "%");
                }

                @Override
                public void onResult(boolean ok, String message) {
                    updateBusy = false;
                    updateStatus.setText(message);
                    updateButton.setEnabled(true);
                    updateButton.setText("Download and install " + available);
                    if (!ok) {
                        Toast.makeText(AdvancedActivity.this, message,
                                Toast.LENGTH_LONG).show();
                    }
                }
            });
            return;
        }

        updateStatus.setText("Checking GitHub…");
        Updater.check(this, true, (ok, found, message) -> {
            updateBusy = false;
            updateButton.setEnabled(true);
            if (ok) {
                refreshUpdateUi();
            } else {
                updateStatus.setText(message);
            }
            Toast.makeText(AdvancedActivity.this, message, Toast.LENGTH_LONG).show();
        });
    }

    /** Small grey explanation under a checkbox. */
    private TextView hint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(Ui.secondary(this));
        t.setLineSpacing(0, 1.15f);
        t.setPadding(dp(28), 0, 0, dp(6));
        return t;
    }

    private Button testButton(String label, int maneuver, String text) {        Button b = Ui.button(this, label);
        b.setOnClickListener(v -> ClusterLink.get().sendTestFrame(maneuver, text));
        return b;
    }

    private void copyLog() {
        copyText(getString(R.string.app_name) + " log", logView.getText().toString(), "Log copied");
    }

    private void copyText(String label, String text, String toast) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ Lg sink

    @Override
    public void onLine(final String line) {
        ui.post(() -> {
            if (logView == null) {
                return;
            }
            logView.append(line);
            logView.append("\n");
            if (logView.length() > LOG_MAX_CHARS) {
                CharSequence t = logView.getText();
                int cut = t.toString().indexOf('\n', t.length() - 40000);
                if (cut > 0) {
                    logView.setText(t.subSequence(cut + 1, t.length()));
                }
            }
        });
    }
}
