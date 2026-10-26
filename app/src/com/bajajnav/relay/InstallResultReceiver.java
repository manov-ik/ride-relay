package com.bajajnav.relay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/**
 * Result of a PackageInstaller session started by {@link Updater}. On most
 * versions the system sends PENDING_USER_ACTION with the installer activity to
 * open; after the user confirms, the final status arrives here.
 */
public class InstallResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                } catch (Exception e) {
                    Toast.makeText(context, "Could not open the installer",
                            Toast.LENGTH_LONG).show();
                }
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "Ride Relay updated", Toast.LENGTH_LONG).show();
            context.getSharedPreferences(RelayService.PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .remove(Updater.K_VERSION)
                    .remove(Updater.K_URL)
                    .remove(Updater.K_SHA)
                    .remove(Updater.K_SIZE)
                    .apply();
        } else {
            String extra = message == null || message.isEmpty() ? "" : " — " + message;
            Toast.makeText(context, "Update failed" + extra, Toast.LENGTH_LONG).show();
        }
    }
}
