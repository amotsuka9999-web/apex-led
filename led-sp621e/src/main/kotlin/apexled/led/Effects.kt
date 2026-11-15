package apexled.led

import apexled.core.Rgb

enum class EffectCategory(val label: String) {
    SOLID("Cor sólida"), RAINBOW("Arco-íris"), FIRE("Fogo"), COMET("Cometa"), METEOR("Meteoro"),
    SNAKE("Cobra gradiente"), WAVE("Onda"), STARS("Estrelas"), BG_STARS("Estrelas com fundo"),
    BREATH("Respiração"), STACK("Empilhar"), SPIN("Giro"), GRADIENT("Gradiente"),
}

/** Efeito do controlador. [palette] é só uma aproximação para a prévia na tela. */
data class LedEffect(val id: Int, val name: String, val category: EffectCategory, val palette: List<Rgb>)

/**
 * Catálogo dos efeitos dinâmicos do SP621E (ids 0x01..0x8E e 0xBE = cor sólida), na ordem do uniled
 * (banlanx2.py, BANLANX2_EFFECTS_RGB). Efeitos de música (0xC9+) não se aplicam ao SP621E (sem microfone).
 * Os nomes em português são traduções livres deste projeto; os BanlanX mostram os próprios nomes.
 */
object Sp621eEffects {
    private val RED = Rgb(255, 0, 0)
    private val GREEN = Rgb(0, 255, 0)
    private val BLUE = Rgb(0, 0, 255)
    private val YELLOW = Rgb(255, 220, 0)
    private val CYAN = Rgb(0, 255, 255)
    private val PURPLE = Rgb(170, 0, 255)
    private val WHITE = Rgb(255, 255, 255)

    private val seven = listOf("Vermelho" to RED, "Verde" to GREEN, "Azul" to BLUE, "Amarelo" to YELLOW,
        "Ciano" to CYAN, "Roxo" to PURPLE, "Branco" to WHITE)
    private val six = seven.take(6)

    // Pares na ordem do uniled (R-G R-B R-Y R-C R-P R-W G-B G-Y ... C-W P-W): é a ordem natural i<j sobre
    // [vermelho, verde, azul, amarelo, ciano, roxo, branco].
    private val pairs: List<Pair<Pair<String, Rgb>, Pair<String, Rgb>>> = buildList {
        for (i in seven.indices) for (j in i + 1 until seven.size) add(seven[i] to seven[j])
    }

    val all: List<LedEffect> = buildList {
        add(LedEffect(Sp621ePackets.EFFECT_SOLID, "Cor sólida", EffectCategory.SOLID, emptyList()))
        var id = 0x01
        fun add(name: String, cat: EffectCategory, vararg colors: Rgb) { add(LedEffect(id++, name, cat, colors.toList())) }
        val rainbow = arrayOf(RED, YELLOW, GREEN, CYAN, BLUE, PURPLE)
        add("Arco-íris", EffectCategory.RAINBOW, *rainbow)
        add("Arco-íris meteoro", EffectCategory.RAINBOW, *rainbow)
        add("Arco-íris estrelas", EffectCategory.RAINBOW, *rainbow)
        add("Arco-íris giro", EffectCategory.RAINBOW, *rainbow)
        listOf("vermelho/amarelo" to (RED to YELLOW), "vermelho/roxo" to (RED to PURPLE),
            "verde/amarelo" to (GREEN to YELLOW), "verde/ciano" to (GREEN to CYAN),
            "azul/roxo" to (BLUE to PURPLE), "azul/ciano" to (BLUE to CYAN))
            .forEach { (n, c) -> add("Fogo $n", EffectCategory.FIRE, c.first, c.second) }
        seven.forEach { (n, c) -> add("Cometa $n".lowercase().replaceFirstChar { it.uppercase() }, EffectCategory.COMET, c) }
        seven.forEach { (n, c) -> add("Meteoro ${n.lowercase()}", EffectCategory.METEOR, c) }
        pairs.forEach { (a, b) -> add("Cobra ${a.first.lowercase()}-${b.first.lowercase()}", EffectCategory.SNAKE, a.second, b.second) }
        seven.forEach { (n, c) -> add("Onda ${n.lowercase()}", EffectCategory.WAVE, c) }
        pairs.forEach { (a, b) -> add("Onda ${a.first.lowercase()}-${b.first.lowercase()}", EffectCategory.WAVE, a.second, b.second) }
        seven.forEach { (n, c) -> add("Estrelas ${n.lowercase()}", EffectCategory.STARS, c) }
        six.forEach { (n, c) -> add("Estrelas ${n.lowercase()} em fundo", EffectCategory.BG_STARS, c) }
        seven.forEach { (n, c) -> add("Estrelas ${n.lowercase()} em fundo branco", EffectCategory.BG_STARS, c, WHITE) }
        seven.forEach { (n, c) -> add("Respiração ${n.lowercase()}", EffectCategory.BREATH, c) }
        seven.forEach { (n, c) -> add("Empilhar ${n.lowercase()}", EffectCategory.STACK, c) }
        add("Empilhar colorido", EffectCategory.STACK, *rainbow)
        listOf("vermelho-verde" to (RED to GREEN), "verde-azul" to (GREEN to BLUE), "azul-amarelo" to (BLUE to YELLOW),
            "amarelo-ciano" to (YELLOW to CYAN), "ciano-roxo" to (CYAN to PURPLE), "roxo-branco" to (PURPLE to WHITE))
            .forEach { (n, c) -> add("Empilhar $n", EffectCategory.STACK, c.first, c.second) }
        add("Cobra vermelho-azul-branco", EffectCategory.SNAKE, RED, BLUE, WHITE)
        add("Cobra verde-amarelo-branco", EffectCategory.SNAKE, GREEN, YELLOW, WHITE)
        add("Cobra vermelho-verde-branco", EffectCategory.SNAKE, RED, GREEN, WHITE)
        add("Cobra vermelho-amarelo", EffectCategory.SNAKE, RED, YELLOW)
        add("Cobra vermelho-branco", EffectCategory.SNAKE, RED, WHITE)
        add("Cobra verde-branco", EffectCategory.SNAKE, GREEN, WHITE)
        seven.forEach { (n, c) -> add("Cometa girando ${n.lowercase()}", EffectCategory.SPIN, c) }
        seven.forEach { (n, c) -> add("Ponto girando ${n.lowercase()}", EffectCategory.SPIN, c) }
        seven.forEach { (n, c) -> add("Segmento girando ${n.lowercase()}", EffectCategory.SPIN, c) }
        add("Gradiente", EffectCategory.GRADIENT, *rainbow)
    }

    private val byId = all.associateBy { it.id }
    fun find(id: Int): LedEffect? = byId[id]
    val lastDynamicId: Int = 0x8E
}
