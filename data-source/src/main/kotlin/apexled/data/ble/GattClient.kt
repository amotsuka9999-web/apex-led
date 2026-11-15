package apexled.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import apexled.core.LedLinkState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Cliente GATT mínimo para o padrão "UART BLE" FFE0/FFE1 (usado pelas rodas Gotway/Veteran e pelo SP621E):
 * conecta, descobre serviços, liga notificações em uma característica e escreve nela. Reconecta sozinho com
 * recuo exponencial (1 s, 2 s, 4 s … até 10 s) enquanto [connect] estiver ativo.
 *
 * ⚠ Nunca foi executado em hardware por este projeto: escrito a partir da API do Android. Veja o README
 * para o roteiro de testes em campo.
 */
@SuppressLint("MissingPermission") // permissões verificadas pela UI antes de conectar (BlePermissions)
class GattClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val tag: String = "gatt",
    /**
     * Sempre escrever COM resposta. O SP621E/BanlanX descarta escritas sem resposta: o `uniled`
     * (banlanx device.py) usa `write_gatt_char(..., True)` e marca a versão sem resposta como "Do not use!".
     */
    private val alwaysWriteWithResponse: Boolean = false,
) {
    private val _state = MutableStateFlow(LedLinkState.DISCONNECTED)
    val state: StateFlow<LedLinkState> = _state.asStateFlow()

    private val _name = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _name.asStateFlow()

    private val _rssi = MutableStateFlow<Int?>(null)
    val rssi: StateFlow<Int?> = _rssi.asStateFlow()

    private val _notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val notifications: SharedFlow<ByteArray> = _notifications.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var wanted = false
    private var address: String? = null
    private var layouts: List<GattLayout> = listOf(GattLayout.UART)

    private val _layout = MutableStateFlow<GattLayout?>(null)
    /** Layout de serviços encontrado na última conexão (define o protocolo da roda). */
    val layout: StateFlow<GattLayout?> = _layout.asStateFlow()
    private var reconnectJob: Job? = null
    private var attempt = 0
    private val writeMutex = Mutex()
    private var pendingWrite: CompletableDeferred<Boolean>? = null

    /** Conecta e usa o primeiro layout de [layouts] que existir no aparelho. */
    fun connect(address: String, layouts: List<GattLayout> = listOf(GattLayout.UART)) {
        this.address = address
        this.layouts = layouts
        wanted = true
        attempt = 0
        openGatt()
    }

    fun disconnect() {
        wanted = false
        reconnectJob?.cancel()
        closeGatt()
        _state.value = LedLinkState.DISCONNECTED
    }

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun openGatt() {
        val addr = address ?: return
        val adapter = adapter()
        if (adapter == null || !adapter.isEnabled) {
            _lastError.value = "Bluetooth desligado"
            scheduleReconnect()
            return
        }
        closeGatt()
        _state.value = if (attempt == 0) LedLinkState.CONNECTING else LedLinkState.RECONNECTING
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(addr)
        } catch (e: IllegalArgumentException) {
            _lastError.value = "Endereço inválido"; _state.value = LedLinkState.DISCONNECTED; wanted = false; return
        }
        _name.value = runCatching { device.name }.getOrNull() ?: _name.value
        gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(context, false, callback)
    }

    private fun closeGatt() {
        characteristic = null
        writeCharacteristic = null
        gatt?.let { g -> runCatching { g.disconnect() }; runCatching { g.close() } }
        gatt = null
        pendingWrite?.complete(false)
    }

    private fun scheduleReconnect() {
        if (!wanted) return
        _state.value = LedLinkState.RECONNECTING
        reconnectJob?.cancel()
        val wait = (1000L shl attempt.coerceAtMost(4)).coerceAtMost(10_000L)
        attempt++
        reconnectJob = scope.launch {
            delay(wait)
            if (wanted) openGatt()
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                _lastError.value = if (status != 0) "GATT status $status" else null
                characteristic = null
                writeCharacteristic = null
                runCatching { g.close() }
                if (gatt === g) gatt = null
                pendingWrite?.complete(false)
                if (wanted) scheduleReconnect() else _state.value = LedLinkState.DISCONNECTED
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            var found: Triple<GattLayout, BluetoothGattCharacteristic, BluetoothGattCharacteristic>? = null
            if (status == BluetoothGatt.GATT_SUCCESS) for (l in layouts) {
                val n = g.getService(l.service)?.getCharacteristic(l.notify) ?: continue
                val w = g.getService(l.writeService)?.getCharacteristic(l.write) ?: continue
                found = Triple(l, n, w); break
            }
            if (found == null) {
                _lastError.value = "Serviço BLE esperado não encontrado (${layouts.joinToString { it.name }})"
                runCatching { g.disconnect() }
                return
            }
            val (l, ch, w) = found
            _layout.value = l
            characteristic = ch
            writeCharacteristic = w
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD)
            if (cccd == null) { markReady(); return }
            val indicate = ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 &&
                ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, value)
            else @Suppress("DEPRECATION") { cccd.value = value; g.writeDescriptor(cccd) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD) markReady()
        }

        private fun markReady() {
            attempt = 0
            _lastError.value = null
            // Intervalo de conexão curto (~7,5–15 ms em vez de ~30–50 ms): cada escrita com resposta e cada
            // notificação chegam mais rápido. Gasta um pouco mais de bateria, só enquanto conectado.
            runCatching { gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
            _state.value = LedLinkState.CONNECTED
            gatt?.readRemoteRssi()
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") c.value?.let { _notifications.tryEmit(it.copyOf()) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            _notifications.tryEmit(value.copyOf())
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) _rssi.value = rssi
            scope.launch { delay(5000); if (_state.value == LedLinkState.CONNECTED) gatt?.readRemoteRssi() }
        }
    }

    /**
     * Escreve [data]. Usa escrita sem resposta quando a característica suporta (menor latência); senão escrita
     * com resposta, aguardando a confirmação. Escritas são serializadas.
     */
    suspend fun write(data: ByteArray): Boolean = writeMutex.withLock {
        val g = gatt ?: return false
        val ch = writeCharacteristic ?: return false
        if (_state.value != LedLinkState.CONNECTED) return false
        val canWrite = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val noResponse = (!alwaysWriteWithResponse || !canWrite) &&
            ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val waiter = CompletableDeferred<Boolean>()
        pendingWrite = if (noResponse) null else waiter
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, data, type) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") run { ch.writeType = type; ch.value = data; g.writeCharacteristic(ch) }
        }
        if (!started) return false
        if (noResponse) true else (withTimeoutOrNull(2000) { waiter.await() } ?: false)
    }

    companion object {
        val FFE0: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val FFE1: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

/**
 * Onde ficam as características de leitura (notificação) e escrita de um aparelho.
 * Fontes dos UUIDs: WheelLog, utils/Constants.kt.
 */
data class GattLayout(
    val name: String,
    val service: UUID,
    val notify: UUID,
    val writeService: UUID = service,
    val write: UUID = notify,
) {
    companion object {
        private fun u(s: String) = UUID.fromString(s)
        /** UART FFE0/FFE1: Begode/Gotway, Leaperkim/Veteran, Nosfet, Kingsong e o SP621E. */
        val UART = GattLayout("FFE0/FFE1", u("0000ffe0-0000-1000-8000-00805f9b34fb"), u("0000ffe1-0000-1000-8000-00805f9b34fb"))
        /** Inmotion V5/V8/V10 (protocolo CAN): lê FFE0/FFE4 e escreve FFE5/FFE9. */
        val INMOTION_V1 = GattLayout(
            "Inmotion V1", u("0000ffe0-0000-1000-8000-00805f9b34fb"), u("0000ffe4-0000-1000-8000-00805f9b34fb"),
            u("0000ffe5-0000-1000-8000-00805f9b34fb"), u("0000ffe9-0000-1000-8000-00805f9b34fb"),
        )
        /** Inmotion V9/V11/V12/V13/V14/P6 (Nordic UART). */
        val INMOTION_V2 = GattLayout(
            "Inmotion V2", u("6e400001-b5a3-f393-e0a9-e50e24dcca9e"), u("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
            write = u("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
        )
        /** Ordem de detecção para rodas: os mais específicos primeiro. */
        val WHEELS = listOf(INMOTION_V2, INMOTION_V1, UART)
    }
}
