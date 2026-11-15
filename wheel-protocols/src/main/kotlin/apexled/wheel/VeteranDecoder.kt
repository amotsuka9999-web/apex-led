package apexled.wheel

import apexled.core.PwmCalibration
import apexled.core.Telemetry
import java.util.zip.CRC32
import kotlin.math.abs

/**
 * @property calibration usada se a roda não informar PWM de hardware (firmware antigo).
 * @property preferHardwarePwm usa o PWM de hardware (bytes 34–35) quando a versão do firmware é ≥ 2.
 */
data class VeteranConfig(
    val calibration: PwmCalibration = PwmCalibration(100f, 151.2f, 0.90f),
    val preferHardwarePwm: Boolean = true,
)

/**
 * Protocolo Veteran/Leaperkim (e Nosfet, que usa a família: versão 42 = Apex, 43 = Aero, 44 = Aeon).
 *
 * Frame: `DC 5A 5C <len> ...`, total `len + 4` bytes. Se `len > 38` (firmware novo) os últimos 4 bytes são o
 * CRC32 (big-endian) dos primeiros `len` bytes. Campos (big-endian):
 *  - [4..5] tensão u16, 0,01 V
 *  - [6..7] velocidade s16, 0,1 km/h
 *  - [8..11] distância da viagem, u32 "word-swapped" (m)
 *  - [12..15] distância total, idem
 *  - [16..17] corrente de fase s16, 0,1 A
 *  - [18..19] temperatura s16, 0,01 °C
 *  - [28..29] versão u16; `versão/1000` identifica o modelo (42 = Nosfet Apex)
 *  - [34..35] PWM de hardware u16, 0,01 % (10000 = 100%)
 */
class VeteranDecoder(private val config: VeteranConfig = VeteranConfig()) : WheelDecoder {
    private val buffer = ArrayList<Byte>()
    private var usingCrc = false
    private var lastChunkMs = 0L

    override var model: String? = null
        private set

    /** Versão do protocolo/modelo lida do último frame (ex.: 42). */
    var protocolVersion: Int = 0
        private set

    override fun reset() { buffer.clear(); usingCrc = false; model = null; protocolVersion = 0 }

    override fun feed(bytes: ByteArray, nowMs: Long): List<Telemetry> {
        // Frame cortado há muito tempo não será completado: descarta (o WheelLog faz o mesmo após 100 ms).
        if (buffer.isNotEmpty() && nowMs - lastChunkMs > GAP_RESET_MS) buffer.clear()
        lastChunkMs = nowMs
        for (b in bytes) buffer.add(b)
        if (buffer.size > MAX_BUFFER) buffer.dropFirst(buffer.size - MAX_BUFFER)
        val out = ArrayList<Telemetry>()
        while (true) {
            val start = findHeader()
            if (start < 0) {
                val keep = trailingHeaderPrefix()
                buffer.dropFirst(buffer.size - keep)
                break
            }
            if (start > 0) buffer.dropFirst(start)
            if (buffer.size < 4) break
            val len = buffer[3].toInt() and 0xFF
            val total = len + 4
            if (len < MIN_LEN) { buffer.dropFirst(1); continue }
            if (buffer.size < total) break
            val frame = ByteArray(total) { buffer[it] }
            if (valid(frame, len)) {
                buffer.dropFirst(total)
                out += decode(frame, nowMs)
            } else {
                buffer.dropFirst(1)
            }
        }
        return out
    }

    private fun findHeader(): Int {
        for (i in 0 until buffer.size - 2) {
            if (buffer[i] == 0xDC.toByte() && buffer[i + 1] == 0x5A.toByte() && buffer[i + 2] == 0x5C.toByte()) return i
        }
        return -1
    }

    /** Quantos bytes finais podem ser o início de um cabeçalho cortado (DC, DC 5A). */
    private fun trailingHeaderPrefix(): Int {
        val n = buffer.size
        if (n >= 2 && buffer[n - 2] == 0xDC.toByte() && buffer[n - 1] == 0x5A.toByte()) return 2
        if (n >= 1 && buffer[n - 1] == 0xDC.toByte()) return 1
        return 0
    }

    private fun valid(f: ByteArray, len: Int): Boolean {
        // Verificações de sanidade dos bytes fixos (as mesmas do WheelLog).
        if (f.size > 22 && f[22].toInt() != 0) return false
        if (f.size > 23 && (f[23].toInt() and 0xFE) != 0) return false
        if (f.size > 30 && !(f[30].toInt() == 0 || f[30].toInt() == 7)) return false
        if (len > 38 || usingCrc) {
            val crc = CRC32().apply { update(f, 0, len) }.value
            val provided = f.u32(len)
            if (crc != provided) return false
            usingCrc = true
        }
        return true
    }

    private fun decode(f: ByteArray, nowMs: Long): Telemetry {
        val voltage = f.u16(4) / 100f
        val speedKmh = abs(f.s16(6) / 10f)
        val phaseA = abs(f.s16(16) / 10f)
        val tempC = f.s16(18) / 100f
        val version = f.u16(28)
        protocolVersion = version / 1000
        model = modelName(protocolVersion)

        val hwRaw = if (f.size > 35) f.u16(34) else 0
        val useHw = config.preferHardwarePwm && protocolVersion >= 2
        val pwm = if (useHw) hwRaw / 10000f else config.calibration.estimate(speedKmh, voltage)
        return Telemetry(
            timestampMs = nowMs,
            speedKmh = speedKmh,
            pwm = pwm,
            voltage = voltage,
            // Mesma aproximação do WheelLog (calculateCurrent): corrente de bateria ≈ PWM × corrente de fase.
            currentA = pwm?.let { it * phaseA },
            batteryPercent = null,
            temperatureC = tempC,
            pwmEstimated = !useHw,
        )
    }

    companion object {
        private const val MAX_BUFFER = 1024
        private const val MIN_LEN = 32
        private const val GAP_RESET_MS = 250L

        fun modelName(ver: Int): String = when (ver) {
            0, 1 -> "Sherman"
            2 -> "Abrams"
            3 -> "Sherman S"
            4 -> "Patton"
            5 -> "Lynx"
            6 -> "Sherman L"
            7 -> "Patton S"
            8 -> "Oryx"
            9 -> "Lynx S"
            42 -> "Nosfet Apex"
            43 -> "Nosfet Aero"
            44 -> "Nosfet Aeon"
            else -> "Veteran ($ver)"
        }
    }
}
