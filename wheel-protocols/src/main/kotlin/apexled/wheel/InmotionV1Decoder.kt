package apexled.wheel

import apexled.core.PwmCalibration
import apexled.core.Telemetry
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Inmotion "V1" (V5, V8, V10 e Glide 3): pacotes CAN embrulhados, lidos no serviço FFE0/FFE4 e escritos no
 * FFE5/FFE9 em pedaços de 20 bytes.
 *
 * Pacote: `AA AA <id 4B LE> <dados 8B> <len> <ch> <formato> <tipo> [dados estendidos] <soma> 55 55`, com
 * escape 0xA5 antes de 0xAA, 0x55 e 0xA5. A soma é a soma de bytes entre o cabeçalho e ela mesma.
 * Quando len = 0xFE, os 4 primeiros bytes de "dados" dizem o tamanho dos dados estendidos.
 *
 * Fluxo, como o WheelLog: envia a senha (padrão "000000") 6 vezes, pede os dados lentos (modelo) e depois
 * repete o pedido de dados rápidos a cada 250 ms. Os dados rápidos (id 0x0F550113) trazem, little-endian:
 * [12..15]+[16..19] velocidade das rodas (÷ 3812·2 × 3,6 = km/h), [20..23] corrente s32 0,01 A,
 * [24..27] tensão 0,01 V, [32] temperatura °C. Não há PWM: ele é estimado com [calibration].
 *
 * Fonte: WheelLog (GPL-3.0), utils/InMotionAdapter.java e InmotionAdapterTest.kt.
 */
