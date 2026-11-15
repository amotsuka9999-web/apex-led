# O que testar na prática (nada abaixo foi validado em hardware)

Faça **com a roda parada e levantada**, depois em baixa velocidade num local seguro.

## Fita SP621E
- [ ] Busca encontra o SP621E (aparece "provável")? Conecta e a pílula fica "Fita ok"?
- [ ] A característica FFE1 aceita escrita? Com ou sem resposta? (o app usa sem resposta se disponível)
- [ ] Teste R→G→B→branco mostra as cores certas? Se não, troque a ordem de cor (Config).
- [ ] Brilho/cor/efeitos respondem como no BanlanX? Velocidade e comprimento funcionam?
- [ ] Cor com RGB (0,0,0) apaga de verdade (pisca fica nítido)?
- [ ] A fita **grava a última cor**? Mude a cor, desligue/ligue o controlador: voltou à cor? (se sim, o envio contínuo pode desgastar a memória — avise)
- [ ] Pisca crítico a 400 ms fica fluido ou travado? Alguma latência a 10 Hz?
- [ ] Desligar o Bluetooth do SP621E / afastar: o app reconecta sozinho?
- [ ] Capturar tipo de CI e nº de pixels (docs/captura-ble.md) — fita de 200 LEDs/m.

## Roda
- [ ] Apex: modelo detectado "Nosfet Apex"? PWM de hardware confere com o WheelLog?
- [ ] A2: o PWM estimado chega perto de 100% no limite real? Ajuste a calibração.
- [ ] Velocidade e tensão batem com o display da roda/WheelLog?
- [ ] Desligar a roda/afastar: após ~1,5 s a fita vira âmbar e volta sozinha ao reconectar?
- [ ] Conflito: com o WheelLog conectado, o app avisa/reconecta sem travar?
- [ ] Ponte WheelLog (opcional): chegam dados com o WheelLog em segundo plano?

## Sistema
- [ ] Foreground service mantém tudo com a tela apagada por 10+ min (Android 17; desative otimização de bateria se necessário)?
- [ ] Permissões Bluetooth/notificações pedidas corretamente?
