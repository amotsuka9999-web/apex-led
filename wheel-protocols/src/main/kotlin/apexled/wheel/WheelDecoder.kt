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

    /**
     * Pedido a enviar para a roda agora (consulta de dados ou keep-alive), ou null. O transporte chama a
     * cada [POLL_INTERVAL_MS]. Begode e Leaperkim/Nosfet enviam dados sozinhos; Kingsong e Inmotion precisam.
     */
    fun poll(nowMs: Long): ByteArray? = null

    /** Tamanho máximo de cada escrita BLE (0 = sem limite). Inmotion V5/V8/V10 exige pedaços de 20 bytes. */
    val writeChunk: Int get() = 0

    companion object {
        const val POLL_INTERVAL_MS = 25L
    }
}

internal fun ByteArray.u16le(i: Int): Int = (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)
internal fun ByteArray.s16le(i: Int): Int = u16le(i).toShort().toInt()
internal fun ByteArray.s32le(i: Int): Int =
    (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8) or
        ((this[i + 2].toInt() and 0xFF) shl 16) or ((this[i + 3].toInt() and 0xFF) shl 24)

internal fun ByteArray.u16(i: Int): Int = ((this[i].toInt() and 0xFF) shl 8) or (this[i + 1].toInt() and 0xFF)
internal fun ByteArray.s16(i: Int): Int = u16(i).toShort().toInt()
internal fun ByteArray.u32(i: Int): Long =
    (u16(i).toLong() shl 16) or u16(i + 2).toLong()

/** Remove os [count] primeiros bytes de uma lista de bytes mutável. */
internal fun MutableList<Byte>.dropFirst(count: Int) { repeat(count.coerceAtMost(size)) { removeAt(0) } }

/**
 * Para rodas no serviço FFE0/FFE1: tenta Gotway/Begode, Veteran/Leaperkim/Nosfet e Kingsong ao mesmo tempo e
 * fica com o primeiro que decodificar um frame válido. (Inmotion usa outros serviços BLE e é escolhida pelo
 * transporte antes daqui.)
 */
class AutoDecoder(
    private val gotway: GotwayDecoder = GotwayDecoder(),
    private val veteran: VeteranDecoder = VeteranDecoder(),
    private val kingsong: KingsongDecoder = KingsongDecoder(),
) : WheelDecoder {
    private var chosen: WheelDecoder? = null
    override val model: String? get() = (chosen ?: veteran.takeIf { it.model != null })?.model

    override fun feed(bytes: ByteArray, nowMs: Long): List<Telemetry> {
        chosen?.let { return it.feed(bytes, nowMs) }
        val v = veteran.feed(bytes, nowMs)
        val k = kingsong.feed(bytes, nowMs)
        val g = gotway.feed(bytes, nowMs)
        if (v.isNotEmpty()) { chosen = veteran; return v }
        if (k.isNotEmpty()) { chosen = kingsong; return k }
        if (g.isNotEmpty()) { chosen = gotway; return g }
        return emptyList()
    }

    /**
     * Só envia algo depois de reconhecer o protocolo: bytes de outro protocolo podem ser lidos como comando
     * (a Begode usa letras soltas como comandos). A Kingsong transmite os dados ao vivo sem pedido.
     */
    override fun poll(nowMs: Long): ByteArray? = chosen?.poll(nowMs)

    override fun reset() { chosen = null; gotway.reset(); veteran.reset(); kingsong.reset() }
}
