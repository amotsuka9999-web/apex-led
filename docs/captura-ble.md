# Como capturar o tráfego do BanlanX (para completar o protocolo do SP621E)

Faltam os comandos de **tipo de CI** e **número de pixels** (e a confirmação dos demais). O caminho é gravar o
Bluetooth enquanto você usa o BanlanX e analisar com o script deste repositório.

## 1. Gravar (no celular Android)
1. **Opções do desenvolvedor** → ative **Ativar registro de HCI Bluetooth** (*Enable Bluetooth HCI snoop log*, em "Completo/Full" se houver escolha).
2. **Desligue e ligue o Bluetooth** (isso começa um arquivo novo).
3. Abra o **BanlanX**, conecte ao SP621E e faça **uma ação por vez, com uma pausa de ~3 s entre elas**, anotando a ordem:
   1. ligar → desligar → ligar
   2. cor sólida **vermelha pura** (R=255), depois **verde puro**, depois **azul puro**
   3. brilho: baixo → alto
   4. **2 ou 3 efeitos** diferentes (anote os nomes)
   5. velocidade do efeito: 1, 5, 10
   6. comprimento do efeito, se houver
   7. **Configuração da fita** (o mais importante): tipo de IC (troque WS2811 → WS2812 → volte), **ordem de cor** (RGB → GRB → volta), **número de pixels** (ex.: 100 → 150 → 200; use valores bem distintos)
4. Desligue o registro HCI.

## 2. Extrair o log
- Mais simples: `adb bugreport bugreport.zip` e procure `btsnoop_hci.log` dentro do zip (costuma estar em `FS/data/misc/bluetooth/logs/`).
- Ou `adb pull /data/misc/bluetooth/logs/btsnoop_hci.log` (caminho varia por fabricante; alguns exigem o bugreport).
- Alguns aparelhos salvam em `/sdcard/btsnoop_hci.log` ou `Android/data/btsnoop_hci.log`.

## 3. Analisar
```bash
python3 tools/btsnoop_gatt.py btsnoop_hci.log --only-writes --marks
python3 tools/btsnoop_gatt.py btsnoop_hci.log --csv captura.csv
```
O script lista cada escrita GATT, identifica a característica (FFE1) e marca `NOVO` nos comandos `A0 xx` que o app
ainda não conhece. Cada bloco separado por linha em branco = uma ação sua. ⚠️ O script foi testado só com logs
sintéticos; se não achar nada num log real, mande o arquivo para análise.

## 4. O que me enviar
O texto da saída (ou o CSV) **junto com a sua lista de ações na ordem**. Com isso o `Sp621ePackets.pixelCount()` e
`icType()` (hoje devolvem `null` de propósito) podem ser implementados, cada um com teste de formato.
Não publique o log bruto em lugar aberto: ele contém endereços Bluetooth do seu celular e dispositivos.
