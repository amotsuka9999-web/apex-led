# Especificação do Balanx (Fase 0)

**Estado: incompleta.** Este documento separa o que foi confirmado em fonte pública do que
ainda precisa ser levantado com o app na mão. Nada aqui foi preenchido por suposição:
o que não foi verificado está marcado como **A LEVANTAR**.

## 1. O que foi possível fazer e o que não foi

O levantamento foi feito em 2026-11-14, sem acesso ao app nem a uma placa.

| Fonte | Resultado |
|---|---|
| App instalado em um aparelho | não disponível neste ambiente |
| Loja de aplicativos (Google Play) | bloqueada pela rede deste ambiente |
| Site do app (`banlanx.net`) e manuais em PDF (`superlightingled.com`, `manuals.plus`, `cdn.shopify.com`) | bloqueados pela rede deste ambiente |
| Resumos de busca na web sobre os manuais das controladoras SP6xxE | usados, com cautela (são resumos, não o manual) |
| Projetos abertos que controlam essas placas (`monty68/uniled`, MIT) | lidos; fonte da lista de funções, faixas de valores e protocolo |

Consequência: **telas, botões, textos e valores padrão do app não puderam ser
levantados**. O que existe abaixo é o conjunto de funções que a placa aceita, que é o
teto do que o app pode oferecer.

## 2. Identificação (hipótese, ver Q7 em [questoes.md](questoes.md))

- Não foi encontrado nenhum app chamado "Balanx". O app que corresponde à descrição
  (controle de fitas e COB por Bluetooth) é o **BanlanX**, pacote Android
  `com.spled.scenex`, para as controladoras da série **SP6xxE** da SP LED
  (Shenzhen Sperll).
- Não foi encontrada placa LED fabricada pela **Nosfet**; a Nosfet aparece como
  fabricante de monociclos (Apex, Aero, Aeon), sem SDK nem documentação de LEDs publicados.
- Versão do app analisada: **nenhuma** (Q1).
- Modelo da placa: **SP621E** (especificação revisada), controladora mini para fita
  endereçável da mesma linha. O texto original desta linha, abaixo, é anterior a essa informação.
- Modelo da placa (registro anterior): desconhecido (Q1). Para fita COB RGB os candidatos naturais são
  os modelos PWM RGB (SP613E, SP623E, SP633E, SP643E) e o multifunção SP630E.

## 3. Estrutura do app (de resumos dos manuais do SP630E)

Cinco páginas de controle, cujo conteúdo muda conforme o tipo de LED configurado:

| Página | Função | Estado |
|---|---|---|
| Dinâmico | efeitos embutidos | confirmado que existe; layout A LEVANTAR |
| Estático | cor fixa e brilho | confirmado que existe; layout A LEVANTAR |
| Música | efeitos reativos a som | confirmado que existe; layout A LEVANTAR |
| Personalizado (DIY) | efeito montado pelo usuário | confirmado que existe; conteúdo A LEVANTAR |
| Configurações | nome, tipo de LED, ordem das cores, timers, efeito ao ligar/desligar | confirmado que existe; layout A LEVANTAR |

Fluxo de primeira conexão (dos manuais): adicionar a controladora por Bluetooth; pode ser
pedido o tipo de LED e a calibração da ordem das cores; ambos ficam disponíveis depois
em Configurações.

## 4. Funções e configurações

"Faixa" vem da implementação aberta citada acima. "Padrão" é o valor de fábrica do app:
nenhum pôde ser confirmado.

