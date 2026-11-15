package apexled.wheel

import apexled.core.Telemetry
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Inmotion "V2" (V9, V11, V11y, V12, V12S, V13, V14 e afins), serviço Nordic UART 6E400001
 * (notificação 6E400003, escrita 6E400002).
 *
 * Pacote: `AA AA <flags> <len> <cmd> <dados len-1> <xor>`; bytes 0xAA/0xA5 dentro do pacote vêm precedidos
 * de 0xA5 (escape). O checksum é o XOR de flags..dados. A roda só responde a pedidos, então [poll] envia em
 * sequência: tipo do carro (para saber o layout), versões (V11 antigo × novo) e, depois, dados em tempo real
 * a cada 250 ms, como o WheelLog.
 *
 * Fonte: WheelLog (GPL-3.0), utils/InmotionAdapterV2.java e InmotionAdapterV2Test.kt.
 */
class InmotionV2Decoder : WheelDecoder {
    private enum class Layout { V11_OLD, V11_NEW, V12, V13 }

    private var layout: Layout? = null
    private var series = 0
    private var gotVersions = false
    private var lastRequestMs = Long.MIN_VALUE / 2
    private var step = 0
    private val unpacker = Unpacker()

    override var model: String? = null
        private set

    override fun reset() {
        layout = null; series = 0; model = null; gotVersions = false
        lastRequestMs = Long.MIN_VALUE / 2; step = 0; unpacker.reset()
    }

    override fun feed(bytes: ByteArray, nowMs: Long): List<Telemetry> {
        val out = ArrayList<Telemetry>()
        for (b in bytes) {
            val pkt = unpacker.add(b) ?: continue
            handle(pkt, nowMs)?.let { out += it }
        }
        return out
    }

    private fun handle(p: ByteArray, nowMs: Long): Telemetry? {
        // p = AA AA flags len cmd dados... check
        if (p.size < 6) return null
        var x = 0
        for (i in 2 until p.size - 1) x = x xor (p[i].toInt() and 0xFF)
        if (x != (p.last().toInt() and 0xFF)) return null
        val flags = p[2].toInt() and 0xFF
        val len = p[3].toInt() and 0xFF
        val cmd = p[4].toInt() and 0x7F
        val data = p.copyOfRange(5, (len + 4).coerceAtMost(p.size - 1))
        if (flags == FLAG_INITIAL && cmd == CMD_MAIN_INFO && data.isNotEmpty()) {
            when (data[0].toInt()) {
                0x01 -> if (data.size >= 4) setCar(data[2].toInt(), data[3].toInt())
                0x06 -> if (data.size >= 24) { gotVersions = true; if (series == 6 && layout != null) layout = mainBoardLayoutV11(data) }
            }
            return null
        }
        if (flags != FLAG_DEFAULT || cmd != CMD_REALTIME) return null
        val l = layout ?: return null
        return parseRealtime(l, data, nowMs)
    }

    private fun setCar(s: Int, type: Int) {
        series = s
        val id = s * 10 + type
        model = MODELS[id] ?: "Inmotion V$s"
        layout = when (id) {
            61 -> Layout.V11_OLD
            71, 72, 73 -> Layout.V12
            else -> Layout.V13 // V11y, V13, V14, V12S, V9 e modelos novos usam o mesmo layout
        }
    }

    /** V11: placa principal anterior a 1.4 usa o layout antigo. */
    private fun mainBoardLayoutV11(d: ByteArray): Layout {
        val main1 = d[14].toInt(); val main2 = d[13].toInt()
        return if (main1 < 2 && main2 < 4) Layout.V11_OLD else Layout.V11_NEW
    }

