// Copyright (c) 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.apache.cordova.PluginResult.Status;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.uribeacon.scan.compat.BluetoothLeScannerCompat;
import org.uribeacon.scan.compat.BluetoothLeScannerCompatProvider;
import org.uribeacon.scan.compat.ScanCallback;
import org.uribeacon.scan.compat.ScanRecord;
import org.uribeacon.scan.compat.ScanResult;
import org.uribeacon.scan.compat.ScanSettings;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Base64;
import android.util.Log;


@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
public class ChromeBluetooth extends CordovaPlugin {

  private static final String LOG_TAG = "ChromeBluetooth";
  private static final int[] SUPPORTED_PROFILE = {
    BluetoothProfile.GATT, BluetoothProfile.GATT_SERVER
  };

  private Map<String, ScanResult> knownLeScanResults = new HashMap<String, ScanResult>();
  private Map<String, BluetoothDevice> knownBluetoothDevices =
      new ConcurrentHashMap<String, BluetoothDevice>();
  private Set<String> activeDevices = new HashSet<String>();

  private BluetoothManager bluetoothManager;
  private BluetoothAdapter bluetoothAdapter;
  private BluetoothLeScannerCompat leScanner;
  private boolean isLeScanning;
  private boolean isDiscovering = false;

  private CallbackContext bluetoothEventsCallback;

  @Override
  public boolean execute(String action, CordovaArgs args, final CallbackContext callbackContext)
      throws JSONException {
    if ("getAdapterState".equals(action)) {
      getAdapterState(callbackContext);
    } else if ("getDevice".equals(action)) {
      getDevice(args, callbackContext);
    } else if ("getDevices".equals(action)) {
      getDevices(callbackContext);
    } else if ("startDiscovery".equals(action)) {
      startDiscovery(args, callbackContext);
    } else if ("stopDiscovery".equals(action)) {
      stopDiscovery(callbackContext);
    } else if ("registerBluetoothEvents".equals(action)) {
      registerBluetoothEvents(callbackContext);
    } else {
      return false;
    }
    return true;
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    unregisterAdapterStateReceiver();
  }

  @Override
  protected void pluginInitialize() {
    registerAdapterStateReceiver();
    bluetoothManager = (BluetoothManager) webView.getContext().getSystemService(Context.BLUETOOTH_SERVICE);
    bluetoothAdapter = bluetoothManager.getAdapter();
    leScanner = BluetoothLeScannerCompatProvider.getBluetoothLeScannerCompat(webView.getContext());
    isLeScanning = false;
    enableBluetooth();
  }

  private void enableBluetooth() {
    if (bluetoothAdapter == null) {
      Log.e(LOG_TAG, "Bluetooth is not supported");
    } else if (!bluetoothAdapter.isEnabled()) {
      bluetoothAdapter.enable();
    }
  }

  // @Nullable
  ScanResult getKnownLeScanResults(String deviceAddress) {
    return knownLeScanResults.get(deviceAddress);
  }

  // @Nullable
  BluetoothDevice getKnownBluetoothDevice(String deviceAddress) {
    return knownBluetoothDevices.get(deviceAddress);
  }

  private JSONObject getAdapterStateInfo() throws JSONException {
    JSONObject stateInfo = new JSONObject();
    stateInfo.put("address", bluetoothAdapter.getAddress());
    stateInfo.put("name", bluetoothAdapter.getName());
    stateInfo.put("powered", bluetoothAdapter.getState() != BluetoothAdapter.STATE_OFF);
    stateInfo.put("available", bluetoothAdapter.isEnabled());
    stateInfo.put("discovering", isLeScanning);
    return stateInfo;
  }

  private void getAdapterState(CallbackContext callbackContext) throws JSONException {
    callbackContext.success(getAdapterStateInfo());
  }

  public boolean isConnected(BluetoothDevice device) {
    for (int profile : SUPPORTED_PROFILE) {
      if (bluetoothManager.getConnectedDevices(profile).contains(device))
        return true;
    }
    return false;
  }

  private JSONObject getBasicDeviceInfo(BluetoothDevice device) throws JSONException {
    JSONObject deviceInfo = new JSONObject();
    deviceInfo.put("address", device.getAddress());
    deviceInfo.put("name", device.getName());
    deviceInfo.put("deviceClass", device.getBluetoothClass().getDeviceClass());
    deviceInfo.put("paired", device.getBondState() == BluetoothDevice.BOND_BONDED);
    deviceInfo.put("connected", isConnected(device));
    deviceInfo.put("uuids", new JSONArray(getUuidStringsFromDevice(device)));
    return deviceInfo;
  }

  private Collection<String> getUuidStringsFromDevice(BluetoothDevice device) {
    Set<String> uuidStrings = new HashSet<String>();
    ParcelUuid[] uuids = device.getUuids();
    if (uuids != null) {
      for(ParcelUuid uuid : uuids) {
        uuidStrings.add(uuid.toString()) ;
      }
    }
    return uuidStrings;
  }

