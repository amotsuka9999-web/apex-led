# apex-led

Controle de fitas LED COB para monociclo elétrico, com cores guiadas pela telemetria da
roda (velocidade e PWM). Base prevista: WheelLog (Android).

**Estado:** lógica central pronta e testada em JVM; módulos Android em rascunho (telas
ainda não compiladas); validação em hardware pendente. Ver [docs/relatorio-testes.md](docs/relatorio-testes.md).

## O que existe

| Módulo | Conteúdo |
|---|---|
| `pattern-engine` | motor de padrões: gradiente contínuo, gradiente de 3 cores, pulso, modo de transição (proposta), alerta crítico, sincronizado/defasado para até 10 placas |
| `led-device` | protocolo das placas, fila de comandos, reconexão, gerenciador de placas e simulador |
| `led-core` | controlador (telemetria → padrões → placas), regras do alerta crítico, persistência das configurações |

| `led-android` | enlace Bluetooth LE, busca de placas e telas (Compose) |
| `app` | app de bancada: as telas acima com uma roda simulada, para testar placas sem a roda |

Os três primeiros não dependem de Android. Os dois últimos só entram no build quando há
um SDK do Android configurado (`local.properties` ou `ANDROID_HOME`).

## Compilar e testar

Requer JDK 21.

```
./gradlew test
```

## Documentação

- [docs/questoes.md](docs/questoes.md): perguntas em aberto e o que cada uma bloqueia
- [docs/balanx-spec.md](docs/balanx-spec.md): levantamento do app de referência (incompleto)
- [docs/padroes-cor.md](docs/padroes-cor.md): mapeamentos de cor e exemplos
- [docs/protocolo-led.md](docs/protocolo-led.md): protocolo das placas (não verificado em hardware)
- [docs/wheellog-integracao.md](docs/wheellog-integracao.md): pontos de integração no WheelLog e licença
- [docs/arquitetura.md](docs/arquitetura.md): módulos e decisões
- [docs/checklist-campo.md](docs/checklist-campo.md): testes em campo
- [docs/relatorio-testes.md](docs/relatorio-testes.md): o que foi e o que não foi testado
- [CHANGELOG.md](CHANGELOG.md)

## Avisos

- Os padrões de cor são cosméticos. Os LEDs são um indicador adicional e não substituem
  os alarmes do app nem a atenção de quem pilota.
- O protocolo das placas foi escrito a partir de documentação aberta de terceiros e
  ainda não foi testado em uma placa real.
- Licença: a definir (ver Q11). O WheelLog é GPL-3.0, o que condiciona a licença do app final.
