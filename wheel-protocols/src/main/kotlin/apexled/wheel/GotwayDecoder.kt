package apexled.wheel

import apexled.core.PwmCalibration
import apexled.core.Telemetry
import kotlin.math.abs

/**
 * @property voltageScale multiplicador da tensão lida (o WheelLog tem a mesma opção: 1,0 para 16S/67,2 V;
 *   2,0 para packs de 120 V que enviam metade da tensão, etc.).
 * @property calibration usada para estimar PWM quando a roda não o informa.
 * @property frameAPwmIsHardware true só para firmwares customizados (ex.: "CF"/"BF" do WheelLog) em que os
 *   bytes 14–15 do frame A carregam o PWM de hardware.
 */
data class GotwayConfig(
    val voltageScale: Float = 1f,
    val calibration: PwmCalibration = PwmCalibration(50f, 84f, 0.90f),
    val frameAPwmIsHardware: Boolean = false,
)

/**
 * Protocolo Gotway/Begode (BLE UART, serviço FFE0 / característica FFE1).
 *
 * Frame de 24 bytes: `55 AA <16 bytes de dados> <tipo> <0x18> 5A 5A 5A 5A`.
 *
 * Frame A (tipo 0x00, dados ao vivo), big-endian:
 *  - [2..3] tensão u16, 0,01 V (× [GotwayConfig.voltageScale])
 *  - [4..5] velocidade s16, × 3,6 = 0,01 km/h
 *  - [8..9] distância u16 (m)
 *  - [10..11] corrente de fase s16, 0,01 A
 *  - [12..13] temperatura s16 do IMU MPU6050: raw/340 + 36,53 °C
 *  - [14..15] PWM (somente firmware customizado)
 * Frame 0x07 (firmwares novos): [2..3] corrente de bateria s16 (0,01 A, sinal invertido),
 *  [6..7] temperatura do motor s16 (°C), [8..9] PWM de hardware em % (0..100).
 */
class GotwayDecoder(private val config: GotwayConfig = GotwayConfig()) : WheelDecoder {
    private val buffer = ArrayList<Byte>()

    private var hardwarePwm: Float? = null        // preenchido após ver um frame 0x07 com PWM ≠ 0
    private var batteryCurrentA: Float? = null
    private var motorTempC: Float? = null

    override val model: String? = null

    override fun reset() {
        buffer.clear(); hardwarePwm = null; batteryCurrentA = null; motorTempC = null
    }

    override fun feed(bytes: ByteArray, nowMs: Long): List<Telemetry> {
        for (b in bytes) buffer.add(b)
        if (buffer.size > MAX_BUFFER) buffer.dropFirst(buffer.size - MAX_BUFFER)
        val out = ArrayList<Telemetry>()
        while (true) {
            val start = findHeader()
            if (start < 0) {
                // mantém o último byte caso seja o 0x55 de um cabeçalho cortado
                if (buffer.isNotEmpty() && buffer.last() == 0x55.toByte()) buffer.dropFirst(buffer.size - 1) else buffer.clear()
                break
            }
            if (start > 0) buffer.dropFirst(start)
            if (buffer.size < FRAME_LEN) break
            val frame = ByteArray(FRAME_LEN) { buffer[it] }
            if (frame.footerOk()) {
                buffer.dropFirst(FRAME_LEN)
                decodeFrame(frame, nowMs)?.let { out += it }
            } else {
                buffer.dropFirst(1) // lixo: procura o próximo cabeçalho
            }
        }
        return out
    }

    private fun findHeader(): Int {
        for (i in 0 until buffer.size - 1) {
            if (buffer[i] == 0x55.toByte() && buffer[i + 1] == 0xAA.toByte()) return i
        }
        return -1
    }

    private fun ByteArray.footerOk() = (20..23).all { this[it] == 0x5A.toByte() }

    private fun decodeFrame(f: ByteArray, nowMs: Long): Telemetry? {
        when (f[18].toInt() and 0xFF) {
            0x07 -> {
                batteryCurrentA = -f.s16(2) / 100f
                motorTempC = f.s16(6).toFloat()
                val pct = abs(f.s16(8))
                if (pct > 0) hardwarePwm = pct / 100f
                return null
            }
            0x00 -> Unit
            else -> return null
        }
        val voltage = f.u16(2) / 100f * config.voltageScale
        val speedKmh = abs(f.s16(4) * 3.6f / 100f)
        val phaseA = abs(f.s16(10) / 100f)
        val imuTemp = f.s16(12) / 340f + 36.53f

        val frameAPwm = if (config.frameAPwmIsHardware) abs(f.s16(14)) / 1000f else null
        val hw = hardwarePwm ?: frameAPwm
        val estimated = hw == null
        val pwm = hw ?: config.calibration.estimate(speedKmh, voltage)
        val current = batteryCurrentA ?: pwm?.let { it * phaseA }
        return Telemetry(
            timestampMs = nowMs,
            speedKmh = speedKmh,
            pwm = pwm,
            voltage = voltage,
            currentA = current,
            batteryPercent = null,
            temperatureC = motorTempC ?: imuTemp,
            pwmEstimated = estimated,
        )
    }

    private companion object {
        const val FRAME_LEN = 24
        const val MAX_BUFFER = 512
    }
}
