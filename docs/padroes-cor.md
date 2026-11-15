# Padrões de cor: mapeamentos e exemplos

Este documento descreve exatamente o que o motor de padrões (`pattern-engine`) produz.
As tabelas de exemplo são verificadas por teste (`DocumentedExamplesTest`): se o código
mudar, o teste falha e este documento precisa ser atualizado.

## Modos da especificação revisada

Estes são os três modos oferecidos na tela. Testes: `ModesTest`.

### Modo 1: espectro por PWM (`Pattern.PwmSpectrum`, padrão)

| Entrada | 0 % | 24 % | 48 % | 72 % | 96 % ou mais |
|---|---|---|---|---|---|
| Cor | azul `#0000FF` | verde `#00FF00` | amarelo `#FFFF00` | laranja `#FF8000` | vermelho `#FF0000` |

Entre dois pontos a cor é interpolada linearmente por canal, em RGB (decisão da Fase 3;
ver Q19). Exemplos: 12 % → `#008080`, 36 % → `#80FF00`, 84 % → `#FF4000`. A faixa é contínua:
0,1 % de entrada move qualquer canal em no máximo 2 passos.

### Modo 2: espectro personalizado (`Pattern.CustomSpectrum`)

Mesmos cinco pontos (0, 24, 48, 72, 96 %), cada um com a cor escolhida pelo usuário, mesma
interpolação. A tela tem um seletor por ponto e a faixa completa de pré-visualização.

### Modo 3: pisca entre duas cores (`Pattern.SpeedBlink`)

```
frequência = frequência_máxima × t        (t = posição da entrada, 0 a 1)
```

- Entrada padrão: velocidade (0 a `v_max`, 50 km/h por padrão). Pode ser trocada para PWM.
- `t = 0`: fica fixo na primeira cor.
- Cada ciclo mostra a primeira cor na primeira metade e a segunda na outra.
- Frequência máxima: 5 Hz por padrão, ajustável de 0,5 a 10 Hz. Acima da faixa de
  velocidade a frequência não passa do limite. A tela mostra o limite e um aviso sobre
  fotossensibilidade.
- Verificado por teste: 10 km/h → 1 Hz, 25 km/h → 2,5 Hz, 50 km/h ou mais → 5 Hz.
- Limitação conhecida: salto de fase quando a velocidade muda (Q18).

### Alerta crítico em 96 % de PWM

- Padrão: PWM ≥ 96 % é crítico (`SafetyConfig.pwmPercent = 96`), além dos alarmes do app.
- Aparência padrão: **vermelho piscando** a 2 Hz, alternando entre o limite máximo de brilho e
  25 % dele, sem nunca apagar. É diferente do vermelho fixo que o Modo 1 mostra ao chegar
  perto de 96 %. Alternativa configurável: vermelho fixo.
- Continua valendo tudo da seção 6: sobrepõe qualquer modo, não é defasado nem suavizado,
  não tem opção nos padrões.

As seções seguintes descrevem a base comum e os padrões do rascunho anterior (P1, P2, P3,
transição), que continuam no motor mas não aparecem na tela.

## Visão geral

O motor é uma função pura:

```
(velocidade, PWM, crítico, configuração, tempo) → cor + brilho, por placa
```

Sem relógio interno, sem estado, sem Android. Os mesmos argumentos sempre dão o mesmo
resultado. Ponto de entrada: `PatternEngine.frame(...)` (uma placa) e
`PatternEngine.frames(...)` (várias placas).

Cada quadro (`LedFrame`) tem uma cor RGB de 8 bits por canal e um brilho de 0 a 1.

## 1. Da telemetria à posição `t`

O usuário escolhe a entrada. Ela é normalizada para uma posição `t` entre 0 e 1.

| Entrada | Fórmula | Padrão |
|---|---|---|
| PWM | `t = |PWM| / 100` | entrada selecionada por padrão |
| Velocidade | `t = (|v| − v_min) / (v_max − v_min)` | `v_min = 0`, `v_max = 50 km/h` (provisório, Q10) |

- Valores fora da faixa saturam em 0 ou 1.
- O sinal é ignorado (andar de ré é igual a andar para a frente).
- Leitura inválida (NaN) conta como 0.
- Antes de chegar aqui, a entrada passa por uma suavização exponencial (250 ms por
  padrão, 0 desliga) para a cor não tremer com o ruído do sensor. **A detecção do alerta
  crítico não passa pela suavização.**

## 2. P1: gradiente contínuo

