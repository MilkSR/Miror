package com.selenite.scanner.link

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.selenite.scanner.util.AndroidContext
import java.util.UUID

/**
 * BLE beacon and scanner for Miror Link's ambiguity ranking.
 *
 * Advertises a single 128-bit service UUID and nothing else -- no manufacturer data, no
 * device name, no second key -- so the payload stays inside the 31-byte legacy
 * advertising budget on every device.
 *
 * Every failure path here is non-fatal by design: an advertiser slot being exhausted or
 * a scan being refused simply means the nickname chooser is used instead of automatic
 * proximity selection. Collection transport is entirely unaffected.
 */
// Every BLE call below is guarded twice: [isAvailable] does the explicit runtime
// permission check lint asks for before any start call, and every call is additionally
// wrapped in runCatching so a revoked-mid-session permission surfaces as "ranking
// unavailable, use the chooser" rather than a crash. Lint cannot follow either guard
// across the helper, hence the suppression.
@SuppressLint("MissingPermission")
internal class AndroidMirorLinkProximitySource(context: Context) : MirorLinkProximitySource {

    private val appContext = context.applicationContext

    private val adapter
        get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    override fun isAvailable(): Boolean {
        val bluetooth = adapter ?: return false
        if (!bluetooth.isEnabled) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanOk = ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.BLUETOOTH_SCAN,
            ) == PackageManager.PERMISSION_GRANTED
            val advertiseOk = ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.BLUETOOTH_ADVERTISE,
            ) == PackageManager.PERMISSION_GRANTED
            if (!scanOk || !advertiseOk) return false
        } else {
            val locationOk = ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            if (!locationOk) return false
        }
        return bluetooth.bluetoothLeAdvertiser != null
    }

    override suspend fun startBeacon(serviceUuid: String) {
        if (!isAvailable()) return
        stopBeacon()
        val bleAdvertiser = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()
        // Name excluded deliberately: it would push the payload over 31 bytes and is a
        // stable identifier, which the beacon must not carry.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(UUID.fromString(serviceUuid)))
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                Log.i(TAG, "Link beacon unavailable (error $errorCode); using the nickname chooser")
            }
        }
        runCatching {
            bleAdvertiser.startAdvertising(settings, data, callback)
            advertiser = bleAdvertiser
            advertiseCallback = callback
        }.onFailure { Log.i(TAG, "Could not start the Link beacon", it) }
    }

    override suspend fun stopBeacon() {
        val current = advertiseCallback ?: return
        runCatching { advertiser?.stopAdvertising(current) }
        advertiseCallback = null
        advertiser = null
    }

    override suspend fun startScan(
        serviceUuids: List<String>,
        onSample: (uuid: String, rssiDbm: Int) -> Unit,
    ) {
        if (!isAvailable() || serviceUuids.isEmpty()) return
        stopScan()
        val bleScanner = adapter?.bluetoothLeScanner ?: return
        // Filtered by the exact candidate UUIDs; Link never runs an unfiltered scan.
        val filters = serviceUuids.mapNotNull { uuid ->
            runCatching {
                ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(uuid))).build()
            }.getOrNull()
        }
        if (filters.isEmpty()) return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val matched = result.scanRecord?.serviceUuids?.firstOrNull()?.uuid?.toString()
                    ?: return
                onSample(matched.lowercase(), result.rssi)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.i(TAG, "Link BLE scan unavailable (error $errorCode); using the chooser")
            }
        }
        runCatching {
            bleScanner.startScan(filters, settings, callback)
            scanner = bleScanner
            scanCallback = callback
        }.onFailure { Log.i(TAG, "Could not start the Link BLE scan", it) }
    }

    override suspend fun stopScan() {
        val current = scanCallback ?: return
        runCatching { scanner?.stopScan(current) }
        scanCallback = null
        scanner = null
    }

    companion object {
        private const val TAG = "MirorLinkProximity"
    }
}

internal actual fun createMirorLinkProximitySource(): MirorLinkProximitySource? =
    AndroidContext?.applicationContext?.let { AndroidMirorLinkProximitySource(it) }
