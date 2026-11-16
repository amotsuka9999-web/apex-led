# Apex LED

App Android (Kotlin + Jetpack Compose + Material 3) que lê a telemetria de um monociclo elétrico
(**Nosfet Apex** ou **Begode A2**) e muda a cor/efeito de uma fita LED controlada por um módulo
**SP621E** (ecossistema BanlanX) conforme a **margem de segurança** restante (PWM, velocidade, bateria).

> ⚠️ **Aviso de segurança.** As luzes são um indicador **adicional**. Elas **não substituem** os alarmes
> nativos da roda, o tiltback nem a atenção de quem pilota. A leitura depende de Bluetooth e pode falhar ou
> atrasar. Fita âmbar = o app perdeu os dados da roda. O mesmo aviso aparece na primeira abertura do app.

## Estado do projeto (leia antes de usar)

| Parte | Estado |
|---|---|
| Lógica pura (SafetyEngine, decoders, pacotes do SP621E, controlador) | ✅ compila e tem testes unitários que passam (`./gradlew test`) |
| UI das 5 abas, simulador de ponta a ponta (UI → SafetyEngine → fita simulada) | ✅ compila (APK gerado); **não foi visto rodando** num aparelho/emulador pelo autor |
| Decoders Gotway/Begode e Veteran/Nosfet | ✅ testados com frames reais de outras rodas (testes do WheelLog) e frames sintéticos; ⚠️ **nunca com a sua roda** |
| Conexão BLE com a roda e com o SP621E (`GattClient`) | ⚠️ escrito a partir da API do Android, **nunca executado em hardware** |
| Protocolo do SP621E | ⚠️ vem de engenharia reversa da comunidade (`uniled`), **não confirmado em um SP621E real** |
| Tipo de CI e número de pixels no SP621E | ❌ comando **desconhecido** (não inventado): veja [docs/captura-ble.md](docs/captura-ble.md) |
| Barra de margem na fita real | ❌ o SP621E não tem comando conhecido por pixel: na fita real vira cor sólida; a barra completa aparece só na prévia |
| Ponte com o WheelLog | ⚠️ viável pelo código, **não testada em aparelho** |

Tudo que depende de hardware está em [docs/testes-em-campo.md](docs/testes-em-campo.md).

## Instalar no celular (jeito fácil)

1. No celular Android, abra este link:
   **https://github.com/amotsuka9999-web/apex-led/releases/latest/download/apex-led.apk**
2. Quando o download terminar, toque no arquivo `apex-led.apk`.
3. Se o Android pedir, permita "Instalar apps desconhecidos" para o navegador e toque em **Instalar**.
4. Abra o **Apex LED**. Para atualizar depois, repita o passo 1: o app é atualizado sem perder as configurações.

O link sempre aponta para o último build da `main` (ou de uma branch `claude/*`). Não precisa de login no GitHub.

## Relógio (Galaxy Watch / Wear OS)

O app do relógio é um controle remoto do app do celular. Ele tem 4 páginas, que você passa deslizando:
1. **Modo seguro:** liga/desliga e mostra velocidade, PWM e bateria, com a cor da fita em volta. Vibra quando a margem se esgota.
2. **Setas:** esquerda, direita e pisca-alerta (toque de novo para desligar).
3. **Cores fixas:** 8 cores e OFF.
4. **Modos:** 6 efeitos prontos.

**Bloco (tile):** na tela inicial do relógio, deslize até o fim, toque em **+** e escolha **Apex LED**. Ele liga o modo seguro e as setas sem abrir o app.

Escolher uma cor ou um modo pausa a segurança; ligue o modo seguro de novo na página 1.