    private fun parseRealtime(l: Layout, d: ByteArray, nowMs: Long): Telemetry? {
        fun temp(i: Int) = ((d[i].toInt() and 0xFF) + 80 - 256).toFloat()
        val need = when (l) { Layout.V11_OLD -> 38; Layout.V11_NEW -> 43; Layout.V12 -> 41; Layout.V13 -> 59 }
        if (d.size < need) return null
        val voltage = d.u16le(0) / 100f
        val current = d.s16le(2) / 100f
        val (speed, pwm, battery, mos) = when (l) {
            Layout.V11_OLD -> Quad(d.s16le(4), d.u16le(36), (d[16].toInt() and 0x7F).toFloat(), temp(17))
            Layout.V11_NEW -> Quad(d.s16le(4), d.s16le(8), d.u16le(28) / 100f, temp(42))
            Layout.V12 -> Quad(d.s16le(4), d.s16le(8), d.u16le(24) / 100f, temp(40))
            Layout.V13 -> Quad(d.s16le(8), d.s16le(14), (d.u16le(34) + d.u16le(36)) / 200f, temp(58))
        }
        return Telemetry(
            timestampMs = nowMs,
            speedKmh = abs(speed) / 100f,
            pwm = abs(pwm) / 10000f,
            voltage = voltage,
            currentA = current,
            batteryPercent = battery.coerceIn(0f, 100f),
            temperatureC = mos,
            pwmEstimated = false,
        )
    }

    private data class Quad(val speed: Int, val pwm: Int, val battery: Float, val mos: Float)

    override fun poll(nowMs: Long): ByteArray? {
        if (nowMs - lastRequestMs < 250) return null
        lastRequestMs = nowMs
        step++
        return when {
            layout == null -> message(FLAG_INITIAL, CMD_MAIN_INFO, byteArrayOf(0x01))
            series == 6 && !gotVersions && step < 20 -> message(FLAG_INITIAL, CMD_MAIN_INFO, byteArrayOf(0x06))
            else -> message(FLAG_DEFAULT, CMD_REALTIME, ByteArray(0))
        }
    }

    /** Escape de 0xAA/0xA5 e cabeçalho AA AA. */
    private class Unpacker {
        private var buf = ByteArrayOutputStream()
        private var collecting = false
        private var escaped = false
        private var prevAa = false
        private var len = -1

        fun reset() { buf = ByteArrayOutputStream(); collecting = false; escaped = false; prevAa = false; len = -1 }

        fun add(b: Byte): ByteArray? {
            val c = b.toInt() and 0xFF
            if (!collecting) {
                if (c == 0xAA && prevAa) {
                    buf = ByteArrayOutputStream(); buf.write(0xAA); buf.write(0xAA)
                    collecting = true; escaped = false; len = -1; prevAa = false
                } else prevAa = c == 0xAA
                return null
            }
            if (c == 0xA5 && !escaped) { escaped = true; return null }
            escaped = false
            buf.write(c)
            if (buf.size() == 4) len = c
            if (len >= 0 && buf.size() == len + 5) {
                collecting = false; prevAa = false
                return buf.toByteArray()
            }
            if (buf.size() > 600) reset()
            return null
        }
    }

    companion object {
        private const val FLAG_INITIAL = 0x11
        private const val FLAG_DEFAULT = 0x14
        private const val CMD_MAIN_INFO = 0x02
        private const val CMD_REALTIME = 0x04

        private val MODELS = mapOf(
            61 to "Inmotion V11", 62 to "Inmotion V11y", 71 to "Inmotion V12 HS", 72 to "Inmotion V12 HT",
            73 to "Inmotion V12 PRO", 81 to "Inmotion V13", 82 to "Inmotion V13 PRO", 91 to "Inmotion V14 50GB",
            92 to "Inmotion V14 50S", 111 to "Inmotion V12S", 121 to "Inmotion V9",
        )

        /** Monta `AA AA <flags> <len> <cmd> <dados> <xor>` com escape. */
        fun message(flags: Int, cmd: Int, data: ByteArray): ByteArray {
            val body = byteArrayOf(flags.toByte(), (data.size + 1).toByte(), cmd.toByte()) + data
            var x = 0
            for (v in body) x = x xor (v.toInt() and 0xFF)
            val out = ByteArrayOutputStream()
            out.write(0xAA); out.write(0xAA)
            for (v in body) {
                val c = v.toInt() and 0xFF
                if (c == 0xAA || c == 0xA5) out.write(0xA5)
                out.write(c)
            }
            out.write(x) // como o WheelLog: o checksum vai sem escape
            return out.toByteArray()
        }
    }
}
