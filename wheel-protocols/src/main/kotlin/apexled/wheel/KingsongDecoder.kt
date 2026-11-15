package apexled.wheel

import apexled.core.Telemetry
import kotlin.math.abs

/**
 * Protocolo Kingsong (BLE UART, serviço FFE0 / característica FFE1). Também usado pelas Rockwheel.
 *
 * Frame de 20 bytes: `AA 55 <14 bytes de dados> <tipo> <0x14> 5A 5A` (nome/série usam o rodapé para
 * dados). Inteiros little-endian.
 *  - Tipo 0xA9 (ao vivo): [2..3] tensão u16 0,01 V; [4..5] velocidade s16 0,01 km/h;
 *    [10..11] corrente s16 0,01 A; [12..13] temperatura s16 0,01 °C.
 *  - Tipo 0xF5: [15] PWM ("output") em % (0..100).
 *  - Tipo 0xBB: nome "KS-S18-0205" (modelo = tudo antes do último "-").
 * A roda envia os frames ao vivo sozinha; o nome é pedido com o tipo 0x9B até chegar.
 *
 * Fonte: WheelLog (GPL-3.0), utils/KingsongAdapter.java e KingsongAdapterTest.kt.
 */
class KingsongDecoder : WheelDecoder {
    private val buffer = ArrayList<Byte>()
    private var pwm: Float? = null
    private var lastNameRequestMs = Long.MIN_VALUE / 2

    override var model: String? = null
        private set

    override fun reset() { buffer.clear(); pwm = null; model = null; lastNameRequestMs = Long.MIN_VALUE / 2 }

    override fun feed(bytes: ByteArray, nowMs: Long): List<Telemetry> {
        for (b in bytes) buffer.add(b)
        if (buffer.size > MAX_BUFFER) buffer.dropFirst(buffer.size - MAX_BUFFER)
        val out = ArrayList<Telemetry>()
        while (true) {
            val start = findHeader()
            if (start < 0) {
                if (buffer.isNotEmpty() && buffer.last() == 0xAA.toByte()) buffer.dropFirst(buffer.size - 1) else buffer.clear()
                break
            }
            if (start > 0) buffer.dropFirst(start)
            if (buffer.size < FRAME_LEN) break
            // Sem checar o rodapé: os frames de nome/série (0xBB/0xB3) usam os bytes 17..19 para dados.
            val f = ByteArray(FRAME_LEN) { buffer[it] }
            buffer.dropFirst(FRAME_LEN)
            decode(f, nowMs)?.let { out += it }
        }
        return out
    }

    private fun findHeader(): Int {
        for (i in 0 until buffer.size - 1) if (buffer[i] == 0xAA.toByte() && buffer[i + 1] == 0x55.toByte()) return i
        return -1
    }

    private fun decode(f: ByteArray, nowMs: Long): Telemetry? {
        when (f[16].toInt() and 0xFF) {
            0xA9 -> Unit
            0xF5 -> { pwm = (f[15].toInt() and 0xFF) / 100f; return null }
            0xBB -> { model = parseName(f); return null }
            else -> return null
        }
        val raw = f.u16le(2)
        val voltage = raw / 100f
        return Telemetry(
            timestampMs = nowMs,
            speedKmh = abs(f.s16le(4)) / 100f,
            pwm = pwm,
            voltage = voltage,
            currentA = f.s16le(10) / 100f,
            batteryPercent = model?.let { batteryPercent(it, raw) },
            temperatureC = f.s16le(12) / 100f,
            pwmEstimated = false,
        )
    }

    override fun poll(nowMs: Long): ByteArray? {
        if (model != null || nowMs - lastNameRequestMs < 1000) return null
        lastNameRequestMs = nowMs
        return request(0x9B)
    }

    companion object {
        private const val FRAME_LEN = 20
        private const val MAX_BUFFER = 512

        fun request(type: Int): ByteArray = byteArrayOf(
            0xAA.toByte(), 0x55, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, type.toByte(), 0x14, 0x5A, 0x5A,
        )

        /** "KS-S18-0205" → "KS-S18". */
        internal fun parseName(f: ByteArray): String {
            var end = 0
            while (end < 14 && f[2 + end] != 0.toByte()) end++
            val name = String(f, 2, end, Charsets.US_ASCII).trim()
            val parts = name.split("-")
            return if (parts.size > 1) parts.dropLast(1).joinToString("-") else name
        }

        /** Percentual "padrão" do WheelLog pela classe de tensão do modelo (raw em 0,01 V). */
        internal fun batteryPercent(model: String, raw: Int): Float {
            val (empty, step) = when (model) {
                "KS-18L", "KS-16X", "KS-16XF", "RW", "KS-18LH", "KS-18LY", "KS-S18", "KS-S16", "KS-S16P" -> 6250 to 20 // 84 V
                "KS-S19" -> 7500 to 24       // 100,8 V
                "KS-S20", "KS-S22" -> 9375 to 30 // 126 V
                "KS-F18P" -> 11250 to 36     // 151,2 V
                "KS-F22P" -> 13125 to 42     // 176,4 V
                else -> 5000 to 16           // 67,2 V
            }
            return ((raw - empty) / step).coerceIn(0, 100).toFloat()
        }
    }
}