Percorre o espectro do frio ao quente, com saturação e valor no máximo:

```
matiz = 240° × (1 − t)        (240° = azul, 0° = vermelho)
```

Passa por ciano, verde e amarelo. Nunca passa por magenta e nunca escurece.

| PWM | Matiz | Cor |
|---:|---:|---|
| 0 % | 240° | `#0000FF` azul |
| 10 % | 216° | `#0066FF` |
| 20 % | 192° | `#00CCFF` |
| 25 % | 180° | `#00FFFF` ciano |
| 30 % | 168° | `#00FFCC` |
| 40 % | 144° | `#00FF66` |
| 50 % | 120° | `#00FF00` verde |
| 60 % | 96° | `#66FF00` |
| 70 % | 72° | `#CCFF00` |
| 75 % | 60° | `#FFFF00` amarelo |
| 80 % | 48° | `#FFCC00` |
| 90 % | 24° | `#FF6600` |
| 100 % | 0° | `#FF0000` vermelho |

Suavidade: 0,1 % de PWM move qualquer canal em no máximo 2 passos de 255.

## 3. P2: gradiente de 3 cores

O usuário escolhe início, meio e fim. A interpolação é linear por canal, em sRGB
(sem correção de gama, de propósito: o resultado é fácil de prever).

```
t ≤ m:  cor = início + (meio − início) × (t / m)
t > m:  cor = meio  + (fim  − meio)  × ((t − m) / (1 − m))
```

`m` é a posição do meio (padrão 0,5; qualquer valor entre 0 e 1, exclusivo).

Exemplo com as cores padrão (verde `#00FF00`, amarelo `#FFFF00`, vermelho `#FF0000`):

| PWM | Cor |
|---:|---|
| 0 % | `#00FF00` |
| 25 % | `#80FF00` |
| 50 % | `#FFFF00` |
| 75 % | `#FF8000` |
| 100 % | `#FF0000` |

## 4. P3: pulso / intensidade

Uma lista de até 8 itens. Cada item tem:

| Campo | Faixa | Padrão |
|---|---|---|
| cor | RGB | branco |
| frequência | 0,1 a 3 Hz | 1 Hz |
| faixa de atuação | início e fim, em % da entrada (0–100), extremos inclusos | 0–100 |
| forma | `BLINK` (pisca) ou `BREATHE` (respira) | `BLINK` |
| brilho mínimo e máximo | 0 a 1 | 0 e 1 |
| ciclo ativo (só `BLINK`) | entre 0 e 1 | 0,5 |

Regras:
- Vale o **primeiro** item da lista cuja faixa contém a entrada. Faixas podem se sobrepor.
- Fora de todas as faixas vale a "luz de repouso": apagado (padrão) ou cor fixa.
- `BLINK`: onda quadrada. Brilho máximo durante a fração "ciclo ativo" de cada período,
  brilho mínimo no resto.
- `BREATHE`: `brilho = mín + (máx − mín) × (1 − cos(2π × fase)) / 2`. Começa no mínimo,
  chega ao máximo no meio do período.
- `fase = parte fracionária de (tempo_em_segundos × frequência)`.

O teto de 3 Hz segue a diretriz de fotossensibilidade (no máximo três flashes por
segundo) e fica dentro do que o enlace Bluetooth entrega com folga (Q13).

Exemplo: verde respirando devagar abaixo de 60 %, âmbar piscando rápido acima de 80 %,
verde fixo entre os dois.

| Entrada | Tempo | Resultado |
|---:|---:|---|
| 30 % | 0 ms | verde, brilho 0,2 |
| 30 % | 1000 ms | verde, brilho 1,0 (0,5 Hz: pico no meio do período de 2 s) |
| 70 % | qualquer | verde fixo, brilho 1,0 (repouso) |
| 90 % | 0 ms | âmbar `#FFA000`, brilho 1,0 |
| 90 % | 167 ms | âmbar, brilho 0 |
| 90 % | 334 ms | âmbar, brilho 1,0 |

## 5. Modo de transição (proposta, não confirmada: Q4)

`Pattern.ColorSequence`: de 2 a 12 cores distribuídas igualmente de 0 a 1.

- Suave: interpola entre vizinhas. Com N cores, a cor `i` cai em `t = i / (N − 1)`.
- Em degraus: N faixas iguais; a cor muda de uma vez em cada limite.

Exemplo do pedido (verde → vermelho → verde → azul), entrada velocidade, 0–60 km/h, suave:

