# Bajaj Ride Connect — BLE protocol & navigation findings

Target artifact: `Bajaj Ride Connect.apk`
- Package: `com.bajajconnect.rideapp`
- Version: `1.11.57` (versionCode `264`)
- minSdk 24 / targetSdk 35, single base APK (no split libs in this file)
- SHA-256: `a72583c496f8bfdb290c408ccb5ad58228d81782cd8943f789c40ff858ef8b1d`
- Stack: React Native (Hermes bytecode) + native Kotlin/Java BLE + MapmyIndia (Mappls) SDK + Google Navigation SDK

This supersedes the earlier `FINDINGS.md` (which covered the unrelated `com.excellonsoft.bajajibcare` service app).

> **Note on paths.** References to `re-work/...` in this document point at local
> reverse-engineering scratch (decompiled sources, extracted APKs and patched builds).
> That directory is deliberately **not** committed — it contains other people's code and
> binaries. Everything here describes observed behaviour; no third-party code is
> redistributed.

---

## 1. Bluetooth link

The app talks to the bike's instrument cluster over **BLE GATT**. All bike-facing code lives in `com.bajajconnect.ble.*`.

### 1.1 GATT service / characteristics

Main service:

```
MAIN_SERVICE = 0010676e-6972-6565-6e69-676e4543544f
```

Characteristics (all share the `10676e-6972-6565-6e69-676e4543544f` suffix; first byte is an index):

| UUID | Field | Direction | Purpose |
|---|---|---|---|
| `0110676e-…` | `TBT_INFO_CHAR` | phone → bike | **Turn-by-turn navigation frames (48 bytes)** |
| `0210676e-…` | `GENERAL_CHAR` (`TBT_DATA`) | phone → bike | General status / heartbeat packet |
| `0310676e-…` | `MISSED_CALL_CHAR` | phone → bike | Missed-call packet |
| `0410676e-…` | `ALERTS_INFO_CHAR` | phone → bike | SMS / alert packet |
| `0510676e-…` | `PLAYLIST_INFO_CHAR` | phone → bike | Playlist packets |
| `0610676e-…` | `MEDIA_INFO_CHAR` | phone → bike | Now-playing metadata |
| `0910676e-…` | `FAV_CONTACTS_CHAR` | phone → bike | Favourite contacts |
| `0a10676e-…` | `CONTROL_CHAR` | **bike → phone** | Cluster buttons/dial (notify + read) |
| `0b10676e-…` | `RECENT_CALLS_CHAR` | phone → bike | Recent calls |

CCCD descriptor (standard): `00002902-0000-1000-8000-00805f9b34fb`.
Only `CONTROL_CHAR` notifications are enabled by the app; everything else is phone→bike writes.

### 1.2 Connection sequence (from `BleService`)

1. Scan with an **empty `ScanFilter`** (`new ScanFilter.Builder().build()`), SCAN_MODE_LOW_LATENCY, 10 s timeout.
2. Match a result when `resolveScanDeviceName()` equals `GlobalVar.bleDeviceName` (case-insensitive) **or** the MAC equals `GlobalVar.bleDeviceAddress`. Both values come from the app backend (RN calls `setBleName` / `setBleAddress`).
3. If not bonded → `createBond()`, wait for `BOND_BONDED`, then `connectGatt()`.
4. `connectGatt(context, autoConnect=true, callback)`.
5. On `STATE_CONNECTED`: request `CONNECTION_PRIORITY_BALANCED`, then `discoverServices()`.
6. On services discovered: map characteristics by UUID, then `requestMtu(256)`.
7. On `onMtuChanged` (status 0): set `isMTUIncreased = true`, then send the **general packet** (`pushGeneralStatusIfReady`).
8. `enableControlNotifications(CONTROL_CHAR)` → `setCharacteristicNotification(true)` + write CCCD `ENABLE_NOTIFICATION_VALUE`.
9. Writes are serialized through a queue; 48-byte TBT frames fit in one PDU (MTU 256 → 253-byte payload).

> **Critical for any reimplementation:** the app refuses to write anything until `isMTUIncreased` is true (`prepareCharAndWrite` early-returns). You must negotiate MTU first.

