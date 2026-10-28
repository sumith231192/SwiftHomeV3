package com.example.swifthome;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Minimal Bluetooth LE client for "LEDDMX-03" style strips (the ones driven by the LED LAMP / LED DMX phone app).
 * Protocol read from the app: GATT service FFE0, characteristic FFE1, raw bytes.
 * Colour frame: 7B FF 07 R G B FF FF BF.  Only this one frame is ever written.
 */
@SuppressLint({"MissingPermission", "NewApi"})
public class LedController {

    public interface Listener {
        void onStatus(String status);
        void onFound(String name, String address);
        void onScanDone();
    }

    private static final UUID SVC = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHR = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> seen = new HashSet<>();
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCb;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic chr;

    public LedController(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    private boolean ensureAdapter() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            status("Bluetooth is off or not available on this TV");
            return false;
        }
        return true;
    }

    private void status(final String s) {
        main.post(() -> listener.onStatus(s));
    }

    public boolean isReady() {
        return chr != null && gatt != null;
    }

    public void scan() {
        try {
            if (!ensureAdapter()) return;
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                status("Bluetooth LE scanning is not available");
                return;
            }
            seen.clear();
            scanCb = new ScanCallback() {
                @Override
                public void onScanResult(int type, ScanResult r) {
                    final String n = r.getDevice().getName();
                    final String a = r.getDevice().getAddress();
                    if (n != null && n.toUpperCase(Locale.ROOT).startsWith("LED") && seen.add(a)) {
                        main.post(() -> listener.onFound(n, a));
                    }
                }

                @Override
                public void onScanFailed(int code) {
                    status("Scan failed (" + code + ")");
                }
            };
            scanner.startScan(scanCb);
            status("Scanning for LED strips...");
            main.postDelayed(this::stopScan, 8000);
        } catch (RuntimeException e) {
            status("Bluetooth permission or hardware problem");
        }
    }

    public void stopScan() {
        if (scanner != null && scanCb != null) {
            try {
                scanner.stopScan(scanCb);
            } catch (RuntimeException ignored) {
            }
            scanCb = null;
            main.post(listener::onScanDone);
        }
    }

    public void connect(String address) {
        try {
            if (!ensureAdapter()) return;
            close();
            BluetoothDevice dev = adapter.getRemoteDevice(address);
            status("Connecting...");
            if (Build.VERSION.SDK_INT >= 23) {
                gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = dev.connectGatt(ctx, false, cb);
            }
        } catch (RuntimeException e) {
            status("Could not connect (permission?)");
        }
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int st, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status("Connected, finding service...");
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                chr = null;
                status("Disconnected");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int st) {
            BluetoothGattService s = g.getService(SVC);
            chr = (s == null) ? null : s.getCharacteristic(CHR);
            status(chr != null ? "Strip ready" : "Connected, but not an FFE0/FFE1 strip");
        }
    };

    /** Sends one colour frame. Returns false if not connected. */
    public boolean setColor(int r, int g, int b) {
        if (gatt == null || chr == null) return false;
        byte[] frame = new byte[]{
                0x7B, (byte) 0xFF, 0x07,
                (byte) clamp(r), (byte) clamp(g), (byte) clamp(b),
                (byte) 0xFF, (byte) 0xFF, (byte) 0xBF};
        try {
            chr.setValue(frame);
            return gatt.writeCharacteristic(chr);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    public void close() {
        stopScan();
        chr = null;
        if (gatt != null) {
            try {
                gatt.close();
            } catch (RuntimeException ignored) {
            }
            gatt = null;
        }
    }
}
