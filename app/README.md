# Ride Relay — app

The Android app. See the [project README](../README.md) for what it does and how to use
it; this file covers the internals and the build.

## Layout

```
AndroidManifest.xml        permissions, services, app icon
build.sh                   aapt2 + javac + d8 + zipalign + apksigner
res/                       app icon (vector + adaptive) and strings
src/com/bajajnav/relay/
  MainActivity.java        main screen: status, find/connect/disconnect, rides entry
  AdvancedActivity.java    relay switch, packet mode, phone switches, test frames, log
  TripsActivity.java       ride log list + detail dialog
  Ui.java                  shared view helpers (sections, buttons, spacing)
  RelayService.java        foreground service; 800 ms TBT loop + 5 s general heartbeat
  NavListener.java         the NotificationListenerService (Maps + phone)
  NavParser.java           turns the Maps notification into NavState
  ManeuverMatcher.java     shape-matches the notification icon against Maps' drawables
  NavState.java            the parsed navigation state
  PhoneBridge.java         call state, caller name, SMS/WhatsApp alerts, missed calls
  Controls.java            decodes the cluster's control characteristic
  ClusterLink.java         BLE: scan, bond, GATT handshake, write queue, reconnect, polling
  TbtFrame.java            48-byte turn-by-turn frame
  GeneralPacket.java       legacy 55-byte / v2 89-byte general status packet
  Alerts.java              44-byte alert + 63-byte missed-call packets
  Pack.java                byte helpers (UTF-8 clamping, timestamps, checksums)
  TripStore.java           SQLite ride log
  TripRecorder.java        GPS sampling and ride bookkeeping
  RouteView.java           route trace drawn from the stored points
  Lg.java                  tiny logger (logcat + in-app ring buffer)
```

## Build

```sh
./build.sh              # -> out/ride-relay.apk
```

Needs JDK 17+ and Android build-tools **35** plus `platforms/android-34`.
`ANDROID_HOME` defaults to `/opt/homebrew/share/android-commandlinetools`.

Why not Gradle: the whole app is ~1300 lines of plain Java with a handful of resources, so
`aapt2` + `javac` + `d8` is faster, has no dependency resolution and no network. Two
gotchas worth knowing if you touch the build:

- Use build-tools 35's `d8`. 34's crashes with `NullPointerException` on any class with
  nested or anonymous classes.
- `javac` with `--classpath android.jar` (not `-bootclasspath`, which fails on
  `LambdaMetafactory`).

## Key implementation notes

**Maneuver detection.** Google Maps' notification exposes distance, an arrival clock and
a road name — never the direction. `ManeuverMatcher` loads Maps' own
`maneuver_*` drawables via `createPackageContext("com.google.android.apps.maps")`,
renders each one, and compares it to the notification's icon after cropping both to
their foreground bounding box and resampling to a 28×28 boolean grid. That
box-normalisation cancels out icon size, padding, aspect and tint.

This needs `<queries><package android:name="com.google.android.apps.maps"/></queries>`
in the manifest (Android 11+ package visibility) and `Icon.loadDrawable()` — note
`Icon.getBitmap()` is not public API.

**BLE handshake.** The cluster expects `discoverServices()` → enable CONTROL
notifications → `requestMtu(256)` before it accepts writes; the link is treated as ready
on `onMtuChanged(status=0)` with a 2.5 s fallback.

**Reconnect.** A dead `BluetoothGatt` must be closed before retrying, late callbacks from
a closed client must be ignored (compare the callback's `BluetoothGatt` against the
current one), and the backoff must reset once a link is healthy. Miss any of those and
the connection flaps.

**Reconnect gives up.** Trying forever is wasteful when the bike is parked: the backoff
climbs 3 s → 6 s → 9 s → 12 s → 15 s, and after 20 consecutive failures (about four
minutes) the link stops retrying and sits at *Idle — tap Connect*. `gaveUp` is cleared by
a manual `connect()` and by a healthy `onReady()`.

**Foreground service.** `startForeground(..., FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)`
throws `SecurityException` until `BLUETOOTH_CONNECT` is granted, so the service retries
on each tick and the activity restarts it after the permission result.

**Control polling.** The cluster advertises the control characteristic as *read-only* and
gives it no CCCD, so notifications are not possible. Like the official app, the app reads
it every 700 ms and acts on bytes that changed since the previous sample. The first sample
only seeds the previous values — the characteristic carries sticky state (dial source,
playlist page) that must not be mistaken for a key press. Reads are skipped while a write
is queued: GATT allows one operation at a time and a read landing on a write makes the
write fail, which is exactly how a call-state packet gets lost.

**Phone state.** `PhoneBridge` listens with `PhoneStateListener` (needs `READ_PHONE_STATE`)
for ringing/off-hook/idle, and takes the caller's name from the dialer's own notification
through the notification listener — no contacts or call-log permission. Alerts are limited
to the SMS apps and WhatsApp, matching the official app, because the cluster's text field
holds 32 bytes; the sender is all that fits anyway. Answering/declining from the cluster
uses `TelecomManager.acceptRingingCall()` / `endCall()` (`ANSWER_PHONE_CALLS`).

**Ride log.** The bike sends no telemetry — the only bike→phone data is the control
characteristic — and the official app's trips come from Bajaj's cloud. So `TripRecorder`
builds its own from GPS while the link is up: 5 s / 8 m sampling, accuracy and
teleport-jump filters, a ride saved only if it is at least 200 m and 60 s, and a split
after ten minutes parked. Distance/time/speed go into SQLite (`rides.db`); the route is
thinned to one point per ~40 m.

## Frame formats

`TbtFrame` (characteristic `0110676e-…`, 48 bytes):

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | type (bit 0) \| step unit (bit 4, 1 = metres) \| AM/PM (bit 7) |
| 1 | 1 | maneuver icon id |
| 2–5 | 4 | step distance, 16.16 fixed point, little-endian |
| 6 | 1 | ETA minute |
| 7 | 1 | ETA hour (1–12) \| roundabout exit << 4 |
| 8–11 | 4 | total distance, 16.16 fixed point, little-endian |
| 12 | 1 | total unit (bit 0) \| GPS status (bits 2–3) |
| 13 | 1 | take-me-home ack |
| 14 | 1 | text length |
| 15–45 | 31 | text |
| 46 | 1 | unused |
| 47 | 1 | checksum = sum(bytes 0..46) & 0xFF |

Maneuver ids are the official app's `PrimaryTurns` values (66–90). The cluster blinks an
arrow when the turn is under 100 m, which the app reproduces by adding 32 to the id.

`Alerts` (`0410676e-…`, 44 bytes): `[0]` type (1 = SMS, 2 = WhatsApp), `[1]` sender length,
`[2..33]` sender UTF-8, `[34..37]` epoch seconds big-endian. No checksum.

`Alerts.missedCall` (`0310676e-…`, 63 bytes): `[0]` = 1, `[1]` name length,
`[2..33]` name, `[34]` number length, `[35..52]` number, `[53..56]` epoch seconds.
No checksum.

`GeneralPacket` puts the call state in the low nibble of byte 1 (0 none, 1 incoming,
2 outgoing, 3 active, 4 ended), the caller name at `[18]`/`[19]` + `[20..51]` (v2) or
`[18]`/`[20]` + `[21..50]` (legacy), the missed-call and alert counts at `[13]`/`[15]`,
and the acknowledgements the cluster compares against at `[4..17]`.

See [`../docs/PROTOCOL.md`](../docs/PROTOCOL.md) for the full picture.