Write type is chosen per characteristic (`writeTypeForCharacteristic`): default-write (`2`) if the char advertises WRITE, otherwise no-response (`1`).

### 1.3 Scan/name matching

`resolveScanDeviceName()` tries, in order: `device.getName()`, `scanRecord.getDeviceName()`, the raw AD bytes, then the bonded cache. `VehicleType.fromDeviceName()` only distinguishes `PULSAR` vs `UNKNOWN` (everything non-empty is `PULSAR`). The actual brand/model string comes from the app's server.

---

## 2. Turn-by-turn frame (`TBT_INFO_CHAR`, 48 bytes)

Built by `com.bajajconnect.utils.TbtNavFrame.buildNavHelperFrame(...)`
(and the Mappls path `NavigationHelper.sendNavigationData()` — identical layout).

```
off  size  field
 0    1    flags: bit0 = frame type (1 = nav update, 0 = nav stop)
                  bits4-7 = distance unit for STEP distance (1 = metres, 0 = km)
                  bit7 = 1 if ETA is PM, 0 if AM
 1    1    maneuver icon id (see §3)
 2-5  4    STEP distance to next maneuver, little-endian 16.16 fixed point
 6    1    ETA minute (0-59)
 7    1    ETA hour (1-12) | (roundabout exit number << 4)
 8-11 4    TOTAL distance to destination, little-endian 16.16 fixed point
12    1    bit0 = total-distance unit (1 = metres, 0 = km)
            bits2-3 = GPS status (0 not active, 1 active, 2 searching)
13    1    takeMeHome ack
14    1    street/instruction text length (max 31, ASCII alnum + '.')
15-45 31   street/instruction text
46    1    unused (0)
47    1    checksum = sum(bytes[0..46]) & 0xFF
```

Distance encoding (`NavigationHelper.formatDistance(double)`):
- `d < 1000` → value in **metres**, unit bit = 1
- `d >= 1000` → value in **km**, unit bit = 0, two decimal places (10 m resolution)
- encoded as a 16.16 fixed-point `(intPart << 16) | hundredths`, stored little-endian in the 4 bytes.

Nav start / stop control frames:
- start: `buildNavHelperFrame(1, STRAIGHT(71), true, 0,0,0,0,0, "")`
- stop:  `buildNavHelperFrame(0, 0, true, 0,0,0,0,0, "")` (all-zero type frame)
- destination reached: `buildNavHelperFrame(1, DESTINATION_REACHED(72), …)`
- Google path sends every **800 ms**; Mappls path ~1000 ms; arrival threshold is **80 m**.

---

## 3. Maneuver icon IDs (`PrimaryTurns`)

The cluster renders a glyph from byte 1. Values 66–90 are solid, 98–122 are the "blinking" variants (used when the maneuver is < 100 m away).

| Icon | Meaning | | Icon | Meaning |
|---|---|---|---|---|
| 66 | wrong way | | 79 | U-turn left |
| 67 | slight left | | 80 | U-turn right |
| 68 | slight right | | 81 | fork left |
| 69 | sharp left | | 82 | fork right |
| 70 | sharp right | | 84 | ferry-train |
| 71 | straight | | 85 | roundabout left |
| 72 | destination reached | | 86 | merge |
| 73 | turn left | | 88 | keep right |
| 74 | turn right | | 89 | ferry |
| 75 | ramp left | | 90 | keep left |
| 76 | ramp right | | 78 | roundabout right |

Blinking variants = value + 32 (e.g. straight 71 ↔ 103).
Roundabout exit number goes in the high nibble of byte 7 (1–7); Google roundabout maneuvers (43–62) are mapped through `TbtNavFrame.googleRoundaboutSeverityToExitAngle` and the clockwise/anti-clockwise band.

### 3.1 Google maneuver → icon (`TbtNavFrame.mapGoogleManeuver`)

Google Navigation SDK `StepInfo.getManeuver()` values map as: 1/5/65→straight(71), 2/3/4→destination(72), 6→left(73), 7→right(74), 8→keep-left(90), 9→keep-right(88), 10→slight-left(67), 11→slight-right(68), 12→sharp-left(69), 13→sharp-right(70), 14→U-turn-right(80), 15→U-turn-left(79), 16-18→merge(86), 19→fork-left(81), 20→fork-right(82), 21/32→straight, 22/33→left, 23/34→right, 26/37→slight-left, 27/38→slight-right, 28/39→sharp-left, 29/40→sharp-right, 43-62→roundabout (L/R), 63→ferry, 64→ferry-train. Unknown → wrong-way(66).

