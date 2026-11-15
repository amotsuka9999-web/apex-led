package apexled.wheel

import apexled.core.Telemetry

/**
 * Decodificador de protocolo de roda. Classes puras (sem Android): recebem os bytes das notificações BLE
 * na ordem em que chegam (os frames podem vir fatiados em vários pacotes) e devolvem telemetria.
 *
 * Referência de formato: WheelLog (Wheellog.Android, GPL-3.0) — GotwayAdapter.java e VeteranAdapter.java,
 * commit f44e39e. Reimplementado a partir do layout dos frames; veja docs/wheel-protocolos.md.
 */
interface WheelDecoder {
    /** Modelo identificado pelo protocolo (ex.: "Nosfet Apex"), se o frame informar. */
    val model: String?

    /** Alimenta com um pacote recebido; devolve uma telemetria por frame de dados completo. */
    fun feed(bytes: ByteArray, nowMs: Long): List<Telemetry>

    fun reset()
}

internal fun ByteArray.u16(i: Int): Int = ((this[i].toInt() and 0xFF) shl 8) or (this[i + 1].toInt() and 0xFF)
internal fun ByteArray.s16(i: Int): Int = u16(i).toShort().toInt()
internal fun ByteArray.u32(i: Int): Long =
    (u16(i).toLong() shl 16) or u16(i + 2).toLong()

/** Remove os [count] primeiros bytes de uma lista de bytes mutável. */
internal fun MutableList<Byte>.dropFirst(count: Int) { repeat(count.coerceAtMost(size)) { removeAt(0) } }

/** Tenta Gotway/Begode e Veteran ao mesmo tempo; usa o que decodificar primeiro. */
class AutoDecoder(
    private val gotway: GotwayDecoder = GotwayDecoder(),
    private val veteran: VeteranDecoder = VeteranDecoder(),
) : WheelDecoder {
    private var chosen: WheelDecoder? = null
    override val model: String? get() = (chosen ?: veteran.takeIf { it.model != null })?.model

    override fun feed(bytes: ByteArray, nowMs: Long): List<Telemetry> {
        chosen?.let { return it.feed(bytes, nowMs) }
        val v = veteran.feed(bytes, nowMs)
        val g = gotway.feed(bytes, nowMs)
        if (v.isNotEmpty()) { chosen = veteran; return v }
        if (g.isNotEmpty()) { chosen = gotway; return g }
        return emptyList()
    }

    override fun reset() { chosen = null; gotway.reset(); veteran.reset() }
}