| # | Função | Faixa / opções | Padrão | No nosso código |
|---|---|---|---|---|
| F1 | Ligar / desligar | ligado, desligado | A LEVANTAR | `BoardCommand.Power` |
| F2 | Brilho | 0–255 | A LEVANTAR | `BoardCommand.Brightness` |
| F3 | Cor estática | RGB + brilho | A LEVANTAR | `BoardCommand.StaticColor` |
| F4 | Modo | estático, dinâmico, música, personalizado (com variantes cor/branco conforme o LED) | A LEVANTAR | `LightMode` + `BoardCommand.SelectEffect` |
| F5 | Efeito dinâmico | lista numerada; na família SP63xE em modo PWM RGB a fonte aberta lista 12 efeitos dinâmicos e 3 de música | A LEVANTAR | `SelectEffect(DYNAMIC, n)`; catálogo de nomes pendente |
| F6 | Velocidade do efeito | 1–10 | A LEVANTAR | `BoardCommand.EffectSpeed` |
| F7 | Comprimento do efeito | 1–150 (só fitas endereçáveis) | A LEVANTAR | `BoardCommand.EffectLength` |
| F8 | Direção do efeito | para frente / para trás (só endereçáveis) | A LEVANTAR | `BoardCommand.EffectDirection` |
| F9 | Ciclo automático de efeitos | ligado / desligado | A LEVANTAR | `BoardCommand.EffectLoop` |
| F10 | Pausar / continuar efeito | — | A LEVANTAR | `BoardCommand.EffectPlay` |
| F11 | Cor do efeito (efeitos que aceitam cor) | RGB | A LEVANTAR | `BoardCommand.EffectColor` |
| F12 | Fonte de áudio | microfone embutido, player do celular, microfone externo | A LEVANTAR | `BoardCommand.SelectAudioInput` |
| F13 | Sensibilidade do microfone | 1–16 | A LEVANTAR | `BoardCommand.Sensitivity` |
| F14 | Estado ao energizar | desligado, ligado, último estado | A LEVANTAR | `BoardCommand.SetPowerOnState` |
| F15 | Animação ao ligar/desligar | 4 animações, 3 velocidades, 1–600 pixels | A LEVANTAR | `BoardCommand.SetOnOffAnimation` |
| F16 | Tipo de LED | PWM mono, CCT, RGB, RGBW, RGBCCT; SPI equivalentes | A LEVANTAR | não implementado (ver nota) |
| F17 | Ordem dos canais (calibração) | permutações de R, G, B | A LEVANTAR | não implementado (ver nota) |
| F18 | Timers de ligar/desligar | até 5 | A LEVANTAR | não implementado (comando não documentado nas fontes) |
| F19 | Renomear a controladora | texto | A LEVANTAR | nome local por placa implementado; gravar o nome na placa não está documentado |
| F20 | Efeito personalizado (DIY) | em modo PWM, três tipos: salto, respiração e estrobo; edição das cores A LEVANTAR | A LEVANTAR | só seleção do modo e do tipo; edição não documentada |
| F21 | Canais de branco (W, CCT) | níveis frio/quente | A LEVANTAR | não implementado (fita COB RGB não usa) |
| F22 | Memória do último estado | a placa guarda o estado ao desligar | n/a | é da placa; nada a fazer no app |

Nota sobre F16 e F17: trocar o tipo de LED ou a ordem dos canais com valor errado pode
deixar a fita apagada ou com cores trocadas. Só serão implementados depois de testados
em uma placa real.

## 5. Botões

| Item | Estado |
|---|---|
| Botões das telas do app (um por um, com efeito de toque curto e longo) | **A LEVANTAR**: exige o app |
| Botão físico da controladora, se houver (toque curto / longo / reset) | **A LEVANTAR**: exige o manual do modelo exato |
| Controle remoto RF 2,4 GHz, se houver | **A LEVANTAR**; fora do escopo do app, mas pode alterar o estado da placa por fora |

## 6. Estados de conexão e erros

| Situação | O que se sabe | Estado |
|---|---|---|
| Uma conexão por vez | a controladora aceita só uma conexão Bluetooth; com o app oficial conectado, outro app não conecta | confirmado em várias fontes abertas |
| Identificação na busca | serviço `FFE0`, característica `FFE1`; dados de fabricante com ID 20563 (0x5053) | de fonte aberta; A CONFIRMAR em hardware |
| Tipo de LED errado | fita não acende ou mostra cores erradas | dos manuais (solução de problemas) |
| Mensagens de erro, telas de "desconectado", tentativas de reconexão do app | — | **A LEVANTAR** |
| Permissões pedidas pelo app (Bluetooth, localização, microfone) | — | **A LEVANTAR** |

No nosso app os estados por placa são: desconectada, conectando, pronta e reconectando
(com número da tentativa e tempo até a próxima).

## 7. Roteiro para completar o levantamento

Com o app instalado e uma placa ligada (cerca de uma hora):

1. Anotar versão do app e modelo da placa (Q1).
2. Para cada uma das cinco páginas: listar todos os controles na ordem em que aparecem;
   para cada um, tipo (botão, deslizante, lista), faixa, valor inicial e efeito observado
   na fita. Anotar toque longo quando existir.
3. Em Configurações: listar todas as opções com o valor de fábrica (antes de mexer).
4. Provocar e anotar: placa fora de alcance, placa sem energia, Bluetooth desligado,
   segundo celular tentando conectar.
5. Opcional, e o que mais reduz risco: gravar um log HCI do Android durante o roteiro
   (*Opções do desenvolvedor → Ativar log de espionagem HCI Bluetooth*). Com ele os
   comandos em [protocolo-led.md](protocolo-led.md) são conferidos byte a byte.

Restrição do projeto: o levantamento descreve **função e comportamento**. Telas, textos,
ícones e marca do app original não são copiados; a interface deste app é própria.
