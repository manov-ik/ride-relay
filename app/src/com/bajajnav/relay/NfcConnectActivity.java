package com.bajajnav.relay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Invisible target for {@code bajajrelay://connect} NFC tags.
 *
 * Android only dispatches NDEF tags to activities, never to a background
 * receiver or service, so a tag cannot be handled without an activity. This one
 * has a transparent, animation-free theme and finishes in the same frame: the
 * foreground service is started first, the saved bike is connected, and no Ride
 * Relay screen is shown. Tapping the tag is still exactly the Connect button.
 */
public class NfcConnectActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        Intent intent = getIntent();
        Uri data = intent == null ? null : intent.getData();
        if (data == null
                || !"bajajrelay".equals(data.getScheme())
                || !"connect".equals(data.getHost())) {
            finish();
            return;
        }

        SharedPreferences prefs = getSharedPreferences(RelayService.PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(RelayService.K_NFC, true)) {
            Toast.makeText(this, "NFC connect is off in Advanced", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        String address = prefs.getString(RelayService.K_ADDR, null);
        if (address == null) {
            Toast.makeText(this, "No bike saved yet — open Ride Relay and tap Find bike",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (!bluetoothAllowed()) {
            // A transparent activity cannot show a permission dialog sensibly.
            // The rider has to open the app once and grant it there.
            Toast.makeText(this, "Open Ride Relay once to grant Bluetooth access",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        ClusterLink.get().init(this);
        boolean wasRunning = RelayService.isRunning();

        // The service owns the link (foreground notification, reconnects, ride
        // log). Starting it here also covers a cold process.
        Intent svc = new Intent(this, RelayService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }

        if (ClusterLink.get().isReady() || ClusterLink.get().isConnected()) {
            Lg.i("nfc: tag tapped, link already up");
            finish();
            return;
        }

        if (!wasRunning) {
            // A cold start runs RelayService.onCreate, which already connects to
            // the saved bike. Connecting here as well would tear that attempt
            // down and start a second one.
            Lg.i("nfc: tag tapped -> service starting, auto-connect " + address);
            finish();
            return;
        }

        String name = prefs.getString(RelayService.K_NAME, address);
        Lg.i("nfc: tag tapped -> connect " + address);
        ClusterLink.get().connect(address, name);
        finish();
    }

    private boolean bluetoothAllowed() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }
}