| Velocidade | Cor |
|---:|---|
| 0 km/h | `#00FF00` verde |
| 10 km/h | `#808000` |
| 20 km/h | `#FF0000` vermelho |
| 40 km/h | `#00FF00` verde |
| 50 km/h | `#008080` |
| 60 km/h | `#0000FF` azul |

## 6. Alerta crítico (vermelho)

Quando o estado é crítico, **todas** as placas mostram vermelho `#FF0000`:

- sobrepõe qualquer padrão e também o modo manual;
- ignora o brilho geral: usa o limite máximo de brilho;
- não sofre o atraso do modo defasado: todas as placas mudam juntas;
- não passa pela suavização da entrada: é avaliado na leitura bruta;
- não existe opção nos padrões que o desligue. A configuração dele (`SafetyConfig`) é
  separada da configuração de padrões;
- é reenviado à placa a cada 500 ms (um padrão comum é reenviado a cada 2 s), para que
  um pacote perdido não deixe o alerta sem aparecer.

O que conta como crítico:

| Causa | Padrão |
|---|---|
| alarme disparado pelo próprio app | sempre ativo, sem opção |
| PWM ≥ limiar | desligado (Q3) |
| velocidade ≥ limiar | desligado (Q3) |
| temperatura ≥ limiar | desligado (Q3) |
| corrente ≥ limiar (em módulo) | desligado (Q3) |
| tensão ≤ limiar | desligado (Q3) |

Entrada imediata, saída retardada: o vermelho acende na mesma leitura em que a causa
aparece e fica por mais 1,5 s (`holdMs`, 0 a 10 s) depois que ela some, para não
piscar quando o valor oscila em torno do limiar.

Estilos: `SOLID` (fixo, padrão, como pedido) e `FLASH` (alterna entre o limite máximo e
25 % dele, a 2 Hz, nunca apaga). Ver Q8.

Os LEDs são um indicador **adicional**. Os alarmes do próprio app (som, vibração, tela)
não são tocados por este módulo.

## 7. Brilho

```
brilho_final = min(intensidade_do_padrão × brilho_geral, limite_máximo)
```

- `brilho_geral` (0 a 1, padrão 1): ajuste do usuário. Só afeta padrões cosméticos.
- `limite_máximo` (0,05 a 1, padrão 1): teto absoluto. Nenhum quadro passa dele,
  inclusive o alerta. É um corte, não uma escala: com limite 0,8 e brilho geral 0,5 o
  resultado é 0,5; com brilho geral 1,0 o resultado é 0,8. Uma onda `BREATHE` que
  ultrapasse o teto fica com o topo achatado.
- O limite não pode ser menor que 5 %, para não servir de meio de esconder o alerta.

Conversão para o nível de 0 a 255 enviado à placa: `nível = arredonda(brilho × 255)`.

| Brilho | Nível |
|---:|---:|
| 0 | 0 |
| 0,05 | 13 |
| 0,25 | 64 |
| 0,5 | 128 |
| 0,75 | 191 |
| 1 | 255 |

O teste `nothing sent to a board exceeds the brightness ceiling` percorre o PWM de 0 a
100 %, com alerta no fim, e confere byte a byte que nenhum comando enviado ao simulador
passa do teto.

Não verificado: como a placa real traduz nível em luz (a curva pode não ser linear) e
se nível 0 apaga de fato a fita. Está no [checklist de campo](checklist-campo.md).

## 8. Várias placas

Todas as placas recebem a mesma configuração.

- **Sincronizado:** todas mostram o mesmo quadro.
- **Defasado:** a placa `i` (0, 1, 2…) mostra o que a placa 0 mostrava `i × atraso`
  antes. Vale tanto para a cor (usa a telemetria daquele instante) quanto para a fase
  dos pulsos. Atraso de 0 a 2000 ms por placa, padrão 150 ms. A ordem das placas é a da
  lista e pode ser alterada.

O alerta crítico nunca é defasado.

## 9. Sem telemetria

Se nenhuma leitura chega há mais de 3 s (configurável), o padrão usa, conforme a opção:
entrada zero (padrão: aceso, na ponta fria), último valor, ou apagado. Um alarme que
estava ativo quando a roda desconectou é liberado depois do tempo de retenção. Ver Q9.

## 10. Pré-visualização

`PatternEngine.preview(padrão, n)` devolve `n` cores igualmente espaçadas de 0 a 100 %.
É o que a tela de padrões vai desenhar como faixa de cores. Para o P3 mostra a cor de
cada item sobre a faixa em que ele atua.
