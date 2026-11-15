# Pontos de integração no WheelLog

Levantamento feito por leitura do código em `github.com/Wheellog/Wheellog.Android`,
commit `f44e39e` (30/09/2026, versão 3.2.10b). A base a ser usada ainda não foi
confirmada (Q6); se for outro fork, conferir de novo. Nenhum código do WheelLog foi
copiado para este repositório.

## Licença

- **GPL-3.0** (arquivo `LICENSE` na raiz).
- Ao distribuir um app derivado: licenciar o conjunto sob GPL-3.0, oferecer o
  código-fonte completo, manter os avisos de copyright e de licença existentes e marcar
  os arquivos modificados como alterados, com data.
- As dependências de terceiros do WheelLog têm licenças próprias, a manter.
- A licença deste projeto ainda precisa ser confirmada pelo dono (Q11).

## Estrutura

- Módulos Gradle: `app`, `shared`, `wearos`. Kotlin 2.2.10, AGP 8.13, Gradle 8.13,
  `compileSdk 35`, `minSdk 21`, Java 17, Compose + Views, Koin para injeção.
- Pacote principal: `com.cooper.wheellog`.

## Conexão com a roda

- `BluetoothService.kt`: `Service` Android. Usa a biblioteca `blessed-android` 2.4.1
  (`BluetoothCentralManager`), criada com `Handler(Looper.getMainLooper())`: **os
  callbacks de BLE rodam na thread principal**.
- Dados recebidos: `onCharacteristicUpdate` → `readData()` → `WheelData.decodeResponse()`.
- Reconexão: `autoConnectPeripheral` + timer próprio (`startReconnectTimer`).

## Onde a telemetria chega

`WheelData.java` é um singleton com todo o estado da roda.

1. `decodeResponse(byte[] data, Context)` chama `getAdapter().decode(data)`; cada marca
   tem um adaptador em `utils/` (`VeteranAdapter`, `KingsongAdapter`, `GotwayAdapter`,
   `InmotionAdapterV2`…).
2. O adaptador grava os valores com `setSpeed`, `setVoltage`, `setTemperature`, etc.
3. Se houve dado novo, `decodeResponse` envia o broadcast
   `Constants.ACTION_WHEEL_DATA_AVAILABLE`.

Tudo isso acontece na thread principal, uma vez por quadro de telemetria da roda.

### Valores e unidades

| Grandeza | Leitura | Unidade |
|---|---|---|
| Velocidade | `WheelData.getSpeedDouble()` | km/h (interno: centésimos) |
| PWM | `WheelData.getCalculatedPwm()` | % de 0 a 100 (interno: fração 0–1) |
| Temperatura | `WheelData.getTemperature()` | °C, inteiro |
| Tensão | `WheelData.getVoltageDouble()` | V |
| Corrente | `WheelData.getCurrentDouble()` | A |

### De onde vem o PWM

Depende do adaptador:

- **PWM real da roda:** `wd.setOutput(...)` + `wd.updatePwm()` (Kingsong, InMotion V2,
  Gotway e Veteran quando a opção `hwPwm` está ativa).
- **PWM estimado:** `wd.calculatePwm()`, a partir de velocidade, tensão e dos parâmetros
  `rotationSpeed`, `rotationVoltage` e `powerFactor` das configurações.

**Nosfet:** o Apex, o Aero e o Aeon são tratados pelo `VeteranAdapter` (`mVer` 42, 43 e
44). Nele o PWM é lido do quadro (`hwPwm`, posição 34) e usado quando
`appConfig.getHwPwm()` é verdadeiro; `getVer()` liga essa opção para `mVer >= 2`.
Falta confirmar roda e firmware (Q2).

## Alarmes do app

- `utils/Alarms.kt` (objeto). `checkAlarm(pwm, context)` é chamado pela `MainActivity` a
  cada `ACTION_WHEEL_DATA_AVAILABLE` quando `appConfig.alarmsEnabled`; enquanto houver
  alarme, um timer próprio reavalia a cada 200 ms.
- Tipos (`Constants.ALARM_TYPE`): `SPEED1..3`, `CURRENT`, `TEMPERATURE`, `PWM`,
  `BATTERY`, `WHEEL`.
- `Alarms.alarm` devolve uma máscara de bits com os alarmes em execução (velocidade/PWM
  `0x01`, corrente `0x02`, temperatura `0x04`, bateria `0x08`, roda `0x10`).
- Ao disparar, envia o broadcast `Constants.ACTION_ALARM_TRIGGERED` e aciona som e vibração.

Atenção: a checagem de alarmes parte da `MainActivity`. Com a tela do app em segundo
plano vale conferir se os alarmes continuam sendo avaliados antes de depender só de
`Alarms.alarm` para o vermelho.

## Como a interface é atualizada

- `MainActivity` registra um `BroadcastReceiver` para `ACTION_WHEEL_DATA_AVAILABLE` e
  chama `pagerAdapter.updateScreen(...)`.
- As configurações usam `AppConfig` (um invólucro sobre `SharedPreferences`) e telas em
  Compose no pacote `settings/`.

## Integração proposta

O acoplamento ao WheelLog fica em uma única classe-ponte, pequena:

```
BluetoothService ──► WheelData.decodeResponse ──► broadcast (inalterado)
                                   │
                                   └──► LedBridge.onWheelData()      [novo, 1 linha de chamada]
                                             │  monta WheelTelemetry(velocidade, PWM, temperatura,
                                             │  corrente, tensão, appAlarm = Alarms.alarm != 0)
                                             ▼
                                   LedController.onTelemetry()       [módulo led-core]
                                             ▼
                                   PatternEngine ─► BoardManager ─► placas (BLE)
```

- **Ponto de chamada:** ao fim de `decodeResponse`, depois do broadcast. É a mesma
  thread, sem custo de `Intent`. `LedController.onTelemetry` não faz E/S e não bloqueia:
  só calcula quadros e os deixa para as corrotinas de cada placa.
- **Alarmes:** a ponte apenas **lê** `Alarms.alarm`. Nada em `Alarms.kt` é alterado, de
  modo que os padrões não têm como ocultar, atrasar ou substituir os alarmes do app.
- **Ciclo de vida:** `LedController.start()` e `stop()` acompanham o `BluetoothService`
  (serviço em primeiro plano), para as placas continuarem recebendo cor com a tela apagada.
- **BLE das placas:** implementar `LedTransport` sobre a mesma `blessed-android` já
  usada, com um `BluetoothPeripheral` por placa. É a única peça que falta na camada de
  dispositivo e precisa de hardware para ser testada.
- **Persistência:** `FileSettingsStore` em `context.filesDir`, separado das preferências
  do WheelLog.
- **Telas:** novas telas Compose (placas, padrões com pré-visualização, funções da
  placa, alerta), ligadas ao menu de configurações existente.

## O que falta para a fase 4

1. Confirmar a base (Q6) e fazer o fork.
2. SDK do Android disponível no ambiente de build (aqui o download é bloqueado: o host
   `dl.google.com` não é permitido).
3. Completar a spec do Balanx ([balanx-spec.md](balanx-spec.md)).
4. `LedTransport` sobre BLE do Android e as telas.
