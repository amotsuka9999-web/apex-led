# Ponte com o WheelLog

**Resultado da investigação:** existe um canal utilizável, mas não é uma API pública.

`WheelData.java` (Wheellog.Android `f44e39e`) chama `sendBroadcast(new Intent("com.cooper.wheellog.wheelDataAvailable"))` a cada frame decodificado, sem pacote de destino e sem permissão. Extras: `Speed` (int, 0,01 km/h), `PWM` (double, fração — medido ou estimado conforme as opções do WheelLog), `Voltage` (0,01 V), `Current` (0,01 A), `PhaseCurrent`, `Power`, `Battery` (%), `Temp1`/`Temp2` (0,01 °C), `Max*`, `Distance`. Também existem `com.cooper.wheellog.bluetoothConnectionState` e `…wheelIsReady`. O app registra um receiver em tempo de execução (`RECEIVER_EXPORTED` no Android 13+).

Prós: o WheelLog continua dono da conexão BLE (a roda só aceita um central); reaproveita as calibrações dele. Contras: sem contrato estável; depende do WheelLog rodando e sem restrição de bateria; latência extra; **não testado em aparelho** (só leitura de código). Outros mecanismos (Pebble, Garmin ConnectIQ, Samsung Gear, log CSV) existem, mas exigem hardware/arquivos e não servem para tempo real.
