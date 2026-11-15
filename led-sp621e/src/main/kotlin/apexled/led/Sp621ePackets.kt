package apexled.led

import apexled.core.ColorOrder
import apexled.core.Rgb

/**
 * Pacotes do protocolo BanlanX "v2" (família SP611E/SP617E/SP620E/SP621E), escritos na característica
 * FFE1 do serviço FFE0. Formato: `A0 <opcode> <n> <n bytes de dados>`.
 *
 * FONTE: projeto `monty68/uniled` (MIT), custom_components/uniled/lib/ble/banlanx2.py — engenharia reversa da
 * comunidade, NÃO documentação do fabricante. Cada builder abaixo tem teste de formato, mas nenhum foi
 * confirmado contra um SP621E físico por este projeto. Veja docs/protocolo-sp621e.md.
 *
 * O que NÃO está no `uniled` e portanto NÃO é inventado aqui: quantidade de pixels e tipo de CI
 * (WS2811/WS2812…). [pixelCount] e [icType] devolvem null até haver captura (docs/captura-ble.md).
 */
object Sp621ePackets {
    const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val CHARACTERISTIC_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"

    /** ID de fabricante nos dados de anúncio BLE (0x5053 = 20563, "SP"). */
    const val MANUFACTURER_ID = 20563

    const val HEADER = 0xA0

    object Op {
        const val POWER = 0x62
        const val EFFECT = 0x63
        const val CHIP_ORDER = 0x64
        const val BRIGHTNESS = 0x66
        const val SPEED = 0x67
        const val LENGTH = 0x68
        const val COLOR = 0x69
        const val LIGHT_MODE = 0x6A
        const val STATE_QUERY = 0x70
    }

    const val EFFECT_SOLID = 0xBE
    const val MAX_SPEED = 10
    const val MAX_LENGTH = 150

    private fun packet(op: Int, vararg data: Int): ByteArray =
        ByteArray(3 + data.size).also {
            it[0] = HEADER.toByte(); it[1] = op.toByte(); it[2] = data.size.toByte()
            data.forEachIndexed { i, v -> it[3 + i] = v.toByte() }
        }

    fun power(on: Boolean) = packet(Op.POWER, if (on) 1 else 0)

    /** Seleciona efeito; [EFFECT_SOLID] (0xBE) = cor sólida. */
    fun effect(id: Int): ByteArray {
        require(id in 0..255) { "efeito fora de 0..255" }
        return packet(Op.EFFECT, id)
    }

    /** Brilho geral 0..255 (efeitos dinâmicos). Na cor sólida o brilho vai no próprio comando de cor. */
    fun brightness(level: Int) = packet(Op.BRIGHTNESS, level.coerceIn(0, 255))

    /** Velocidade do efeito, 1..10 (fora disso o controlador ignora, segundo o uniled). */
    fun speed(value: Int) = packet(Op.SPEED, value.coerceIn(1, MAX_SPEED))

    /** Comprimento do efeito, 1..150. */
    fun length(value: Int) = packet(Op.LENGTH, value.coerceIn(1, MAX_LENGTH))

    /** Cor sólida: R, G, B e nível (brilho) 0..255. Só tem efeito visível com o efeito 0xBE ativo. */
    fun color(c: Rgb, level: Int = 255) = packet(Op.COLOR, c.r, c.g, c.b, level.coerceIn(0, 255))

    /** 0 = efeito único; 1 = ciclo de efeitos dinâmicos. */
    fun lightMode(mode: Int) = packet(Op.LIGHT_MODE, mode.coerceIn(0, 1))

    /** Ordem dos canais; índice = posição na lista de permutações de "RGB" (ver [ColorOrder]). */
    fun chipOrder(order: ColorOrder) = packet(Op.CHIP_ORDER, order.index)

    fun stateQuery() = byteArrayOf(HEADER.toByte(), Op.STATE_QUERY.toByte(), 0x00)

    /** Sem comando conhecido — aguardando captura do BanlanX. */
    @Suppress("UNUSED_PARAMETER") fun pixelCount(count: Int): ByteArray? = null

    /** Sem comando conhecido — aguardando captura do BanlanX. */
    @Suppress("UNUSED_PARAMETER") fun icType(index: Int): ByteArray? = null
}

/** Estado lido da notificação de status (`53 43 ...`). */
data class Sp621eStatus(
    val power: Boolean,
    val lightMode: Int,
    val effect: Int,
    val order: ColorOrder,
    val brightness: Int,
    val speed: Int,
    val length: Int,
    val color: Rgb,
)

/**
 * Remonta e interpreta a notificação de status. Formato (uniled): `53 43 <nº do pacote> <tam. total>
 * <tam. deste pacote> <dados>`; o status completo tem pelo menos 10 bytes de dados:
 * [0] ligado, [1] modo, [2] efeito, [3] ordem, [4] brilho, [5] velocidade, [6] comprimento, [7..9] RGB.
 */
class Sp621eStatusParser {
    private val payload = ArrayList<Byte>()
    private var expected = 0
    private var lastPacket = 0

    fun reset() { payload.clear(); expected = 0; lastPacket = 0 }

    fun feed(data: ByteArray): Sp621eStatus? {
        if (data.size <= 5 || data[0] != 0x53.toByte() || data[1] != 0x43.toByte()) return null
        val number = data[2].toInt() and 0xFF
        val total = data[3].toInt() and 0xFF
        val part = data[4].toInt() and 0xFF
        if (number == 1) {
            payload.clear(); expected = total; lastPacket = 1
        } else {
            if (lastPacket != number - 1 || total != expected) { reset(); return null }
            lastPacket = number
        }
        for (i in 5 until minOf(data.size, 5 + part)) payload.add(data[i])
        if (payload.size < expected) return null
        val p = payload.map { it.toInt() and 0xFF }
        reset()
        if (p.size < 10) return null
        return Sp621eStatus(
            power = p[0] == 1, lightMode = p[1], effect = p[2], order = ColorOrder.fromIndex(p[3]),
            brightness = p[4], speed = p[5], length = p[6], color = Rgb(p[7], p[8], p[9]),
        )
    }
}
