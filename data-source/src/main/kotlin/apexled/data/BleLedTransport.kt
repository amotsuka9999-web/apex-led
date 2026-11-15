package apexled.data

import apexled.core.LedLinkState
import apexled.data.ble.GattClient
import apexled.led.LedTransport
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Liga o [apexled.led.Sp621eController] ao GATT real (serviço FFE0, característica FFE1). */
class BleLedTransport(val gatt: GattClient) : LedTransport {
    override val link: StateFlow<LedLinkState> get() = gatt.state
    override val deviceName: StateFlow<String?> get() = gatt.deviceName
    override val notifications: SharedFlow<ByteArray> get() = gatt.notifications
    override suspend fun write(data: ByteArray): Boolean = gatt.write(data)
}
