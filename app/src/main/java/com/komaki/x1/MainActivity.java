package com.komaki.x1;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQ_CODE = 1001;
    private static final int ENABLE_BT_REQ_CODE = 1002;
    private static final String TARGET_URL = "https://ais-pre-x73nfmvp3yaq2ao2yzxq54-863264071083.asia-northeast1.run.app";

    private static final UUID SVC_FF00 = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb");
    private static final UUID CHR_FF01 = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb");
    private static final UUID CHR_FF02 = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb");
    private static final UUID SVC_FFE0 = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHR_FFE1 = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID SVC_CUSTOM = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1912");
    private static final UUID CHR_CUSTOM = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d2b12");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner leScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic rxChar;
    private BluetoothGattCharacteristic txChar;
    private BluetoothGattCharacteristic customChar;

    private final Map<String, BluetoothDevice> discoveredDevices = new LinkedHashMap<>();
    private final List<String> deviceLabels = new ArrayList<>();
    private ArrayAdapter<String> dialogAdapter;
    private AlertDialog scanDialog;
    private boolean isScanning = false;
    private boolean pendingScanAfterPermission = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        BluetoothManager btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (btManager != null) {
            bluetoothAdapter = btManager.getAdapter();
        }

        webView = findViewById(R.id.webView);
        configureWebView();
        checkPermissions(false);
        webView.loadUrl(TARGET_URL);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.addJavascriptInterface(new BleBridge(), "AndroidBle");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void checkPermissions(boolean startScanWhenGranted) {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        if (!needed.isEmpty()) {
            pendingScanAfterPermission = startScanWhenGranted;
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQ_CODE);
        } else if (startScanWhenGranted) {
            startBleScanDialog();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE && pendingScanAfterPermission) {
            pendingScanAfterPermission = false;
            if (hasBlePermissions()) {
                startBleScanDialog();
            } else {
                emitBleError("Bluetooth permission was denied. Please allow Nearby Devices / Location permission in Android settings.");
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void startBleScanDialog() {
        if (bluetoothAdapter == null) {
            emitBleError("Bluetooth hardware is not available on this device.");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, ENABLE_BT_REQ_CODE);
            emitBleError("Please turn on Bluetooth and tap Connect again.");
            return;
        }

        discoveredDevices.clear();
        deviceLabels.clear();
        dialogAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceLabels);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Komaki X1 BMS");
        builder.setAdapter(dialogAdapter, (dialog, which) -> {
            stopBleScan();
            List<BluetoothDevice> devs = new ArrayList<>(discoveredDevices.values());
            if (which >= 0 && which < devs.size()) {
                connectToDevice(devs.get(which));
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> {
            stopBleScan();
            emitBleError("Bluetooth pairing cancelled.");
        });
        builder.setOnCancelListener(dialog -> stopBleScan());
        scanDialog = builder.create();
        scanDialog.show();

        leScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (leScanner != null) {
            isScanning = true;
            leScanner.startScan(scanCallback);
            mainHandler.postDelayed(this::stopBleScan, 15000);
        }
    }

    @SuppressLint("MissingPermission")
    private void stopBleScan() {
        if (isScanning && leScanner != null && hasBlePermissions()) {
            try {
                leScanner.stopScan(scanCallback);
            } catch (Exception ignored) {}
        }
        isScanning = false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null) return;
            String addr = device.getAddress();
            if (!discoveredDevices.containsKey(addr)) {
                String name = device.getName();
                if (name == null || name.trim().isEmpty()) {
                    name = "BLE Device";
                }
                discoveredDevices.put(addr, device);
                String label = name + " (" + addr + ")";
                mainHandler.post(() -> {
                    deviceLabels.add(label);
                    if (dialogAdapter != null) dialogAdapter.notifyDataSetChanged();
                });
            }
        }
    };

    @SuppressLint("MissingPermission")
    private void connectToDevice(BluetoothDevice device) {
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.close();
            } catch (Exception ignored) {}
            bluetoothGatt = null;
        }
        rxChar = null;
        txChar = null;
        customChar = null;
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                rxChar = null;
                txChar = null;
                customChar = null;
                mainHandler.post(() -> webView.evaluateJavascript(
                    "window.__onAndroidBleDisconnected && window.__onAndroidBleDisconnected();",
                    null
                ));
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitBleError("Service discovery failed (status " + status + ")");
                return;
            }
            BluetoothGattService svc = gatt.getService(SVC_FF00);
            if (svc != null) {
                rxChar = svc.getCharacteristic(CHR_FF01);
                txChar = svc.getCharacteristic(CHR_FF02);
            } else {
                svc = gatt.getService(SVC_FFE0);
                if (svc != null) {
                    rxChar = svc.getCharacteristic(CHR_FFE1);
                    txChar = svc.getCharacteristic(CHR_FFE1);
                }
            }

            BluetoothGattService customSvc = gatt.getService(SVC_CUSTOM);
            if (customSvc != null) {
                customChar = customSvc.getCharacteristic(CHR_CUSTOM);
            }

            if (rxChar == null) {
                emitBleError("JBD BMS service (FF00/FFE0) not found on this device.");
                gatt.disconnect();
                return;
            }

            gatt.setCharacteristicNotification(rxChar, true);
            BluetoothGattDescriptor cccd = rxChar.getDescriptor(CCCD_UUID);
            if (cccd != null) {
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(cccd);
            }

            String devName = gatt.getDevice() != null && gatt.getDevice().getName() != null
                ? gatt.getDevice().getName().replace("'", "")
                : "Komaki X1 BMS";
            String summary = "Service: Active\nRX Notify: Found & Active\nTX Write: "
                + (txChar != null ? "Found & Active" : "Not Present")
                + "\nCustom 2B12: " + (customChar != null ? "Found" : "Not Present");
            final String safeSummary = summary.replace("\n", "\\n");
            mainHandler.post(() -> webView.evaluateJavascript(
                "window.__onAndroidBleConnected && window.__onAndroidBleConnected('" + devName + "', '" + safeSummary + "');",
                null
            ));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value != null) {
                dispatchBytes(value);
            }
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
            dispatchBytes(value);
        }

        private void dispatchBytes(byte[] value) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < value.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(value[i] & 0xFF);
            }
            sb.append("]");
            final String arr = sb.toString();
            mainHandler.post(() -> webView.evaluateJavascript(
                "window.__onAndroidBleData && window.__onAndroidBleData(" + arr + ");",
                null
            ));
        }
    };

    private void emitBleError(String msg) {
        final String safeMsg = msg.replace("'", "\\'");
        mainHandler.post(() -> webView.evaluateJavascript(
            "window.__onAndroidBleError && window.__onAndroidBleError('" + safeMsg + "');",
            null
        ));
    }

    public class BleBridge {
        @JavascriptInterface
        public boolean isSupported() {
            return bluetoothAdapter != null;
        }

        @JavascriptInterface
        public void startScanAndConnect() {
            mainHandler.post(() -> checkPermissions(true));
        }

        @SuppressLint("MissingPermission")
        @JavascriptInterface
        public void disconnect() {
            mainHandler.post(() -> {
                if (bluetoothGatt != null && hasBlePermissions()) {
                    bluetoothGatt.disconnect();
                }
            });
        }

        @SuppressLint("MissingPermission")
        @JavascriptInterface
        public boolean writeHex(String hexStr, String target) {
            BluetoothGattCharacteristic targetChar = "cu".equals(target) ? customChar : txChar;
            if (bluetoothGatt == null || targetChar == null || !hasBlePermissions()) {
                return false;
            }
            String clean = hexStr.replaceAll("[^0-9a-fA-F]", "");
            if (clean.length() < 2 || clean.length() % 2 != 0) return false;
            byte[] data = new byte[clean.length() / 2];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
            }
            targetChar.setValue(data);
            int props = targetChar.getProperties();
            if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                targetChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } else {
                targetChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            }
            return bluetoothGatt.writeCharacteristic(targetChar);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        stopBleScan();
        if (bluetoothGatt != null && hasBlePermissions()) {
            try {
                bluetoothGatt.close();
            } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