---

## 4. General status packet (`GENERAL_CHAR`, 89 bytes)

Built by `NewGeneralPacketV2.build()` for "V2" SKUs, else the legacy `utils.CallFrame.phoneStatusNew()`.
Sent after MTU negotiation, on screen-on, and to refresh state.

- `[0]` = volume(low nibble) | headset(bit4) | 0xC0
- `[1]` = dnd(bit7) | battery level 0-3 (bits4-5) | call state (low nibble)
- `[2]` = signal 0-7 (bits0-2) | birthday day (bits3-7)
- `[3]` = birthday month
- `[4..17]` = acks: callAccept, callReject, callRejectWithSms, resumeSong, pauseSong, skipNext, skipPrev, stopSong, launchMediaPlayer, missedCallCount, missedCallGetAck, smsCount, alertsGetAck, selectedPlaylistSongAck
- `[18]` = 1 if a call is active; `[19]` = caller-name length; `[20..51]` = caller name/UTF-8 (max 32)
- `[52..53]` = call progress counter (LE)
- `[54]` = heartbeat (increments each build)
- `[55]` = phone model length; `[56..87]` = phone model UTF-8 (max 32)
- `[88]` = checksum (sum of 0..87) & 0xFF

The cluster likely needs this packet for the link to be considered healthy and for its status icons; a reimplementation should send at least a valid general packet on connect and periodically.

### 4.1 Alerts packet (`ALERTS_INFO_CHAR` = `0410676e-…`, 44 bytes)

Built by `ble.protocol.NewAlertsPacketV2.build()`; the legacy variant is `utils.CallFrame.alertsFrame()`.
Only two kinds exist — `enums.Alert`: **SMS(1)** and **WAPP(2)**. There is no "all notifications" mode.

- `[0]` = alert type (1 SMS, 2 WhatsApp)
- `[1]` = sender length (max 32)
- `[2..33]` = sender UTF-8
- `[34..37]` = epoch seconds, big-endian
- `[38..43]` = zero padding

The body is never sent: the cluster's field is 32 bytes and the official app only ever writes the sender.
No checksum. The cluster counts these in general packet `[15]` and re-pulls the last body when the rider opens the list.
Ride Relay does not implement this packet — it only mirrors calls — so its general packet keeps `[15]`/`[16]` at zero.

### 4.2 Missed call packet (`MISSED_CALL_CHAR` = `0310676e-…`, 63 bytes)

Built by `ble.protocol.NewMissedCallPacketV2.build()`.

- `[0]` = reserved id, always 1
- `[1]` = name length (0 when the name is empty or equal to the number)
- `[2..33]` = name UTF-8 (max 32)
- `[34]` = number length (max 18)
- `[35..52]` = number UTF-8
- `[53..56]` = epoch seconds, big-endian
- `[57..62]` = zero padding

No checksum. Counted in general packet `[13]`.

### 4.3 Control characteristic (`CONTROL` = `0a10676e-…`)

This cluster exposes it with **props 0x02 (READ only)** and no CCCD, so a `NotificationListenerService`-style
subscription is impossible. The official app reads it on a 700 ms timer and compares each sample with the previous
one; the first sample only seeds the state because several bytes are sticky.

| Byte | Meaning |
|---|---|
| 0 | bits1-4 volume nibble | bits6-7 dial source (0 none, 1 favourites, 2 recent, 3 missed) |
| 1 | call accept | 2 call reject | 3 call reject with SMS |
| 4 | bits1-6 playlist page | bits7-8 new playlist request |
| 5 | take me home | 6 resume | 7 pause | 8 next | 9 previous | 10 stop |
| 11 | missed-call get | 12 alert get | 13 launch media player |
| 14 | playlist song request | 15 selected playlist song | 16 dial index | 17 dial transaction |

"call accept" / "reject" / "alert get" are counters: a change *edge* is the key press, and the app echoes the
new value back in the matching ack byte of the general packet.