  private Collection<String> getUuidStringsFromLeScanRecord(ScanRecord scanRecord) {
    Set<String> uuidStrings = new HashSet<String>();
    List<ParcelUuid> uuids = scanRecord.getServiceUuids();
    if (uuids != null) {
      for(ParcelUuid uuid : uuids) {
        uuidStrings.add(uuid.toString());
      }
    }
    return uuidStrings;
  }

  private JSONObject getLeDeviceInfo(ScanResult leScanResult) throws JSONException {
    JSONObject deviceInfo = getBasicDeviceInfo(leScanResult.getDevice());
    Set<String> uuidStrings = new HashSet<String>();
    uuidStrings.addAll(getUuidStringsFromDevice(leScanResult.getDevice()));
    uuidStrings.addAll(getUuidStringsFromLeScanRecord(leScanResult.getScanRecord()));

    JSONArray uuids = new JSONArray(uuidStrings);

    if (!uuidStrings.isEmpty()) {
      deviceInfo.put("uuids", uuids);
    }

    deviceInfo.put("rssi", leScanResult.getRssi());
    deviceInfo.put("tx_power", leScanResult.getScanRecord().getTxPowerLevel());

    Iterator serviceDataIt = leScanResult.getScanRecord().getServiceData().entrySet().iterator();
    if (serviceDataIt.hasNext()) {
      Map.Entry<ParcelUuid, byte[]> serviceDataPair =
          (Map.Entry<ParcelUuid, byte[]>) serviceDataIt.next();
      deviceInfo.put("serviceDataUuid", serviceDataPair.getKey().toString());
      deviceInfo.put(
          "serviceData", Base64.encodeToString(serviceDataPair.getValue(), Base64.NO_WRAP));
    }

    return deviceInfo;
  }

  // Note: If the device both discovered by LeScanner and regular scanner, LeDevice
  // info will be returned since it contains more information.
  private void getDevice(CordovaArgs args, CallbackContext callbackContext) throws JSONException {
    String deviceAddress = args.getString(0);
    ScanResult leResult = knownLeScanResults.get(deviceAddress);
    BluetoothDevice device = knownBluetoothDevices.get(deviceAddress);

    if (leResult == null && device == null) {
      callbackContext.error("Invalid Argument");
    } else if (leResult != null) {
      callbackContext.success(getLeDeviceInfo(leResult));
    } else {
      callbackContext.success(getBasicDeviceInfo(device));
    }
  }

  // Note: If the device both discovered by LeScanner and regular scanner, LeDevice
  // info will be returned since it contains more information.
  private void getDevices(CallbackContext callbackContext) throws JSONException {
    JSONArray results = new JSONArray();

    for (BluetoothDevice device: knownBluetoothDevices.values()) {
      results.put(getBasicDeviceInfo(device));
    }

    for (ScanResult result: knownLeScanResults.values()) {
      results.put(getLeDeviceInfo(result));
    }

    callbackContext.success(results);
  }

  private void startDiscovery(CordovaArgs args, CallbackContext callbackContext) {

    String scanMode = "low_latency";
    boolean disableLe = false;
    boolean disableNonLe = false;

    JSONObject obj = args.optJSONObject(0);
    if (obj!=null) {
      scanMode = obj.optString("scanMode", "low_latency");
      disableLe = obj.optBoolean("disableLe");
      disableNonLe = obj.optBoolean("disableNonLe");
    }

    int mode;
    if ("low_power".equals(scanMode)) {
      mode = ScanSettings.SCAN_MODE_LOW_POWER;
    } else if ("balanced".equals(scanMode)) {
      mode = ScanSettings.SCAN_MODE_BALANCED;
    } else {
      mode = ScanSettings.SCAN_MODE_LOW_LATENCY;
    }

    ScanSettings settings = new ScanSettings.Builder()
        .setCallbackType(
            ScanSettings.CALLBACK_TYPE_FIRST_MATCH | ScanSettings.CALLBACK_TYPE_MATCH_LOST)
        .setScanMode(mode)
        .build();

    if (isLeScanning && bluetoothAdapter.isDiscovering()) {
      // both regular and low energy are already in scanning mode. return the
      // same error message as desktop.
      callbackContext.error("Starting discovery failed");
      return;
    }

    // Reset low energy scanner or regular scanner if they are in scanning mode.
    if (isLeScanning) {
      leScanner.stopScan(leScanCallback);
    }

    if (bluetoothAdapter.isDiscovering()) {
      bluetoothAdapter.cancelDiscovery();
    }

    if (!disableLe) {
      isLeScanning = true;
      leScanner.startScan(Collections.EMPTY_LIST, settings, leScanCallback);
    }

    if (!disableNonLe) {
      isDiscovering = true;
      bluetoothAdapter.startDiscovery();
    }
    callbackContext.success();
    sendAdapterStateChangedEvent();
  }