**Instalar** (o relógio precisa do app do celular instalado, na mesma versão):
1. No relógio, abra **Configurações → Sobre o relógio → Software** e toque 5 vezes em **Versão do software** para liberar as Opções do desenvolvedor.
2. Em **Configurações → Opções do desenvolvedor**, ative **Depuração ADB** e **Depuração por Wi-Fi** (celular e relógio na mesma rede Wi-Fi).
3. No celular, baixe o APK do relógio: **https://github.com/amotsuka9999-web/apex-led/releases/latest/download/apex-led-relogio.apk**
4. Instale no relógio com um app de instalação por ADB no celular (por exemplo, *Wear Installer 2* ou *Bugjaeger*): toque em **Parear novo dispositivo** no relógio, digite o código no app do celular e escolha o `apex-led-relogio.apk`.

## Recursos

- **Grupo de até 10 fitas**, cada uma com uma posição: Geral, Esquerda, Direita, Capacete ou Na roda (gira).
  - Seta: pisca âmbar nas fitas do lado (e nas "Geral"); a da roda e a do capacete só no pisca-alerta.
  - Capacete: lanterna vermelha fraca e vermelho forte ao frear (corrente de regeneração ou desaceleração forte), sempre que a roda estiver conectada.
- **Segurança:** modos PWM, Velocidade (com escala própria em km/h, opcional), Combinado.
- **Brilho dia/noite** pelo horário (segurança e setas).
- **Alertas de voz:** PWM, bateria da roda, sinal perdido e bateria do celular baixa.
- **Passeios em CSV** (aba Config) e **backup/restauração** das configurações.
- **Modo técnico** (aba Config): mostra simulador, fita simulada, ponte WheelLog, diagnóstico e o modo Barra.

Publicação na Play Store: veja `docs/play-store.md`. O guia `docs/captura-ble.md` ficou como referência (só é necessário para descobrir comandos novos da placa).

## Rodas suportadas

Conexão direta por BLE, com o protocolo detectado sozinho (perfil "Automático") ou escolhido no perfil:
- **Begode/Gotway** e **Leaperkim/Veteran** (Sherman, Abrams, Patton, Lynx, Oryx), **Nosfet** (Apex, Aero, Aeon)
- **Kingsong** (pede o nome à roda para saber o pack)
- **Inmotion V5/V8/V10** (senha padrão 000000; PWM estimado) e **Inmotion V9/V11/V11y/V12/V12S/V13/V14**

Os formatos vêm do WheelLog e foram testados com frames reais dos testes dele, mas **nenhuma roda foi conectada de verdade por este projeto ainda**. Teste parado antes de andar.

## Como compilar e instalar

Requisitos: JDK 21 e Android SDK (plataforma 35). Crie `local.properties` com `sdk.dir=/caminho/do/sdk` (ou defina `ANDROID_HOME`).

