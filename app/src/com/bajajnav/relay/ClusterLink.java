package com.bajajnav.relay;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Owns the BLE GATT link to the bike cluster and writes frames.
 *
 * Handshake mirrors com.bajajconnect.ble.BleService:
 *   connect -> discoverServices -> requestMtu(256) -> enable CONTROL notifications
 *   -> (ready) send general status + TBT frames.
 */
@SuppressLint("MissingPermission")
final class ClusterLink {
    interface Listener {
        void onDevice(String name, String address, int rssi);

        void onConnection(boolean connected, String address, String name);

        void onStatus(String status);
    }

    static final UUID U_SVC = UUID.fromString("0010676e-6972-6565-6e69-676e4543544f");
    static final UUID U_TBT = UUID.fromString("0110676e-6972-6565-6e69-676e4543544f");
    static final UUID U_GEN = UUID.fromString("0210676e-6972-6565-6e69-676e4543544f");
    static final UUID U_CTL = UUID.fromString("0a10676e-6972-6565-6e69-676e4543544f");
    static final UUID U_MISSED = UUID.fromString("0310676e-6972-6565-6e69-676e4543544f");
    private static final UUID U_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /**
     * The official app reads the control characteristic instead of subscribing to
     * it: this cluster advertises READ only and has no CCCD. 700 ms is its
     * cadence, and it is the only way the cluster's buttons reach us.
     */
    private static final long CTL_POLL_MS = 700L;

    private static final ClusterLink INSTANCE = new ClusterLink();

    private Context ctx;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic tbtChar;
    private BluetoothGattCharacteristic genChar;
    private BluetoothGattCharacteristic ctlChar;
    private BluetoothGattCharacteristic missedChar;

    private final HandlerThread thread = new HandlerThread("cluster");
    private Handler h;
    private final List<Listener> listeners = new ArrayList<>();

    private volatile boolean scanning = false;
    private volatile boolean ready = false;
    private volatile boolean connected = false;
    private volatile boolean wantConnection = false;
    private volatile String wantAddress = null;
    private volatile String wantName = null;
    private volatile boolean gaveUp = false;
    /**
     * Consecutive failed attempts before auto-reconnect stops and waits for a
     * manual Connect. With the 15 s backoff cap this is roughly four minutes.
     */
    private static final int MAX_AUTO_RETRIES = 20;
    private int reconnectAttempt = 0;
    private volatile String pendingBondAddress;
    private BroadcastReceiver bondReceiver;

    private final Runnable reconnectTask = new Runnable() {
        @Override
        public void run() {
            doConnect();
        }
    };
    private final Runnable connectSettleTask = new Runnable() {
        @Override
        public void run() {
            connectNow();
        }
    };

    /** Polls the control characteristic while the link is up. */
    private final Runnable ctlPollTask = new Runnable() {
        @Override
        public void run() {
            if (!ready || ctlChar == null || gatt == null) {
                return;
            }
            // GATT runs one operation at a time: a read issued while a write is
            // in flight makes the write fail, which is how call packets get
            // lost. Only read when the write queue is idle.
            synchronized (queue) {
                if (!currentDone || !queue.isEmpty() || ctlReadPending) {
                    h.postDelayed(this, CTL_POLL_MS);
                    return;
                }
            }
            boolean ok;
            ctlReadPending = true;
            try {
                ok = gatt.readCharacteristic(ctlChar);
            } catch (Exception e) {
                ok = false;
            }
            if (!ok) {
                ctlReadPending = false;
            }
            h.postDelayed(this, CTL_POLL_MS);
        }
    };

    // write queue
    private static final class Wr {
        final BluetoothGattCharacteristic c;
        final byte[] v;
        final int type;
        int attempts;

        Wr(BluetoothGattCharacteristic c, byte[] v, int type) {
            this.c = c;
            this.v = v;
            this.type = type;
        }
    }

    private final Deque<Wr> queue = new ArrayDeque<>();
    private boolean currentDone = true;
    private volatile boolean ctlReadPending = false;

    private ClusterLink() {
    }

