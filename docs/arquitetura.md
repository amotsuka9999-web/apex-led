# Arquitetura e decisões

## Módulos

```
pattern-engine   Kotlin puro, sem dependências.
                 (velocidade, PWM, crítico, configuração, tempo) → cor/brilho por placa.

led-device       Kotlin + corrotinas. Depende de pattern-engine.
                 LedTransport   enlace bruto com uma placa (conectar, escrever, notificações)
                 LedProtocol    bytes de uma família de controladoras
                 BoardConnection  uma placa: reconexão, fila de comandos, quadro mais recente
                 BoardManager   até 10 placas, com nome e endereço
                 sim/           SimulatedBoard: placa em memória para testes sem hardware

led-core         Kotlin + corrotinas + JSON. Depende dos dois acima.
                 LedController  telemetria → motor → placas; modo padrão e modo manual
                 CriticalMonitor / SafetyConfig   quando o vermelho acende
                 SettingsStore  persistência entre sessões

(futuro) app     Fork do WheelLog + ponte de telemetria + LedTransport em BLE + telas.
```

Nenhum dos três módulos depende de Android. Eles compilam e são testados em JVM comum e
entram no fork do WheelLog como dependências, sem alteração.

## Fluxo de um quadro

1. A roda envia telemetria; o WheelLog decodifica (thread principal).
2. A ponte chama `LedController.onTelemetry(...)`.
3. O controlador avalia o alerta na leitura bruta, suaviza a entrada, guarda no histórico
   e chama `PatternEngine.frames(...)`.
4. `BoardManager.showFrames` entrega um quadro a cada `BoardConnection`.
5. Cada conexão, em sua corrotina, envia só se o quadro mudou (ou se venceu o reforço).

Além da telemetria, um relógio de 50 ms repete os passos 3 a 5, porque pulsos e
defasagem mudam com o tempo mesmo com a telemetria parada.

## Decisões

| # | Decisão | Motivo |
|---|---|---|
| D1 | Motor como função pura, com o tempo como argumento | testável nos limites e nas transições; mapeamento previsível e documentável |
| D2 | Enlace (`LedTransport`) separado dos bytes (`LedProtocol`) | o modelo da placa é desconhecido; permite trocar o protocolo e simular a placa |
| D3 | Só o quadro mais recente é enviado; quadros antigos não ficam na fila | num enlace lento a cor mostrada é a atual, e o vermelho não espera atrás de cores velhas |
| D4 | O alerta crítico passa na frente dos comandos em espera e é reforçado a cada 500 ms | um pacote perdido não pode deixar o alerta sem aparecer |
| D5 | A configuração do alerta (`SafetyConfig`) fica fora da configuração de padrões; alarme do app é sempre crítico | requisito: nenhum padrão desliga o alerta |
| D6 | O alerta é avaliado na leitura bruta e nunca é defasado | suavização e defasagem são cosméticas e não podem atrasar o alerta |
| D7 | O módulo só lê o estado de alarme do app; não altera os alarmes do WheelLog | requisito: padrões não ocultam, atrasam nem substituem alertas do app |
| D8 | Limite máximo de brilho é um corte aplicado a todo quadro e não pode ser menor que 5 % | brilho previsível; o limite não serve para esconder o alerta |
| D9 | Defasagem implementada reavaliando o padrão sobre um histórico de telemetria | mantém o motor puro e vale também para a fase dos pulsos |
| D10 | Configurações em JSON próprio, versionado, com classes de arquivo separadas das de domínio | formato estável; o motor continua sem dependências |
| D11 | `FileSettingsStore` usa só `java.io` | o WheelLog tem `minSdk 21`, onde `java.nio.file` não existe |
| D12 | Versões de Kotlin, corrotinas e bytecode iguais às do WheelLog | os módulos entram no fork sem conflito |
| D13 | Pulso limitado a 3 Hz | fotossensibilidade e folga do enlace BLE (a confirmar, Q13) |
| D14 | Funções arriscadas da placa (tipo de LED, ordem dos canais) não implementadas | valor errado pode apagar a fita; só depois de teste em hardware |
| D15 | Código e comentários em inglês; documentação em português | o código segue a base (WheelLog); a documentação segue o pedido |

Valores provisórios que dependem de respostas estão em [questoes.md](questoes.md).

## Como trocar ou adicionar um protocolo

1. Implementar `LedProtocol` (bytes de entrar em cor sólida, de um quadro, dos comandos
   suportados, e leitura do status).
2. Registrar no `ProtocolRegistry`.
3. Opcional: um `BoardFirmware` correspondente para o simulador.

Nada em `BoardConnection`, `BoardManager`, `LedController` ou no motor muda.