### 4.4 Call states (`enums.CallState`)

`NO_CALL(0)`, `INCOMING_CALL(1)`, `OUTGOING_CALL(2)`, `ACTIVE_CALL(3)`, `END_CALL(4)` — the low nibble of general
packet `[1]`. The caller name is resolved from the dialer's own notification (Person extras, then title/text/bigText),
because `incomingNumber` from `PhoneStateListener` is empty without `READ_CALL_LOG` on modern Android.

### 4.5 What the official trip log is (and is not)

There is **no telemetry characteristic**. The only bike→phone data in the whole app is `CONTROL`.
`syncRecentTripsForCluster()` is not a ride log — it pushes recent *destinations* (name/lat/lng) for the WiFi
NavV2 cluster. Trip history comes from Bajaj's backend (`storeTripDetails`, Firestore/HTTP in the RN bundle), so a
third-party app cannot reproduce it from the bike; Ride Relay records its own rides from GPS instead.

---

## 5. How the app picks Google vs Bajaj (Mappls) navigation

The app ships **both** navigation engines (identical TBT output):

- Default: **Mappls / MapmyIndia** (`com.mappls.sdk.*`, `com.bajajconnect.navigate.HomeActivity`) → `AdviseInfo` → `NavigationHelper.sendNavigationData()`.
- Optional: **Google Navigation SDK** (`com.google.android.libraries.navigation`, `com.google.android.libraries.mapsplatform.turnbyturn`) → `com.bajajconnect.gMaps.NavigationActivity` → `NavInfo` → `TbtNavFrame.fromGoogleNavInfo()`.

Selection is a single global flag, `GlobalVar.isGoogleNavigation`:

- `NavigationManager.setModelCode(sku)` and `setSkuCohorts(...)` (React methods) set it from
  `VehicleFeature.MAP_GOOGLE_NAV.isEnabledFor(sku)`.
- `NavigationManager.setNavigationType(boolean)` (React method) sets it directly.
- `SkuFeatureRegistry` enables `MAP_GOOGLE_NAV` (and `BLE_TBT`, `APP_NAVIGATION`, `WIFI_CLUSTER_CONNECT`, …) only for the `CLUSTER_V1` cohort. Built-in default SKUs:

  ```
  CLUSTER_V1 (14): 00JR54 00JRGF 00JRGG 00JR66 00JF63 00JL31 00DT23
                   00JL34 00JF68 00JF69 00JF64 00JFNK 00JR73 00JR90
  BLE_ONLY_GENERAL_V2 (5): 00JR88 00JR72 00JR71 00JR68 00JR91
  ```

  The app also pulls live cohort lists from its backend via `setSkuCohorts`.

When `isGoogleNavigation` is true, the TBT service (`BleService.onNavigationProgress(AdviseInfo, NavInfo)`) uses the Google `NavInfo` and `startGoogleTbtScheduler()` (800 ms). Google nav updates are delivered by `NavInfoReceivingService`, registered with `Navigator.registerServiceForNavUpdates(packageName, service, Integer.MAX_VALUE)`, received by `TurnByTurnManager.readNavInfoFromBundle()`.

Notes:
- `isBleTbtEnabled()` = `BLE_TBT` feature (or legacy, when `BLE_GENERAL_PACKET_V2` is off).
- `isAppNavigationEnabled()` = `APP_NAVIGATION` feature.
- A Google Maps API key is baked into the official app (string resource `GOOGLE_MAPS_API_KEY`)
  and handed to `GmapUtil` for reverse geocoding. It is package- and signature-restricted,
  belongs to Bajaj, and is deliberately **not reproduced here**. Ride Relay does not need
  or use a Maps API key at all.
- The app advertises BLE pairing + turn-by-turn, so the Google engine is a first-class, already-wired path — not a stub.

---

## 6. Practical options to get "Google Maps directions on the cluster"

1. **Use the app's built-in Google engine** (if the bike's SKU is in `CLUSTER_V1`, or once the backend puts it there). Nothing to build; pick Google navigation in the app.
2. **Flip the flag at runtime without re-signing** (rooted phone, Frida/LSPosed):
   force `SkuFeatureRegistry.isEnabled(MAP_GOOGLE_NAV, sku)` → true, or call
   `NavigationManager.setNavigationType(true)`. Keeps the original signature, so the
   Google Navigation SDK licence/API-key restriction still passes.
