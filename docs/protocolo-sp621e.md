# Protocolo do SP621E (BanlanX "v2")

**Estado: não confirmado em hardware.** Fonte primária: [`monty68/uniled`](https://github.com/monty68/uniled) (MIT),
`custom_components/uniled/lib/ble/banlanx2.py` — onde o SP621E ("Mini SPI RGB Controller") é a classe `SP621E`
(`BanlanX2`, id `0x621E`, 3 canais, sem microfone). Confirmação cruzada: `ZephyrSai/rgb-strip-remote` agrupa
SP611E/SP617E/SP620E/SP621E no mesmo protocolo. É engenharia reversa da comunidade, não documentação do fabricante.

## Transporte
- Serviço `0000ffe0-0000-1000-8000-00805f9b34fb`, característica `0000ffe1-0000-1000-8000-00805f9b34fb` (escrita; status por notificação — o `uniled` não lista a característica de notificação explicitamente: **a confirmar**).
- Anúncio: dados de fabricante com ID `0x5053` (20563); o SP621E usa o primeiro byte `0x0D` ou `0x16`.

## Pacotes — `A0 <opcode> <n> <n bytes>`
| Ação | Bytes | Implementado |
|---|---|---|
| Ligar/desligar | `A0 62 01 <1/0>` | ✅ |
| Efeito | `A0 63 01 <id>` (`BE` = cor sólida) | ✅ |
| Ordem de cor | `A0 64 01 <0..5>` (permutações de RGB: RGB,RBG,GRB,GBR,BRG,BGR) | ✅ |
| Brilho | `A0 66 01 <0..255>` | ✅ |
| Velocidade | `A0 67 01 <1..10>` | ✅ |
| Comprimento | `A0 68 01 <1..150>` | ✅ |
| Cor | `A0 69 04 R G B <nível>` | ✅ |
| Modo de luz (0 efeito único, 1 ciclo) | `A0 6A 01 <0/1>` | pacote ✅, sem UI |
| Consulta de estado | `A0 70 00` | ✅ |
| **Tipo de CI (WS2811/WS2812…)** | **desconhecido** | ❌ |
| **Número de pixels** | **desconhecido** | ❌ |

Efeitos dinâmicos: ids `0x01..0x8E` (catálogo em `Sp621eEffects`, nomes em português são tradução livre).

Status (notificação `53 43 <nº pacote> <tam. total> <tam. deste> <dados>`): dados `[0]` ligado, `[1]` modo, `[2]` efeito, `[3]` ordem, `[4]` brilho, `[5]` velocidade, `[6]` comprimento, `[7..9]` RGB, depois bytes não decifrados e o nº de timers. O exemplo usado no teste é o comentado no código do `uniled`, não uma captura própria.

## Decisões do app baseadas nisto
- Quadros do SafetyEngine viram `A0 69 04 R G B FF` com o brilho **já aplicado nos canais RGB** (não dependem do significado exato do byte de nível; o quadro "apagado" do pisca é preto).
- A ordem de cor só é enviada quando o usuário a muda; nunca na sincronização automática.
- Tipo de CI e nº de pixels ficam salvos só no app até haver captura (`docs/captura-ble.md`).