class InmotionV1Decoder(
    private val calibration: PwmCalibration = PwmCalibration(45f, 84f, 0.90f),
    private val password: String = "000000",
) : WheelDecoder {
    private val unpacker = Unpacker()
    private var passwordsSent = 0
    private var gotSlow = false
    private var speedFactor = 3812.0
    private var lastRequestMs = Long.MIN_VALUE / 2

    override var model: String? = null
        private set

    override val writeChunk: Int get() = 20

    override fun reset() {
        unpacker.reset(); passwordsSent = 0; gotSlow = false; speedFactor = 3812.0
        model = null; lastRequestMs = Long.MIN_VALUE / 2
    }

    override fun feed(bytes: ByteArray, nowMs: Long): List<Telemetry> {
        val out = ArrayList<Telemetry>()
        for (b in bytes) {
            val raw = unpacker.add(b) ?: continue
            handle(raw, nowMs)?.let { out += it }
        }
        return out
    }

    /** [p] já sem escape: AA AA ... soma 55 55. */
    private fun handle(p: ByteArray, nowMs: Long): Telemetry? {
        if (p.size < 21) return null
        val body = p.copyOfRange(2, p.size - 3)
        var sum = 0
        for (v in body) sum = (sum + v) and 0xFF
        if (sum != (p[p.size - 3].toInt() and 0xFF)) return null
        if (body.size < 16) return null
        val id = body.s32le(0)
        val len = body[12].toInt() and 0xFF
        val ex: ByteArray? = if (len == 0xFE) {
            val l = body.s32le(4)
            if (l == body.size - 16) body.copyOfRange(16, 16 + l) else null
        } else null
        when (id) {
            ID_PIN -> { passwordsSent = Int.MAX_VALUE; return null }
            ID_SLOW -> { if (ex != null) { gotSlow = true; parseSlow(ex) }; return null }
            ID_FAST -> {}
            else -> return null
        }
        val d = ex ?: return null
        if (d.size < 35) return null
        val speed = abs((d.s32le(12).toDouble() + d.s32le(16).toDouble()) / (speedFactor * 2.0) * 3.6).toFloat()
        val voltage = d.s32le(24) / 100f
        return Telemetry(
            timestampMs = nowMs,
            speedKmh = speed,
            pwm = calibration.estimate(speed, voltage),
            voltage = voltage,
            currentA = d.s32le(20) / 100f,
            batteryPercent = null,
            temperatureC = d[32].toFloat(),
            pwmEstimated = true,
        )
    }

    private fun parseSlow(d: ByteArray) {
        if (d.size < 108) return
        val id = (if (d[107] > 0) d[107].toString() else "") + d[104].toString()
        val name = SLOW_MODELS[id]
        model = "Inmotion " + (name ?: "V8")
        speedFactor = if (id == "1" || id == "5" || id == "30") 1000.0 else if (id == "6") 3810.0 else 3812.0
    }

    override fun poll(nowMs: Long): ByteArray? {
        if (nowMs - lastRequestMs < 250) return null
        lastRequestMs = nowMs
        return when {
            passwordsSent < 6 -> { passwordsSent++; pin() }
            !gotSlow -> can(ID_SLOW, ByteArray(8) { -1 }, remote = true)
            else -> can(ID_FAST, ByteArray(8) { -1 })
        }
    }

    private fun pin(): ByteArray {
        val p = (password + "000000").take(6).toByteArray(Charsets.US_ASCII)
        return can(ID_PIN, byteArrayOf(p[0], p[1], p[2], p[3], p[4], p[5], 0, 0))
    }

    private class Unpacker {
        private var buf = ByteArrayOutputStream()
        private var collecting = false
        private var escaped = false
        private var prev = -1
        private var lenEx = 0
        private var lenP = 0

        fun reset() { buf = ByteArrayOutputStream(); collecting = false; escaped = false; prev = -1; lenEx = 0; lenP = 0 }

        fun add(b: Byte): ByteArray? {
            val c = b.toInt() and 0xFF
            if (!collecting) {
                if (c == 0xAA && prev == 0xAA) {
                    buf = ByteArrayOutputStream(); buf.write(0xAA); buf.write(0xAA)
                    collecting = true; escaped = false; lenEx = 0; lenP = 0; prev = -1
                } else prev = c
                return null
            }
            if (c == 0xA5 && !escaped) { escaped = true; return null }
            val literal = escaped
            escaped = false
            buf.write(c)
            val sz = buf.size()
            if (sz == 7) lenEx = c else if (sz == 15) lenP = c
            if (lenP == 0xFE && sz > lenEx + 21) { reset(); return null }
            val end = !literal && c == 0x55 && prev == 0x55 && (sz == lenEx + 21 || lenP != 0xFE)
            prev = if (literal) -1 else c
            if (end) { collecting = false; prev = -1; return buf.toByteArray() }
            return null
        }
    }

    companion object {
        const val ID_FAST = 0x0F550113
        const val ID_SLOW = 0x0F550114
        const val ID_PIN = 0x0F550307

        private val SLOW_MODELS = mapOf(
            "50" to "V5", "51" to "V5+", "52" to "V5F", "53" to "V5D", "80" to "V8", "86" to "V8F", "87" to "V8S",
            "85" to "Glide 3", "100" to "V10S", "101" to "V10SF", "140" to "V10", "141" to "V10F", "142" to "V10T", "143" to "V10FT",
        )

        /** Pacote CAN de 8 bytes de dados (canal 5), com escape e soma. */
        fun can(id: Int, data: ByteArray, remote: Boolean = false): ByteArray {
            val body = ByteArrayOutputStream()
            body.write(id and 0xFF); body.write((id shr 8) and 0xFF); body.write((id shr 16) and 0xFF); body.write((id shr 24) and 0xFF)
            body.write(data)
            body.write(8); body.write(5); body.write(0); body.write(if (remote) 1 else 0)
            val bytes = body.toByteArray()
            var sum = 0
            for (v in bytes) sum = (sum + v) and 0xFF
            val out = ByteArrayOutputStream()
            out.write(0xAA); out.write(0xAA)
            for (v in bytes) {
                val c = v.toInt() and 0xFF
                if (c == 0xAA || c == 0x55 || c == 0xA5) out.write(0xA5)
                out.write(c)
            }
            out.write(sum); out.write(0x55); out.write(0x55)
            return out.toByteArray()
        }
    }
}