3. **Reimplementation / companion app** that owns the BLE link and writes the 48-byte
   frames itself, sourcing directions from Google. Patching + re-signing the APK is *not*
   a good route: the changed signature usually breaks the Maps/Navigation key restriction.
   Direction sources, cheapest first:
   - Google Maps app **notification listener** (no API key; parse the ongoing navigation
     notification's maneuver/distance/ETA) → build frames.
   - Google **Directions API** + Android GPS + step-advance logic (needs your own key) →
     build frames.
   - Google Navigation SDK (licensed, paid).

Either way the wire format is now fully known: 48-byte frame to `0110676e-…`, plus an
89-byte general packet to `0210676e-…` for link health.

---

## 7. How the engine choice actually resolves (JS layer)

Decompiling the Hermes bundle (`hbc-disassembler`, hermes-dec; bundle is v96) shows the
decision is made **in JavaScript**, not native:

- `VEHICLE_FEATURES` / `MAP_PROVIDER = { google, mappls }`.
- `skuFromBike(bike)` = `normalizeSku(bike.materialSku || bike.ModelColour || '')`.
- `skuMatchesRegisteredValue(sku, registered)` = `startsWith` when the registered value is
  6 chars, else equality.
- `isFeatureEnabledForBike(feature, bike)` = cohort membership.
- `isGoogleMapProvider(bike)` = `isFeatureEnabledForBike(VEHICLE_FEATURES.MAP_GOOGLE_NAV, bike)`
  (Hermes function #21827).
- `syncNativeSkuGatesForBike(bike)` logs
  `{ sku, googleNav, os }` via `logApp('System', 'syncNativeSkuGatesForBike', …)`, then calls
  `NativeModules.RNNavigationManager.setModelCode(sku)` and
  `setNavigationType(googleNav)`.
- In `startNavigation` (screen flow, Hermes function #23682) the branch is
  `if (useGoogleMap) startNavigationActivity(...) else startNavigationActivityMMI(...)`
  and it logs `console.warn('[Nav][Flow][JS] startNavigation called', {platform, useGoogleMap, theme, destinationCount})`.
  `setCurrentLocation(lat,lng)` sets `GlobalVar.lat/lng`.

Native `NavigationManager`:
- `startNavigationActivity(lat,lng,name,addr)` → Google `NavigationActivity` (+ `GoogleNavSdkGate`).
- `startNavigationActivityMMI(pin,name,addr)` → Mappls `HomeActivity`.
- `navigateToPoint(lat,lng,name)` → Mappls `HomeActivity`.
- All three are gated by `isAppNavigationEnabledForCurrentSku()` =
  `!BLE_GENERAL_PACKET_V2.isEnabledFor(sku) || APP_NAVIGATION.isEnabledFor(sku)`.

Consequence: because the engine is chosen in JS from the bike's `materialSku` cohort,
a native-only patch is **not** sufficient (JS would still call the Mappls methods). The
clean, SKU-independent lever is to patch `isGoogleMapProvider` to return `true`.

### 7.1 Implemented patch (see `re-work/patched/`)

`isGoogleMapProvider` at bundle offset `0x4ba51f` becomes `LoadConstTrue r0; Ret r0`
(bytes `78 00 5c 00`, opcodes from `hermes_dec` HBC v96 table), and the trailing Hermes
SHA-1 is recomputed. Result: `useGoogleMap=true` for any `materialSku`, so the app launches
the Google Navigation SDK and feeds the cluster from Google `NavInfo`. Built APK:
`re-work/patched/rideconnect-google-aligned-debugSigned.apk`
(SHA-256 `61aebe55…43ad0`, debug-signed v2+v3). Caveat: re-signing may invalidate Bajaj's
signature-restricted Google Maps/Navigation API key.

---

## 8. Reading Google Maps navigation from its notification (relay app)

Verified on a Samsung Galaxy A53 (Android 16, SDK 36) with Google Maps
`base.apk` 78,884,127 bytes, 2026-08-07, while navigating.

### 8.1 Notification shape

The ongoing nav notification has `category=navigation`, `flags=0x16a`, and these extras:

| extra | value | meaning |
|---|---|---|
| `android.title` | `20 m`, `130 m` | distance to the next maneuver |
| `android.text` | `Turn left`, `Head east`, `towards MG Road` | instruction / next road |
| `android.subText` | `6 hr 17 min · 322 km · 4:57 am ETA` | duration · total distance · arrival clock |
| `android.largeIcon` | `Icon(typ=BITMAP size=91x91)` | **the maneuver arrow** |
| `android.ongoingActivityNoti.*` | Samsung One UI chips | `chipIcon`/`nowbarIcon`/`secondIcon` = 135x135 tinted white, `primaryInfo`/`secondaryInfo` duplicate title/text |
| `android.progress`, `android.progressMax` | `0`, `0` | unused |

There is **no maneuver-name field** — the direction exists only in the icon bitmap.
`android.text` is ambiguous: actual turns read `Turn left`/`Turn right`, but
straight/merge steps read `Head east` or `towards <road>` (no direction word).

### 8.2 Recovering the maneuver

Google Maps ships its maneuver icons as **named resources**, readable from another
app via `createPackageContext("com.google.android.apps.maps").getResources()`:

```
maneuver_straight, maneuver_depart, maneuver_name_change,
maneuver_destination[_left|_right|_straight],
maneuver_turn_normal_left|right, maneuver_turn_slight_left|right,
maneuver_turn_sharp_left|right, maneuver_u_turn_left|right,
maneuver_keep_left|right, maneuver_fork_left|right, maneuver_merge[_left|_right],
maneuver_on_ramp_* / maneuver_off_ramp_* (keep|normal|slight|sharp|u_turn × left|right),
maneuver_roundabout_enter|exit|enter_and_exit_ccw|cw[_normal|_slight|_sharp|_straight|_u_turn …]
```

64 of these resolve on the test device. `ManeuverMatcher` renders each drawable,
crops to its foreground bounding box, resamples to a 28x28 boolean grid, and XORs
against the notification icon normalised the same way. Bounding-box normalisation
makes the comparison immune to icon size, padding, tint and aspect-ratio differences,
so no colour or framing matching is needed.

Measured: `maneuver_turn_normal_left` 98 %, `maneuver_depart` 97 %. Explicit
direction words in `android.text` (when present) take precedence over the icon match;
otherwise the icon decides; otherwise straight.

### 8.3 Distance / ETA

- step distance = first `(\d+(?:[.,]\d+)?)\s*(km|mi|ft|yd|m)\b` in `android.title`
- total distance = same regex over `android.subText` (`6 hr 17 min` does not match: `min`
  fails the word boundary after `m`/`mi`)
- arrival clock = first `(\d{1,2}):(\d{2})(?:\s*([ap]m))?` in `android.subText` (transmit
  this directly instead of computing now+duration)
- remaining time = `(\d+)\s*(hr|hour)` × 3600 + `(\d+)\s*min` × 60

### 8.4 Android-side gotchas found while testing

- **Package visibility (API 30+):** `createPackageContext` needs
  `<queries><package android:name="com.google.android.apps.maps"/></queries>` in the manifest.
- **`Icon.getBitmap()` is not public API** — use `Icon.loadDrawable(context)` and render it.
- **`startForeground(…, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)` throws
  `SecurityException`** if `BLUETOOTH_CONNECT` is not yet granted; retry after the
  permission result and on each service tick.
- The bike's `0a10676e-…` control characteristic reports `props=0x2` (READ only, no CCCD)
  on the NS200 cluster, so control events must be polled, not subscribed.

### 8.5 Observed BLE handshake (test device, bike `38:E2:C4:23:C8:5D` "PULSAR0669")

Services: `0010676e-…` (chars `0110676e` WRITE, `0210676e` WRITE, `0310676e`, `0410676e`,
`0510676e` WRITE, `0a10676e` READ), a second `0020676e-…` service (`1020676e` WRITE,
`1120676e` READ) and `f000ffd0-0451-4000-b000-000000000000` (`f000ffd1` READ|WRITE).
MTU negotiated to **247** (requested 256) — ample for the 48-byte frame.