  private void stopDiscovery(CallbackContext callbackContext) {

    if (!isLeScanning && !bluetoothAdapter.isDiscovering()) {
      callbackContext.error("Failed to stop discovery");
      return;
    }

    if (isLeScanning) {
      isLeScanning = false;
      leScanner.stopScan(leScanCallback);
    }

    if (bluetoothAdapter.isDiscovering()) {
      isDiscovering = false;
      bluetoothAdapter.cancelDiscovery();
    }

    callbackContext.success();
    sendAdapterStateChangedEvent();
  }

  private void registerBluetoothEvents(CallbackContext callbackContext) {
    bluetoothEventsCallback = callbackContext;
  }

  private static PluginResult getMultipartEventsResult(String eventType, JSONObject info) {
    List<PluginResult> multipartMessage = new ArrayList<PluginResult>();
    multipartMessage.add(new PluginResult(Status.OK, eventType));
    multipartMessage.add(new PluginResult(Status.OK, info));
    PluginResult result = new PluginResult(Status.OK, multipartMessage);
    result.setKeepCallback(true);
    return result;
  }

  private void sendAdapterStateChangedEvent() {
    try {
      bluetoothEventsCallback.sendPluginResult(
          getMultipartEventsResult("onAdapterStateChanged", getAdapterStateInfo()));
    } catch (JSONException e) {
    }
  }

  private void sendDeviceAddedEvent(ScanResult scanResult) {
    try {
      bluetoothEventsCallback.sendPluginResult(
          getMultipartEventsResult("onDeviceAdded", getLeDeviceInfo(scanResult)));
    } catch (JSONException e) {
    }
  }

  private void sendDeviceAddedEvent(BluetoothDevice device) {
    try {
      bluetoothEventsCallback.sendPluginResult(
          getMultipartEventsResult("onDeviceAdded", getBasicDeviceInfo(device)));
    } catch (JSONException e) {
    }
  }

  void sendDeviceChangedEvent(ScanResult scanResult) {
    try {
      bluetoothEventsCallback.sendPluginResult(
          getMultipartEventsResult("onDeviceChanged", getLeDeviceInfo(scanResult)));
    } catch (JSONException e) {
    }
  }

  private void sendDeviceRemovedEvent(ScanResult scanResult) {
    try {
      bluetoothEventsCallback.sendPluginResult(
          getMultipartEventsResult("onDeviceRemoved", getLeDeviceInfo(scanResult)));
    } catch (JSONException e) {
    }
  }

  private void sendDeviceRemovedEvent(BluetoothDevice device) {
    try {
      bluetoothEventsCallback.sendPluginResult(
          getMultipartEventsResult("onDeviceRemoved", getBasicDeviceInfo(device)));
    } catch (JSONException e) {
    }
  }

  private void unregisterAdapterStateReceiver() {
    webView.getContext().unregisterReceiver(adapterStateReceiver);
  }

  private void registerAdapterStateReceiver() {
    IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
    filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
    filter.addAction(BluetoothDevice.ACTION_FOUND);
    webView.getContext().registerReceiver(adapterStateReceiver, filter);
  }

  private final BroadcastReceiver adapterStateReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {

        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
          sendAdapterStateChangedEvent();
        } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())) {

          if (!isDiscovering) {
            return;
          }

          Set<String> knownAddresses = new HashSet<String>(knownBluetoothDevices.keySet());
          knownAddresses.removeAll(activeDevices);
          for (String address: knownAddresses) {
            sendDeviceRemovedEvent(knownBluetoothDevices.get(address));
            knownBluetoothDevices.remove(address);
          }
          activeDevices.clear();
          bluetoothAdapter.startDiscovery();

        } else if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
          BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
          activeDevices.add(device.getAddress());
          if (!knownBluetoothDevices.containsKey(device.getAddress())) {
            knownBluetoothDevices.put(device.getAddress(), device);
            sendDeviceAddedEvent(device);
          }
        }
      }
    };

  private final ScanCallback leScanCallback = new ScanCallback() {
      @Override
      public void onScanResult(int callbackType, ScanResult result) {
        switch (callbackType) {
          case ScanSettings.CALLBACK_TYPE_FIRST_MATCH:
            assert (!knownLeScanResults.containsKey(result.getDevice().getAddress()));
            knownLeScanResults.put(result.getDevice().getAddress(), result);
            sendDeviceAddedEvent(result);
            break;
          case ScanSettings.CALLBACK_TYPE_MATCH_LOST:
            sendDeviceRemovedEvent(result);
            knownLeScanResults.remove(result.getDevice().getAddress());
            break;
        }
      }

      @Override
      public void onScanFailed(int errorCode) {
        Log.e(LOG_TAG, "onScanFailed():");
        isLeScanning = false;
      }
    };
}
