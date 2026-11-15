# Protocolos de roda

Fonte: WheelLog (`Wheellog.Android`, GPL-3.0, commit `f44e39e`): `GotwayAdapter.java`, `VeteranAdapter.java` e testes
em `app/src/test/.../GotwayAdapterTest.kt` / `VeteranAdapterTest.kt` (frames reais). Ambos usam serviço `FFE0` /
característica `FFE1` (notificações). Reimplementado em Kotlin puro (`wheel-protocols`).

## Nosfet Apex → protocolo Veteran
No WheelLog o Apex é `versão/1000 == 42` do `VeteranAdapter` (43 = Aero, 44 = Aeon). Frame `DC 5A 5C <len> …`, total `len+4` bytes;
se `len > 38`, CRC32 (big-endian) dos primeiros `len` bytes ao final. Campos big-endian: tensão `[4..5]` (0,01 V), velocidade `[6..7]` s16 (0,1 km/h),
distância `[8..11]`/`[12..15]` (u32 com palavras trocadas), corrente de fase `[16..17]` s16 (0,1 A), temperatura `[18..19]` s16 (0,01 °C),
versão `[28..29]`, **PWM de hardware `[34..35]` (0,01 %; WheelLog usa quando versão ≥ 2)**.
Testes: frames reais Nosfet **Aero** e **Aeon** (o WheelLog não tem frame real do Apex) + frame sintético com CRC válido para o Apex.
⚠️ Não há frame capturado do Apex: confirme na sua roda que o PWM de hardware bate com o do WheelLog.

## Begode A2 → protocolo Gotway/Begode
Frame de 24 bytes: `55 AA <16 bytes> <tipo> 18 5A 5A 5A 5A`. Tipo `00` (dados ao vivo): tensão `[2..3]` (0,01 V × escala), velocidade `[4..5]` s16 × 3,6 (0,01 km/h),
corrente de fase `[10..11]` (0,01 A), temperatura do IMU `raw/340 + 36,53` °C. Tipo `07` (firmwares novos): corrente de bateria, temperatura do motor e **PWM em %** `[8..9]`.
Firmware original **não envia PWM** → estimado: `PWM = vel ÷ (rotação_km/h ÷ rotação_V × tensão × fator)` (mesma fórmula e opções do WheelLog; padrões 50 km/h @ 84 V, fator 0,90).
Testes: frames reais de duas placas (16S e uma de 120 V com `voltageScale = 2`). ⚠️ Nenhum é um A2; o perfil inicial (16S, 67,2 V) é um palpite.

## Convenções do app
Velocidade sempre ≥ 0; corrente de bateria positiva = descarga. Em Gotway/Veteran sem corrente de bateria medida, ela é estimada (`PWM × |corrente de fase|`), então **regeneração não é detectável por corrente** — o flash de freio usa a desaceleração.