    static ClusterLink get() {
        return INSTANCE;
    }

    synchronized void init(Context context) {
        if (this.ctx != null) {
            return;
        }
        this.ctx = context.getApplicationContext();
        BluetoothManager bm = (BluetoothManager) this.ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm == null ? null : bm.getAdapter();
        thread.start();
        h = new Handler(thread.getLooper());

        bondReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(i.getAction())) {
                    return;
                }
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                if (d == null) {
                    return;
                }
                final String addr = d.getAddress();
                if (state == BluetoothDevice.BOND_BONDED) {
                    Lg.i("bonded " + addr);
                    emitStatus("Paired — connecting…");
                    if (pendingBondAddress != null && pendingBondAddress.equalsIgnoreCase(addr)) {
                        pendingBondAddress = null;
                        h.post(ClusterLink.this::doConnect);
                    }
                } else if (state == BluetoothDevice.BOND_BONDING) {
                    Lg.i("bonding " + addr);
                    emitStatus("Pairing… confirm on the phone");
                } else {
                    Lg.w("bond state " + state + " for " + addr);
                }
            }
        };
        IntentFilter bf = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                this.ctx.registerReceiver(bondReceiver, bf, Context.RECEIVER_NOT_EXPORTED);
            } else {
                this.ctx.registerReceiver(bondReceiver, bf);
            }
        } catch (Exception e) {
            Lg.w("bond receiver registration failed: " + e);
        }
    }

    void addListener(Listener l) {
        synchronized (listeners) {
            if (!listeners.contains(l)) {
                listeners.add(l);
            }
        }
    }

    void removeListener(Listener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    String getAddress() {
        return wantAddress;
    }

    String getName() {
        return wantName;
    }

    boolean isConnected() {
        return connected;
    }

    boolean isReady() {
        return ready;
    }

    boolean isScanning() {
        return scanning;
    }

    /** True when auto-reconnect gave up and is waiting for a manual Connect. */
    boolean hasGivenUp() {
        return gaveUp;
    }

    private void emitDevice(final String name, final String addr, final int rssi) {
        List<Listener> ls;
        synchronized (listeners) {
            ls = new ArrayList<>(listeners);
        }
        for (Listener l : ls) {
            l.onDevice(name, addr, rssi);
        }
    }

    private void emitConn(final boolean c, final String addr, final String name) {
        List<Listener> ls;
        synchronized (listeners) {
            ls = new ArrayList<>(listeners);
        }
        for (Listener l : ls) {
            l.onConnection(c, addr, name);
        }
    }

    private void emitStatus(final String s) {
        List<Listener> ls;
        synchronized (listeners) {
            ls = new ArrayList<>(listeners);
        }
        for (Listener l : ls) {
            l.onStatus(s);
        }
    }

    // ---------------------------------------------------------------- scanning

    @SuppressLint("MissingPermission")
    void startScan() {
        if (adapter == null) {
            Lg.w("no bluetooth adapter");
            return;
        }
        if (!adapter.isEnabled()) {
            Lg.w("bluetooth disabled");
            emitStatus("Bluetooth is off");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null || scanning) {
            return;
        }
        scanning = true;
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(null, settings, scanCb);
            emitStatus("Scanning…");
            Lg.i("scan started");
        } catch (Exception e) {
            scanning = false;
            Lg.w("scan start failed: " + e);
            emitStatus("Scan failed: " + e.getMessage());
        }
    }

    void stopScan() {
        if (scanner != null && scanning) {
            try {
                scanner.stopScan(scanCb);
            } catch (Exception ignored) {
            }
        }
        scanning = false;
        emitStatus("Scan stopped");
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            if (d == null) {
                return;
            }
            String name = null;
            try {
                name = d.getName();
            } catch (Exception ignored) {
            }
            if (name == null && result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            emitDevice(name, d.getAddress(), result.getRssi());
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            Lg.w("scan failed code=" + errorCode);
            emitStatus("Scan failed code " + errorCode);
        }
    };

    // -------------------------------------------------------------- connection

    void connect(String address, String name) {
        init(ctx);
        wantConnection = true;
        gaveUp = false;
        wantAddress = address;
        wantName = name;
        reconnectAttempt = 0;
        h.removeCallbacks(reconnectTask);
        h.removeCallbacks(connectSettleTask);
        Lg.i("connect requested " + address + " (" + name + ")");
        h.post(this::doConnect);
    }

    void disconnect() {
        wantConnection = false;
        gaveUp = false;
        h.removeCallbacks(reconnectTask);
        h.removeCallbacks(connectSettleTask);
        h.post(() -> {
            closeGatt();
            emitConn(false, wantAddress, wantName);
            emitStatus("Disconnected");
        });
    }

    private void doConnect() {
        if (adapter == null || wantAddress == null) {
            return;
        }
        // Always release the previous client first — reconnecting while an old
        // BluetoothGatt is still open makes the stack reject the new one.
        boolean hadGatt = gatt != null;
        closeGatt();
        if (hadGatt) {
            h.removeCallbacks(connectSettleTask);
            h.postDelayed(connectSettleTask, 250);
        } else {
            connectNow();
        }
    }

    private void connectNow() {
        if (!wantConnection || adapter == null || wantAddress == null) {
            return;
        }
        try {
            BluetoothDevice d = adapter.getRemoteDevice(wantAddress);
            int bond = d.getBondState();
            if (bond != BluetoothDevice.BOND_BONDED) {
                pendingBondAddress = wantAddress;
                emitStatus("Pairing with bike… confirm the prompt");
                Lg.i("bondState=" + bond + " -> createBond");
                try {
                    boolean started = d.createBond();
                    Lg.i("createBond -> " + started + " state=" + d.getBondState());
                } catch (Exception e) {
                    Lg.w("createBond failed: " + e);
                }
                return;
            }
            pendingBondAddress = null;
            emitStatus("Connecting to " + wantAddress + "…");
            gatt = d.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
            Lg.i("connectGatt -> " + (gatt != null));
        } catch (Exception e) {
            Lg.w("connectGatt failed: " + e);
            scheduleReconnect("connect exception");
        }
    }

    private void closeGatt() {
        ready = false;
        connected = false;
        tbtChar = null;
        genChar = null;
        ctlChar = null;
        missedChar = null;
        ctlReadPending = false;
        h.removeCallbacks(ctlPollTask);
        synchronized (queue) {
            queue.clear();
            currentDone = true;
        }
        if (gatt != null) {
            try {
                gatt.disconnect();
            } catch (Exception ignored) {
            }
            try {
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
    }

    private void scheduleReconnect(String why) {
        if (!wantConnection) {
            return;
        }
        reconnectAttempt++;
        if (reconnectAttempt > MAX_AUTO_RETRIES) {
            // Stop retrying on our own. It resumes only when the user taps Connect,
            // which keeps a parked/off bike from costing a connect attempt every 15 s.
            wantConnection = false;
            gaveUp = true;
            Lg.w("auto-connect gave up after " + reconnectAttempt + " attempts (" + why
                    + ") — tap Connect to retry");
            emitStatus("Idle — tap Connect to retry");
            emitConn(false, wantAddress, wantName);
            return;
        }
        long delay = Math.min(3000L * reconnectAttempt, 15000L);
        Lg.w("reconnect in " + delay + "ms (" + why + ")");
        emitStatus("Reconnecting in " + (delay / 1000) + "s…");
        h.removeCallbacks(reconnectTask);
        h.removeCallbacks(connectSettleTask);
        h.postDelayed(reconnectTask, delay);
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (g != gatt) {
                Lg.w("stale gatt callback ignored status=" + status + " newState=" + newState);
                return;
            }
            Lg.i("onConnectionStateChange status=" + status + " newState=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                emitConn(true, wantAddress, wantName);
                emitStatus("Connected — discovering services…");
                if (Build.VERSION.SDK_INT >= 35) {
                    try {
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
                        h.postDelayed(g::discoverServices, 350);
                    } catch (Exception e) {
                        g.discoverServices();
                    }
                } else {
                    g.discoverServices();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                boolean was = connected;
                connected = false;
                ready = false;
                tbtChar = null;
                genChar = null;
                ctlChar = null;
                missedChar = null;
                ctlReadPending = false;
                h.removeCallbacks(ctlPollTask);
                synchronized (queue) {
                    queue.clear();
                    currentDone = true;
                }
                if (was) {
                    emitConn(false, wantAddress, wantName);
                }
                // Release this client before any retry, then wait a beat so the
                // stack can settle before we create the next one.
                closeGatt();
                if (wantConnection) {
                    scheduleReconnect("gatt drop status=" + status);
                } else {
                    emitStatus("Disconnected");
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (g != gatt) {
                return;
            }
            Lg.i("onServicesDiscovered status=" + status);
            if (status != 0) {
                return;
            }
            listServices(g);
            tbtChar = find(g, U_TBT);
            genChar = find(g, U_GEN);
            ctlChar = find(g, U_CTL);
            missedChar = find(g, U_MISSED);
            Lg.i("chars tbt=" + (tbtChar != null) + " gen=" + (genChar != null)
                    + " ctl=" + (ctlChar != null)
                    + " missed=" + (missedChar != null));
            enableControl(g);
            try {
                boolean r = g.requestMtu(256);
                Lg.i("requestMtu(256) -> " + r);
            } catch (Exception e) {
                Lg.w("requestMtu failed: " + e);
            }
            h.postDelayed(() -> {
                if (connected && !ready) {
                    Lg.w("MTU callback missing — enabling writes anyway");
                    onReady("mtu-fallback");
                }
            }, 2500);
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (g != gatt) {
                return;
            }
            Lg.i("onMtuChanged mtu=" + mtu + " status=" + status);
            if (status == 0) {
                onReady("mtu=" + mtu);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (g != gatt) {
                return;
            }
            Lg.i("onDescriptorWrite status=" + status);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (g != gatt) {
                return;
            }
            ctlReadPending = false;
            if (status == 0 && c != null && c.getUuid().equals(U_CTL)) {
                Controls.process(c.getValue());
            } else if (status != 0) {
                Lg.w("onCharacteristicRead status=" + status + " uuid="
                        + (c == null ? "?" : c.getUuid()));
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c,
                                         byte[] value, int status) {
            if (g != gatt) {
                return;
            }
            ctlReadPending = false;
            if (status == 0 && c != null && c.getUuid().equals(U_CTL)) {
                Controls.process(value);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (g != gatt) {
                return;
            }
            if (status != 0) {
                Lg.w("onCharacteristicWrite status=" + status + " uuid=" + c.getUuid());
            }
            complete();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (g != gatt) {
                return;
            }
            Lg.i("ctl notify uuid=" + c.getUuid() + " val=" + TbtFrame.hex(c.getValue()));
            if (c.getUuid().equals(U_CTL)) {
                Controls.process(c.getValue());
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            if (g != gatt) {
                return;
            }
            Lg.i("ctl notify uuid=" + c.getUuid() + " val=" + TbtFrame.hex(value));
            if (c.getUuid().equals(U_CTL)) {
                Controls.process(value);
            }
        }
    };

    private void listServices(BluetoothGatt g) {
        try {
            for (android.bluetooth.BluetoothGattService s : g.getServices()) {
                StringBuilder sb = new StringBuilder("svc " + s.getUuid() + " chars:");
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    sb.append("\n    ").append(c.getUuid())
                            .append(" props=0x").append(Integer.toHexString(c.getProperties()));
                }
                Lg.i(sb.toString());
            }
        } catch (Exception e) {
            Lg.w("listServices failed: " + e);
        }
    }

    private BluetoothGattCharacteristic find(BluetoothGatt g, UUID uuid) {
        android.bluetooth.BluetoothGattService s = g.getService(U_SVC);
        if (s == null) {
            return null;
        }
        return s.getCharacteristic(uuid);
    }

    private void enableControl(BluetoothGatt g) {
        if (ctlChar == null) {
            return;
        }
        try {
            g.setCharacteristicNotification(ctlChar, true);
            BluetoothGattDescriptor d = ctlChar.getDescriptor(U_CCCD);
            if (d == null) {
                Lg.w("CONTROL has no CCCD");
                return;
            }
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            } else {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(d);
            }
        } catch (Exception e) {
            Lg.w("enableControl failed: " + e);
        }
    }

    private void onReady(String why) {
        ready = true;
        reconnectAttempt = 0;   // a healthy link resets the reconnect backoff
        gaveUp = false;
        Lg.i("link READY (" + why + ")");
        emitStatus("Ready — cluster link up");
        sendGeneralNow();
        h.removeCallbacks(ctlPollTask);
        h.postDelayed(ctlPollTask, CTL_POLL_MS);
    }

    // ------------------------------------------------------------------ writes

    private int writeType(BluetoothGattCharacteristic c) {
        int props = c.getProperties();
        boolean hasWrite = (props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
        boolean hasNoResp = (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
        if (hasWrite || !hasNoResp) {
            return BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        }
        return BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
    }

    private void enqueue(BluetoothGattCharacteristic c, byte[] v) {
        if (c == null || v == null) {
            return;
        }
        if (!ready) {
            Lg.w("write skipped (link not ready)");
            return;
        }
        synchronized (queue) {
            if (queue.size() > 12) {
                queue.pollFirst();
            }
            queue.addLast(new Wr(c, v, writeType(c)));
        }
        pump();
    }

    void sendTbt(byte[] frame) {
        enqueue(tbtChar, frame);
    }

    void sendGeneral(byte[] pkt) {
        enqueue(genChar, pkt);
    }

    /** 63-byte missed call, MISSED_CALL characteristic. */
    void sendMissedCall(byte[] pkt) {
        enqueue(missedChar, pkt);
    }

    /** Push a general packet right now (call state changed, toggle flipped, ...). */
    void sendGeneralNow() {
        int mode = RelayService.getGeneralMode();
        if (mode == GeneralPacket.MODE_OFF || !ready) {
            return;
        }
        sendGeneral(GeneralPacket.build(mode));
    }

    private void pump() {
        h.post(() -> {
            synchronized (queue) {
                if (!currentDone) {
                    return;
                }
                Wr w = queue.peekFirst();
                if (w == null) {
                    return;
                }
                currentDone = false;
                boolean ok = doWrite(w);
                if (!ok) {
                    currentDone = true;
                    if (w.attempts < 2) {
                        // Usually a control read landed in the same slot. Retry
                        // rather than drop a call packet.
                        w.attempts++;
                        h.postDelayed(this::pump, 150);
                        return;
                    }
                    queue.pollFirst();
                    pump();
                    return;
                }
                long timeout = w.type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE ? 60 : 600;
                h.postDelayed(this::complete, timeout);
            }
        });
    }

    private boolean doWrite(Wr w) {
        if (gatt == null) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                int r = gatt.writeCharacteristic(w.c, w.v, w.type);
                if (r != 0) {
                    Lg.w("writeCharacteristic -> " + r + " uuid=" + w.c.getUuid());
                    return false;
                }
                return true;
            } else {
                w.c.setWriteType(w.type);
                w.c.setValue(w.v);
                boolean r = gatt.writeCharacteristic(w.c);
                if (!r) {
                    Lg.w("writeCharacteristic false uuid=" + w.c.getUuid());
                }
                return r;
            }
        } catch (Exception e) {
            Lg.w("writeCharacteristic exception: " + e);
            return false;
        }
    }

    private void complete() {
        h.post(() -> {
            synchronized (queue) {
                if (currentDone) {
                    return;
                }
                currentDone = true;
                queue.pollFirst();
            }
            pump();
        });
    }

    /** Send a frame immediately, bypassing nav state (for test buttons). */
    void sendTestFrame(int maneuver, String text) {
        int icon = maneuver;
        byte[] f = TbtFrame.build(1, icon, true, 250, 4200, 12, 30, 0, text);
        Lg.i("TEST frame icon=" + icon + " " + TbtFrame.hex(f));
        sendTbt(f);
    }
}
