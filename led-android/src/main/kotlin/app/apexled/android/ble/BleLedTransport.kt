package app.apexled.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import app.apexled.device.LedTransport
import app.apexled.device.TransportEvent
import app.apexled.device.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * [LedTransport] over Android Bluetooth LE GATT: service FFE0, characteristic FFE1
 * (write + notify), as used by the SP6xxE controllers.
 *
 * NOT TESTED ON HARDWARE. It compiles and follows the documented GATT call sequence, but
 * it has never talked to a board. See docs/checklist-campo.md, section A1.
 *
 * The caller must hold the Bluetooth permissions before using it (BLUETOOTH_CONNECT on
 * Android 12+). Timeouts and reconnection are handled above, by BoardConnection.
 */
@SuppressLint("MissingPermission")
class BleLedTransport(context: Context, private val address: String) : LedTransport {

    private val appContext = context.applicationContext

    private val _events = MutableSharedFlow<TransportEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<TransportEvent> = _events

    private val lock = Any()
    private val writeMutex = Mutex()
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var pendingConnect: CompletableDeferred<Unit>? = null
    private var pendingWrite: CompletableDeferred<Unit>? = null

    override suspend fun connect() {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: throw TransportException("this device has no Bluetooth")
        if (!adapter.isEnabled) throw TransportException("Bluetooth is off")
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw TransportException("invalid Bluetooth address '$address'", e)
        }

        close()
        val ready = CompletableDeferred<Unit>()
        var connected = false
        try {
            synchronized(lock) {
                pendingConnect = ready
                gatt = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
                    } else {
                        device.connectGatt(appContext, false, callback)
                    }
                } catch (e: SecurityException) {
                    throw TransportException("Bluetooth permission not granted", e)
                } ?: throw TransportException("could not open a GATT connection")
            }
            ready.await()
            connected = true
        } finally {
            synchronized(lock) { pendingConnect = null }
            // Covers failure and cancellation (the connect timeout of BoardConnection).
            if (!connected) close()
        }
    }

    override suspend fun disconnect() {
        close()
    }

    override suspend fun write(payload: ByteArray) {
        writeMutex.withLock {
            val done = CompletableDeferred<Unit>()
            synchronized(lock) {
                val currentGatt = gatt ?: throw TransportException("not connected")
                val target = characteristic ?: throw TransportException("not connected")
                val noResponse = target.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                val writeType = if (noResponse) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                pendingWrite = done
                val accepted = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        currentGatt.writeCharacteristic(target, payload, writeType) == BluetoothStatusCodes.SUCCESS
                    } else {
                        legacyWrite(currentGatt, target, payload, writeType)
                    }
                } catch (e: SecurityException) {
                    false
                }
                if (!accepted) {
                    pendingWrite = null
                    throw TransportException("the Bluetooth stack refused the write")
                }
            }
            try {
                done.await()
            } finally {
                synchronized(lock) { if (pendingWrite === done) pendingWrite = null }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyWrite(
        gatt: BluetoothGatt,
        target: BluetoothGattCharacteristic,
        payload: ByteArray,
        writeType: Int,
    ): Boolean {
        target.writeType = writeType
        target.value = payload
        return gatt.writeCharacteristic(target)
    }

    private fun close() {
        val old = synchronized(lock) {
            characteristic = null
            gatt.also { gatt = null }
        }
        try {
            old?.disconnect()
            old?.close()
        } catch (_: SecurityException) {
            // Permission was revoked while connected; the link is gone either way.
        }
    }

    /** Fails whatever is waiting and reports the loss. Called from GATT callback threads. */
    private fun linkDown(source: BluetoothGatt, reason: String) {
        val wasCurrent: Boolean
        val connect: CompletableDeferred<Unit>?
        val write: CompletableDeferred<Unit>?
        synchronized(lock) {
            wasCurrent = gatt === source
            if (!wasCurrent) return
            connect = pendingConnect
            write = pendingWrite
            pendingConnect = null
            pendingWrite = null
            characteristic = null
            gatt = null
        }
        try {
            source.close()
        } catch (_: SecurityException) {
        }
        val error = TransportException(reason)
        connect?.completeExceptionally(error)
        write?.completeExceptionally(error)
        // A failed connection attempt is reported through connect(); only an established link is "lost".
        if (connect == null) _events.tryEmit(TransportEvent.LinkLost(reason))
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (!gatt.discoverServices()) linkDown(gatt, "service discovery could not start")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                linkDown(gatt, "disconnected (status $status)")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val target = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || target == null) {
                linkDown(gatt, "this device does not expose the LED controller service")
                return
            }
            synchronized(lock) { characteristic = target }

            // Notifications carry the status replies. A board without the descriptor still accepts commands.
            val descriptor = target.getDescriptor(CLIENT_CONFIG_UUID)
            if (!gatt.setCharacteristicNotification(target, true) || descriptor == null || !enableNotifications(gatt, descriptor)) {
                connectionReady()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            connectionReady()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val write = synchronized(lock) { pendingWrite.also { pendingWrite = null } } ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                write.complete(Unit)
            } else {
                write.completeExceptionally(TransportException("write failed (status $status)"))
            }
        }

        // Android 13+.
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            _events.tryEmit(TransportEvent.Notification(value.copyOf()))
        }

        // Android 12 and below.
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                characteristic.value?.let { _events.tryEmit(TransportEvent.Notification(it.copyOf())) }
            }
        }
    }

    private fun connectionReady() {
        synchronized(lock) { pendingConnect.also { pendingConnect = null } }?.complete(Unit)
    }

    @Suppress("DEPRECATION")
    private fun enableNotifications(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
