package app.apexled.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A Bluetooth LE device seen during a scan. */
data class FoundDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    /** True when the advertisement looks like an SP6xxE LED controller. A hint, not a guarantee. */
    val looksLikeLedBoard: Boolean,
)

/**
 * Finds LED boards nearby. NOT TESTED ON HARDWARE.
 *
 * The caller must hold the scan permissions (BLUETOOTH_SCAN on Android 12+, location below).
 */
@SuppressLint("MissingPermission")
class BoardScanner(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Emits devices as they are seen, for as long as the flow is collected.
     * Fails with [IllegalStateException] when Bluetooth is unavailable, off, or the scan cannot start.
     */
    fun scan(): Flow<FoundDevice> = callbackFlow {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth is unavailable or off"))
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val hasService = record?.serviceUuids?.contains(ParcelUuid(BleLedTransport.SERVICE_UUID)) == true
                val hasManufacturer = record?.getManufacturerSpecificData(MANUFACTURER_ID) != null
                val name = record?.deviceName ?: try {
                    result.device.name
                } catch (_: SecurityException) {
                    null
                }
                trySend(FoundDevice(result.device.address, name, result.rssi, hasService || hasManufacturer))
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Bluetooth scan failed (code $errorCode)"))
            }
        }

        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            close(IllegalStateException("Bluetooth scan permission not granted", e))
            return@callbackFlow
        }
        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
            } catch (_: IllegalStateException) {
                // Bluetooth was switched off while scanning.
            }
        }
    }

    companion object {
        /** Bluetooth company identifier the SP6xxE controllers advertise (0x5053). */
        const val MANUFACTURER_ID = 20563
    }
}
