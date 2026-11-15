package apexled.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class ScannedDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    /** Dados de fabricante com o ID 0x5053 ("SP") ou nome SP6xx: provável controlador BanlanX. */
    val looksLikeSpLed: Boolean,
    /** Anuncia o serviço FFE0 (comum a rodas e controladores). */
    val hasFfe0: Boolean,
    val lastSeenMs: Long,
)

object BlePermissions {
    /** Permissões de execução necessárias para escanear/conectar nesta versão do Android. */
    fun required(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) listOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasAll(context: Context): Boolean =
        required().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}

/** Escaneia dispositivos BLE próximos. Cancele a coleta do fluxo para parar a busca. */
class BleScanner(private val context: Context) {
    @SuppressLint("MissingPermission")
    fun scan(): Flow<ScannedDevice> = callbackFlow {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) { close(IllegalStateException("Bluetooth desligado ou indisponível")); return@callbackFlow }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { trySend(result.toDevice()) }
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { trySend(it.toDevice()) } }
            override fun onScanFailed(errorCode: Int) { close(IllegalStateException("Falha na busca BLE (código $errorCode)")) }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toDevice(): ScannedDevice {
        val record = scanRecord
        val name = record?.deviceName ?: runCatching { device.name }.getOrNull()
        val sp = record?.getManufacturerSpecificData(0x5053) != null || name?.startsWith("SP6", ignoreCase = true) == true
        val ffe0 = record?.serviceUuids?.any { it.uuid == GattClient.FFE0 } == true
        return ScannedDevice(device.address, name, rssi, sp, ffe0, System.currentTimeMillis())
    }
}
