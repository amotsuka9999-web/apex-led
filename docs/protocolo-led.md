# Protocolo da placa LED

**Estado: não verificado em hardware.** Tudo aqui vem de documentação e código abertos
de terceiros. Serve para interoperar com uma placa que o usuário já possui. Só está
documentado o que o app precisa.

## Fontes

| Fonte | Licença | Uso |
|---|---|---|
| `github.com/monty68/uniled` (integração Home Assistant) | MIT | formato das mensagens, códigos de operação, layout do status |
| `github.com/ZephyrSai/rgb-strip-remote` | MIT | confirmação de UUIDs e do agrupamento por família |

Nenhum código foi copiado: os codecs em Kotlin foram escritos a partir da descrição do
formato. O fabricante da controladora (SP LED) não publica SDK nem documentação do
protocolo, até onde a pesquisa alcançou; a Nosfet também não (ver Q7).

## Transporte

- Bluetooth Low Energy, GATT.
- Serviço `0000ffe0-0000-1000-8000-00805f9b34fb`.
- Característica `0000ffe1-0000-1000-8000-00805f9b34fb`: escrita (comandos) e
  notificação (respostas).
- Identificação no anúncio: dados de fabricante com ID 20563 (0x5053). O par FFE0/FFE1
  é usado por muitos outros produtos, então o UUID sozinho não identifica a placa.
- Uma conexão por vez.
- Sem pareamento nem autenticação descritos.

## Placa especificada: SP621E (`sp621e`, padrão)

Mensagem `A0 <op> <n> <dados>` (fonte: `ZephyrSai/rgb-strip-remote`, que agrupa SP611E,
SP617E, SP620E e SP621E). Verificado por `Sp621eProtocolTest` contra o simulador, **não em hardware**.

| Operação | Bytes |
|---|---|
| Ligar / desligar | `A0 62 01 <1/0>` |
| Efeito | `A0 63 01 <n>` (cor sólida = `BE`) |
| Brilho | `A0 66 01 <nível>` |
| Velocidade do efeito | `A0 67 01 <1–10>` |
| Comprimento do efeito | `A0 68 01 <1–150>` |
| Cor | `A0 69 04 <R> <G> <B> <nível>` |

Leitura de status, estado ao energizar, timers e demais opções não estão documentados nas
fontes para esta família e não foram implementados. A SP621E é uma controladora de fita
endereçável (ver Q17).

## Famílias

O app do fabricante usa formatos diferentes conforme o modelo. Como o modelo é
desconhecido (Q1), há dois codecs, selecionados por placa:

| Id no app | Modelos | Formato |
|---|---|---|
| `sp63x` | SP630E, SP631E–SP63AE, SP641E–SP64AE | `53 <op> 00 01 00 <n> <dados>` |
| `sp61x-pwm` | SP613E, SP614E, SP623E, SP624E | `<op> <n> <dados>` |

Outras famílias existem (SP611E/SP62xE endereçáveis, SP110E) e não foram implementadas.

## Família `sp63x`

Mensagem: cabeçalho de 6 bytes + dados.

| Byte | Valor |
|---|---|
| 0 | `53` |
| 1 | código de operação |
| 2 | chave de ofuscação; `00` = dados em claro (único caso tratado) |
| 3–4 | `01 00` |
| 5 | quantidade de bytes de dados |
| 6… | dados |

| Operação | Código | Dados |
|---|---|---|
| Consultar status | `02` | `01` |
| Animação ao ligar/desligar | `08` | `01`, animação (1–4), velocidade (1–3), pixels (2 bytes, big-endian) |
| Estado ao energizar | `0B` | 0 desligado, 1 ligado, 2 último |
| Ligar / desligar | `50` | 1 / 0 |
| Brilho | `51` | grupo (0 cor, 1 branco), nível 0–255 |
| Cor estática | `52` | R, G, B, nível |
| Modo e efeito | `53` | modo, efeito |
| Velocidade do efeito | `54` | 1–10 |
| Comprimento do efeito | `55` | 1–150 |
| Direção | `56` | 1 / 0 |
| Cor do efeito | `57` | R, G, B |
| Ciclo automático | `58` | 1 / 0 |
| Fonte de áudio | `59` | 0 embutido, 1 player, 2 externo |
| Sensibilidade | `5A` | 1–16 |
| Reproduzir / pausar | `5D` | 1 / 0 |

Modos: 1 estático cor, 2 estático branco, 3 dinâmico cor, 4 dinâmico branco, 5 música
cor, 6 música branco, 7 personalizado. Efeito 1 do modo estático = cor sólida.

Exemplos (verificados por `Sp63xProtocolTest`):

| Ação | Bytes |
|---|---|
| Ligar | `53 50 00 01 00 01 01` |
| Cor sólida, modo estático | `53 53 00 01 00 02 01 01` |
| Vermelho, brilho máximo | `53 52 00 01 00 04 FF 00 00 FF` |
| Consultar status | `53 02 00 01 00 01 01` |

Status (notificação, operação `02`), posições contadas a partir do início da mensagem:

| Posição | Conteúdo |
|---|---|
| 11–17 | versão do firmware (ASCII) |
| 19 | tipo de LED |
| 25 | estado ao energizar |
| 29 | ligado (≠ 0) |
| 32 | modo |
| 33 | efeito |
| 35 | nível de cor |
| 37–39 | R, G, B da cor estática |
| 42 | velocidade |
| 43 | comprimento |

## Família `sp61x-pwm`

| Operação | Bytes |
|---|---|
| Ligar / desligar | `0F 01 <1/0>` |
| Brilho | `12 01 <nível>` |
| Cor | `13 04 <R> <G> <B> <nível>` |
| Velocidade do efeito | `14 01 <1–10>` |
| Efeito | `15 01 <n>` (cor sólida = `63`) |
| Consultar status | `1D 00` |

O status desta família chega dividido em várias notificações e ainda não é lido.
Funções sem comando conhecido (comprimento, estado ao energizar, animação) são
recusadas pelo app para placas desta família.

## Como o app usa o protocolo para os padrões

Para mostrar um padrão guiado por telemetria a placa precisa estar em cor sólida:

1. ao conectar (e a cada reconexão): ligar + selecionar cor sólida;
2. a cada mudança: um comando de cor com nível (`52` ou `13`);
3. o mesmo quadro não é reenviado, exceto como reforço periódico (2 s; 500 ms no alerta).

## O que precisa ser confirmado em uma placa real

1. Qual família o modelo real usa, ou se usa outra.
2. Se a placa responde ao status com a chave `00` ou com dados ofuscados.
3. Se o status cabe em uma notificação ou chega dividido (o MTU padrão é de 20 bytes de
   dados e o status tem mais de 50).
4. Se a característica aceita escrita sem resposta, e quantos comandos por segundo a
   placa absorve sem atrasar (o app parte de no máximo ~33 por segundo, um a cada 30 ms).
5. Se o comando de cor com nível 0 apaga a fita.
6. Se a placa grava cada cor recebida em memória permanente. Se gravar, o envio contínuo
   de cores pode desgastar a memória e será preciso reduzir a taxa ou usar outro comando.
7. Se trocar de modo durante um efeito causa piscada visível.

Para trocar ou corrigir um protocolo basta uma nova implementação de `LedProtocol`
registrada no `ProtocolRegistry`; nada acima dessa interface muda.
