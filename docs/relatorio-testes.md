# Relatório de testes

Data: 2026-11-14. Ambiente: JVM (JDK 21, bytecode Java 17), sem Android SDK e sem hardware.

## O que foi testado

178 testes unitários, todos passando, já com os Modos 1, 2 e 3, o crítico em 96 % e o
protocolo `sp621e` da especificação revisada.

Observação sobre a última execução: o Maven Central passou a limitar os downloads deste
ambiente (HTTP 429) e o Gradle deixou de resolver dependências. A execução final dos 178
testes foi feita chamando diretamente o compilador Kotlin 2.2.10 e o JUnit 5 que já estavam no
cache local, com avisos tratados como erro. Vale repetir com `./gradlew test` em uma
máquina sem esse limite.

**Módulos Android (`led-android`, `app`): o enlace Bluetooth e a busca compilaram com o
Gradle antes do limite; as telas e o app de bancada foram escritos depois e NÃO foram
compilados nem executados.** Trate-os como rascunho até o primeiro
`./gradlew :app:assembleDebug`.

| Módulo | Testes | O que cobrem |
|---|---:|---|
| `pattern-engine` | 62 | normalização da entrada (0, 100, fora da faixa, negativo, NaN); P1 (extremos, pontos intermediários, suavidade, ausência de magenta); P2 (três pontos, interpolação, continuidade no meio, meio deslocado); P3 (onda quadrada, ciclo ativo, respiração, faixas com extremos inclusos, sobreposição, repouso, validação); modo de transição; brilho e limite máximo para todos os padrões; alerta crítico (sobrepõe tudo, ignora brilho geral, não é defasado); multi-placa sincronizado e defasado; histórico de telemetria; exemplos do documento de padrões |
| `led-device` | 50 | codificação byte a byte dos dois protocolos; leitura de status e rejeição de mensagens malformadas; conexão, envio só do quadro mais recente em enlace lento, reforço periódico, prioridade do alerta sobre a fila, ordem dos comandos, espaçamento entre escritas, reconexão com recuo exponencial, tempo limite de conexão e de escrita, fila limitada, parada e reinício; até 10 placas, duplicatas, renomear, reordenar, remover, falha isolada de uma placa |
| `led-core` | 54 | limiares do alerta (cada um no limite e logo abaixo), retenção, perda de telemetria; suavização; gravação e leitura de todas as configurações, arquivo corrompido, valores fora da faixa, versão futura; controlador de ponta a ponta sobre placas simuladas: padrão por PWM e por velocidade, troca de padrão em uso, defasagem, alerta por alarme do app e por limiar, alerta sem atraso por suavização ou defasagem, alerta sobre o modo manual com restauração, limite de brilho conferido nos bytes enviados, placa que cai e volta, persistência entre sessões, falha ao gravar |

Os testes de tempo usam relógio virtual: são determinísticos e não dependem da
velocidade da máquina.

## O que não foi possível testar sem hardware

Tudo o que depende de uma placa, de uma roda ou de um celular reais. Em particular:

1. **O protocolo.** Os dois codecs foram escritos a partir de documentação aberta de
   terceiros. O simulador foi escrito a partir da mesma documentação, então os testes
   provam que codec e simulador concordam entre si, **não** que uma placa real aceita
   esses bytes. Qual família o modelo real usa também é desconhecido (Q1, Q7).
2. **Bluetooth do Android.** Não existe ainda a implementação de `LedTransport` sobre
   BLE; busca, permissões, conexão, MTU e comportamento em segundo plano não foram
   exercitados.
3. **Dez conexões simultâneas** mais a roda, no celular-alvo (Q14).
4. **Temporização real:** quantos comandos por segundo a placa absorve, latência entre o
   alarme e o vermelho, tempo real de reconexão.
5. **Resposta de luz:** se o nível 0–255 é linear na percepção, se nível 0 apaga, se as
   cores batem (ordem dos canais), consumo de corrente no brilho máximo.
6. **Desgaste de memória da placa** com envio contínuo de cores.
7. **Telemetria real:** que o PWM do Nosfet Apex chega em 0–100 % como o código do
   WheelLog indica; taxa de atualização; ruído (ajuste da suavização).
8. **Alarmes do WheelLog em segundo plano** como fonte do alerta.
9. **Estado da placa quando o celular some** no meio de um alerta ou de um padrão.

Esses pontos estão no [checklist de campo](checklist-campo.md).

## O que não foi feito

- **Paridade com o Balanx (R1), parte de interface:** a spec está incompleta
  ([balanx-spec.md](balanx-spec.md)); telas, botões e valores padrão do app não puderam
  ser levantados sem o app. As funções da placa com comando documentado estão
  codificadas e testadas contra o simulador; timers, tipo de LED, ordem dos canais e
  edição de efeito personalizado não estão.
- **App Android:** as telas existem como rascunho não compilado (ver acima); a integração com o WheelLog não foi feita. Motivos: base não
  confirmada (Q6), SDK do Android indisponível neste ambiente (download bloqueado pela
  rede) e spec incompleta. O desenho da integração está em
  [wheellog-integracao.md](wheellog-integracao.md).
- **Pré-visualização da faixa de cores:** a função que calcula as cores existe e é
  testada (`PatternEngine.preview`); falta desenhá-la na tela.

## Como reproduzir

```
./gradlew test
```

Relatórios em `<módulo>/build/reports/tests/test/index.html`.