```bash
./gradlew test            # testes unitários de todos os módulos
./gradlew assembleDebug   # APK em app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

O GitHub Actions (`.github/workflows/android.yml`) roda os testes e publica o APK debug como artefato.
minSdk 26, targetSdk 35. Interface em português do Brasil, tema escuro por padrão.

## Como usar

1. **Início → Permissões**: conceda Bluetooth (e notificações).
2. **Teste sem hardware**: deixe "Fita simulada" ligada, escolha fonte **Simulador**, toque em *Conectar / iniciar* e vá à aba **Segurança**: use os cenários (aceleração, frenagem, subida, perda de sinal) ou os sliders e veja a fita mudar.
3. **Fita real**: desligue "Fita simulada", *Buscar fita*, escolha o SP621E. Na aba **Config** use o teste R→G→B→branco; se o vermelho sair verde, troque a ordem de cor.
4. **Roda real (BLE direto, recomendado)**: **feche o WheelLog** (a roda só aceita uma conexão por vez), escolha o perfil (Nosfet Apex ou Begode A2), *Escolher roda* → *Conectar*. Há foreground service: a conexão continua com a tela apagada.
5. **Segurança**: modo Combinado (padrão) usa o pior entre PWM, velocidade e bateria baixa. Faixas, histerese, suavização e falha de sinal são editáveis.

### Cada roda
- **Nosfet Apex** (protocolo Veteran, versão 42 no WheelLog): informa **PWM de hardware**; o app mostra o modelo detectado. Os valores iniciais do perfil (velocidade máxima, tensões) são **palpites** — confira.
- **Begode A2** (protocolo Gotway/Begode): com firmware original **não informa PWM**; o app **estima** (velocidade ÷ velocidade máxima na tensão atual, igual ao WheelLog). Ajuste a calibração do perfil até o PWM mostrado chegar perto de 100% no limite real (tiltback). Firmwares novos que enviam o frame 0x07 usam PWM medido.

### Fontes de dados (`WheelDataSource`)
1. **`DirectBleSource`** — principal. O app conecta direto (serviço `FFE0`/característica `FFE1`).
2. **`WheelLogBridgeSource`** — **viável**: investigação do código do WheelLog (`Wheellog.Android`, commit `f44e39e`) mostrou que `WheelData.java` envia, a cada frame, um broadcast implícito e sem permissão `com.cooper.wheellog.wheelDataAvailable` com extras `Speed` (0,01 km/h), `PWM` (fração), `Voltage`, `Current`, `Battery`, `Temp1`. Não é API oficial e pode mudar; o WheelLog segue dono da conexão BLE. Detalhes em [docs/wheellog-ponte.md](docs/wheellog-ponte.md).
3. **`SimulatorSource`** — telemetria falsa para testar tudo sem roda e sem hardware.

## Arquitetura

```
core-model       Rgb, Telemetry, WheelProfile, LedFrame/LedState, interfaces (WheelDataSource, LedController)   [Kotlin puro]
wheel-protocols  GotwayDecoder, VeteranDecoder, AutoDecoder                                                    [Kotlin puro]
led-sp621e       Sp621ePackets, status, catálogo de efeitos, Sp621eController (coalescência 10 Hz), FakeLed    [Kotlin puro]
safety-engine    SafetyEngine: telemetria + perfil + config → quadro de LED                                    [Kotlin puro]
data-source      SimulatorSource, DirectBleSource, WheelLogBridgeSource, GattClient, BleScanner               [Android]
app              UI Compose (Início/Cor/Efeitos/Config/Segurança), AppGraph, ConnectionService, DataStore     [Android]
```

Fluxo: fonte → `AppGraph` (a cada 100 ms) → `SafetyEngine` → `LedController` (envia só o que mudou, ≤10 Hz) → SP621E / fita simulada.

Regras de robustez do `SafetyEngine` (todas com teste): subida imediata e descida lenta; histerese nas faixas e no pisca; perda de dados da roda (>1,5 s) → estado de falha âmbar, nunca "verde mentiroso"; valores inválidos (NaN, PWM > 100%, relógio que volta) saturados; recuperação sem herdar valor antigo. Reconexão automática com recuo exponencial (1→10 s) para roda e fita.

## Limitações conhecidas
- A roda aceita **uma** conexão BLE central: WheelLog e este app não conectam à roda ao mesmo tempo (use a ponte se quiser manter o WheelLog).
- Taxa de escrita ao SP621E limitada a ~10 Hz; o pisca crítico usa período de 400 ms por isso.
- Desconhecido se o SP621E grava cada cor recebida em memória permanente (desgaste). Se a fita voltar sozinha à última cor ao religar, é sinal disso — veja o checklist de campo.
- Efeitos do app na fita real são os do controlador; a animação na prévia é só uma aproximação.

## Licença e fontes
O projeto é **GPL-3.0-or-later** (ver [LICENSE](LICENSE)): reimplementa o formato dos frames Gotway/Veteran a partir do **WheelLog** (GPL-3.0). O protocolo do SP621E vem do **uniled** (MIT, © Phil M). Os vetores de teste reais dos decoders vêm dos testes do WheelLog. Fontes citadas nos comentários e em `docs/`.
